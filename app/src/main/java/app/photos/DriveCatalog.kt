package app.photos

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * The drive's own index: a catalog of the PhotoS folder and packed thumbnails, shared with the Windows app.
 * Format: docs/DRIVE-INDEX.md. Everything here is optional; callers fall back to listing the drive.
 */
class DriveCatalog private constructor(
    private val context: Context,
    val tree: Uri,
    /** Document id of the PhotoS folder; file ids below it are "<photosId>/<relative path>". */
    private val photosId: String,
    private val rootName: String,
    private val indexDir: DocumentFile?,
) {
    data class Entry(val path: String, val size: Long, val modified: Long, val date: Long, val hash: String, val mime: String)
    private data class Ref(val pack: Int, val offset: Long, val length: Int)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val thumbs = ConcurrentHashMap<String, Ref>()
    private val packUris = ConcurrentHashMap<Int, Uri>()
    private val packLengths = ConcurrentHashMap<Int, Long>()
    @Volatile private var dirty = false
    val writable get() = indexDir != null
    val size get() = entries.size

    fun relative(uri: String): String? {
        val id = runCatching { DocumentsContract.getDocumentId(Uri.parse(uri)) }.getOrNull() ?: return null
        return if (id.startsWith("$photosId/")) id.substring(photosId.length + 1) else null
    }
    fun entryFor(uri: String): Entry? = relative(uri)?.let { entries[it] }
    fun hasThumb(hash: String) = hash.isNotEmpty() && thumbs.containsKey(hash)

    fun remove(path: String) { if (entries.remove(path) != null) dirty = true }

    /** How many of [items] (a listing of this drive) are in the index with a thumbnail, and how many are in the PhotoS folder. */
    fun coverage(items: List<MediaEntry>): Pair<Int, Int> {
        var inFolder = 0; var indexed = 0
        for (item in items) {
            val e = entryFor(item.uri) ?: if (relative(item.uri) != null) { inFolder++; continue } else continue
            inFolder++
            if (e.size == item.size && hasThumb(e.hash)) indexed++
        }
        return indexed to inFolder
    }

    /** Sets of byte-identical files among [items], from the index fingerprints. Files without a current fingerprint are counted as unchecked. */
    fun duplicates(items: List<MediaEntry>): Duplicates {
        val byHash = HashMap<String, MutableList<MediaEntry>>()
        var unchecked = 0
        for (item in items) {
            val path = relative(item.uri) ?: continue
            val e = entries[path]
            if (e == null || e.hash.isEmpty() || e.size != item.size) { unchecked++; continue }
            byHash.getOrPut(e.hash) { mutableListOf() }.add(if (e.date > 0) item.copy(date = e.date) else item)
        }
        val groups = byHash.filterValues { it.size > 1 }.map { (hash, files) -> DuplicateSet(hash, files.first().size, files, keeper(files)) }
            .sortedByDescending { it.size * (it.files.size - 1) }
        return Duplicates(groups, unchecked)
    }
    /** Same rule as the desktop app: a named album over Other/Unsorted, then earliest date, then shortest path. */
    private fun keeper(files: List<MediaEntry>): String {
        fun album(f: MediaEntry) = relative(f.uri)?.substringBefore('/', "") ?: ""
        fun generic(f: MediaEntry) = if (album(f) in setOf("Other", "Unsorted", "")) 1 else 0
        return files.sortedWith(compareBy<MediaEntry>({ generic(it) }, { if (it.date > 0) it.date else Long.MAX_VALUE }, { relative(it.uri)?.length ?: Int.MAX_VALUE }, { relative(it.uri) ?: "" })).first().uri
    }
    fun albumOf(uri: String) = relative(uri)?.substringBeforeLast('/', "")?.ifEmpty { "PhotoS" } ?: ""

    fun put(entry: Entry) {
        if (entries.put(entry.path, entry) != entry) dirty = true
    }

    /** Appends one JPEG to the current pack. Only one writer at a time, so appends never interleave. */
    @Synchronized
    fun addThumb(hash: String, jpeg: ByteArray) {
        val dir = indexDir ?: return
        if (hash.isEmpty() || thumbs.containsKey(hash) || jpeg.size < 4) return
        var pack = packLengths.keys.maxOrNull() ?: 0
        var length = packLengths[pack] ?: 0L
        if (length > 0 && length + jpeg.size > PACK_LIMIT) { pack++; length = 0 }
        val uri = packUris[pack] ?: dir.createFile("application/octet-stream", packName(pack))?.uri?.also { packUris[pack] = it } ?: return
        context.contentResolver.openOutputStream(uri, "wa")?.use { it.write(jpeg) } ?: return
        packLengths[pack] = length + jpeg.size
        thumbs[hash] = Ref(pack, length, jpeg.size)
        dirty = true
    }
    fun addThumb(hash: String, bitmap: Bitmap) {
        if (hash.isEmpty() || thumbs.containsKey(hash)) return
        addThumb(hash, jpeg(bitmap))
    }

    /** Thumbnail bytes for a drive file, if the index has one and the file still has the indexed size. */
    fun get(path: String) = entries[path]
    fun thumbFor(uri: String, size: Long? = null): ByteArray? {
        val entry = entryFor(uri) ?: return null
        if (size != null && size > 0 && entry.size != size) return null
        val ref = thumbs[entry.hash] ?: return null
        val pack = packUris[ref.pack] ?: return null
        return runCatching {
            context.contentResolver.openFileDescriptor(pack, "r")?.let { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                    val buffer = ByteBuffer.allocate(ref.length)
                    var position = ref.offset
                    while (buffer.hasRemaining()) { val n = stream.channel.read(buffer, position); if (n <= 0) break; position += n }
                    buffer.array().takeIf { !buffer.hasRemaining() && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
                }
            }
        }.getOrNull()
    }

    /** The catalog as gallery entries, so a drive can be shown before it has been listed. */
    fun mediaEntries(): List<MediaEntry> {
        val list = entries.values.map { e ->
            val album = e.path.substringBeforeLast('/', "")
            MediaEntry(DocumentsContract.buildDocumentUriUsingTree(tree, "$photosId/${e.path}").toString(), e.path.substringAfterLast('/'), e.mime, e.size,
                if (e.date > 0) e.date else e.modified, listOf(rootName, "PhotoS", album).filter { it.isNotEmpty() }.joinToString("/"), e.modified)
        }
        // Make sure the constructed addresses really open on this drive before relying on them.
        val probe = list.firstOrNull() ?: return emptyList()
        val ok = runCatching { context.contentResolver.query(Uri.parse(probe.uri), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } }.getOrNull() == true
        return if (ok) list.sortedByDescending { it.date } else emptyList()
    }

    @Synchronized
    fun save() {
        val dir = indexDir ?: return
        if (!dirty) return
        val items = JSONArray()
        entries.values.sortedBy { it.path }.forEach { items.put(JSONArray().put(it.path).put(it.size).put(it.modified).put(it.date).put(it.hash).put(it.mime)) }
        val refs = JSONObject()
        thumbs.forEach { (hash, r) -> refs.put(hash, JSONArray().put(r.pack).put(r.offset).put(r.length)) }
        val bytes = JSONObject().put("version", 1).put("updated", System.currentTimeMillis()).put("items", items).put("thumbs", refs).toString().toByteArray()
        dir.findFile(TEMP)?.delete()
        val temp = dir.createFile("application/octet-stream", TEMP) ?: return
        context.contentResolver.openOutputStream(temp.uri, "wt")?.use { it.write(bytes) } ?: return
        dir.findFile(NAME)?.delete()
        if (temp.renameTo(NAME)) dirty = false
    }

    private fun load(dir: DocumentFile) {
        var catalog: DocumentFile? = null; var temp: DocumentFile? = null
        for (file in dir.listFiles()) {
            val name = file.name ?: continue
            PACK.matchEntire(name)?.let { m -> val n = m.groupValues[1].toInt(); packUris[n] = file.uri; packLengths[n] = file.length() }
            if (name == NAME) catalog = file
            if (name == TEMP) temp = file
        }
        val source = catalog ?: temp ?: return
        val json = JSONObject(context.contentResolver.openInputStream(source.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return)
        if (json.optInt("version") != 1) return
        json.optJSONArray("items")?.let { a ->
            for (i in 0 until a.length()) a.optJSONArray(i)?.let { r ->
                val path = r.optString(0)
                if (path.isNotEmpty() && !path.startsWith("/") && ".." !in path.split('/'))
                    entries[path] = Entry(path, r.optLong(1), r.optLong(2), r.optLong(3), r.optString(4), r.optString(5, "application/octet-stream"))
            }
        }
        json.optJSONObject("thumbs")?.let { t ->
            t.keys().forEach { hash -> t.optJSONArray(hash)?.let { r -> if (r.length() == 3) thumbs[hash] = Ref(r.optInt(0), r.optLong(1), r.optInt(2)) } }
        }
    }

    companion object {
        private const val NAME = "catalog.json"
        private const val TEMP = "catalog.json.new"
        private const val PACK_LIMIT = 64L * 1024 * 1024
        private val PACK = Regex("thumbs-(\\d{3,})\\.bin")
        private fun packName(n: Int) = "thumbs-%03d.bin".format(n)
        fun jpeg(bitmap: Bitmap): ByteArray {
            val scale = 512f / maxOf(bitmap.width, bitmap.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        }

        /** Deletes the drive's index (for a rebuild). Photos are not touched. */
        fun deleteIndex(context: Context, tree: Uri) {
            runCatching {
                val root = DocumentFile.fromTreeUri(context, tree) ?: return
                val photos = (if (root.name == "PhotoS") root else root.findFile("PhotoS")) ?: return
                photos.findFile(".index")?.let { dir -> dir.listFiles().forEach { it.delete() }; dir.delete() }
            }
        }
        /** Opens the index of the PhotoS folder on [tree]. With [create], makes `.index` when the drive is writable. */
        fun open(context: Context, tree: Uri, create: Boolean): DriveCatalog? = runCatching {
            val root = DocumentFile.fromTreeUri(context, tree) ?: return null
            // The chosen folder is usually the drive root; it may also be the PhotoS folder itself.
            val photos = (if (root.name == "PhotoS") root else root.findFile("PhotoS"))?.takeIf { it.isDirectory } ?: return null
            var index = photos.findFile(".index")?.takeIf { it.isDirectory }
            if (index == null && create && photos.canWrite()) index = photos.createDirectory(".index")
            // A read-only drive still gives its catalog and thumbnails; it just isn't written to.
            DriveCatalog(context, tree, DocumentsContract.getDocumentId(photos.uri), if (root.name == "PhotoS") "" else root.name.orEmpty(), index?.takeIf { it.canWrite() })
                .also { c -> index?.let(c::load) }
        }.getOrNull()

    }
}

data class DuplicateSet(val hash: String, val size: Long, val files: List<MediaEntry>, val keep: String)
data class Duplicates(val groups: List<DuplicateSet>, val unchecked: Int) {
    val extra get() = groups.sumOf { it.files.size - 1 }
    val bytes get() = groups.sumOf { it.size * (it.files.size - 1) }
}

/** Catalogs of drives opened in this session, so thumbnails can be served from a drive's packs. */
object DriveCatalogs {
    private val open = ConcurrentHashMap<String, DriveCatalog>()
    fun register(tree: String, catalog: DriveCatalog?) { if (catalog == null) open.remove(tree) else open[tree] = catalog }
    fun thumbFor(uri: String, size: Long?): ByteArray? = open.values.firstNotNullOfOrNull { c -> if (c.relative(uri) != null) c.thumbFor(uri, size) else null }
}
