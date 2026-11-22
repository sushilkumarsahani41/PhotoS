package app.photos

import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

data class MediaEntry(val uri: String, val name: String, val mime: String, val size: Long, val date: Long, val folder: String, val modified: Long = date, val duration: Long = 0) {
    val video = mime.startsWith("video/")
    /** Local calendar day, computed once at load time (off the main thread) for fast date grouping. */
    val day: Long = Math.floorDiv(date + zone.getOffset(date), 86_400_000L)
    private companion object { val zone: java.util.TimeZone = java.util.TimeZone.getDefault() }
}
data class Drive(val uri: String, val name: String)

class MediaRepository(val context: Context) {
    private val preferences = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    fun drives(): List<Drive> {
        val a = JSONArray(preferences.getString("drives", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { j -> Drive(j.getString("uri"), j.getString("name")) } }
    }
    fun remember(uri: Uri): Drive {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val doc = DocumentFile.fromTreeUri(context, uri) ?: error("This folder is unavailable")
        require(doc.isDirectory && doc.canRead()) { "Connect the drive and choose a readable folder." }
        val known = drives()
        // Keep a name you already gave this folder; else use the name saved on the drive (from another phone or an earlier install).
        val name = known.find { it.uri == uri.toString() }?.name ?: readDriveName(doc) ?: defaultName(doc, uri, known)
        val drive = Drive(uri.toString(), name)
        save(known.filterNot { it.uri == drive.uri } + drive)
        return drive
    }
    /** Gives a drive your own name. It is also written to the drive (PhotoS/.photos-drive.json) so it follows the drive. */
    fun rename(uri: String, name: String): Drive {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
        require(clean.isNotEmpty()) { "Enter a name for the drive." }
        val drive = Drive(uri, clean)
        save(drives().map { if (it.uri == uri) drive else it })
        runCatching { writeDriveName(uri, clean) } // a read-only or disconnected drive keeps the name on this phone only
        return drive
    }
    private fun save(list: List<Drive>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        preferences.edit().putString("drives", a.toString()).apply()
    }
    private fun defaultName(doc: DocumentFile, uri: Uri, known: List<Drive>): String {
        val base = doc.name?.takeIf { it.isNotBlank() } ?: "USB drive"
        return if (known.none { it.name == base }) base else "$base · ${uri.toString().hashCode().toUInt().toString(16).takeLast(4)}"
    }
    private fun readDriveName(root: DocumentFile): String? = runCatching {
        val folder = root.findFile("PhotoS") ?: root.findFile("DualVault") ?: return null
        val file = folder.findFile(NAME_FILE) ?: return null
        val text = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        JSONObject(text).optString("name").trim().take(MAX_NAME).takeIf { it.isNotEmpty() }
    }.getOrNull()
    private fun writeDriveName(uri: String, name: String) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: return
        if (!root.canWrite()) return
        val folder = root.findFile("PhotoS") ?: root.findFile("DualVault")?.takeIf { it.isDirectory && it.renameTo("PhotoS") } ?: root.createDirectory("PhotoS") ?: return
        val file = folder.findFile(NAME_FILE) ?: folder.createFile("application/octet-stream", NAME_FILE) ?: return
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(JSONObject().put("name", name).toString().toByteArray()) }
    }
    fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun accessLabel(): String = when {
        has(Manifest.permission.READ_MEDIA_IMAGES) && has(Manifest.permission.READ_MEDIA_VIDEO) -> "Full photo and video access"
        has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "Selected media only • grant more access to include more files"
        has(Manifest.permission.READ_MEDIA_IMAGES) -> "Photo access only"
        has(Manifest.permission.READ_MEDIA_VIDEO) -> "Video access only"
        else -> "Grant photo and video access to browse your phone"
    }
    suspend fun phone(): List<MediaEntry> {
        val items = mutableListOf<MediaEntry>()
        val columns = arrayOf("_id", "_display_name", "mime_type", "_size", "date_modified", "relative_path", "datetaken", "duration")
        val collections = listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        for (collection in collections) {
            currentCoroutineContext().ensureActive()
            // A denied media category can return an empty cursor or throw on some providers.
            try {
                context.contentResolver.query(collection, columns, "is_pending = 0 AND is_trashed = 0", null, "date_modified DESC")?.use { c ->
                    while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        items += MediaEntry(ContentUris.withAppendedId(collection, c.getLong(0)).toString(), c.getString(1) ?: "Media", c.getString(2) ?: "application/octet-stream", c.getLong(3), c.getLong(6).takeIf { it > 0 } ?: (c.getLong(4) * 1000), c.getString(5) ?: "Other", c.getLong(4) * 1000, if (c.isNull(7)) 0 else c.getLong(7))
                    }
                }
            } catch (_: SecurityException) { /* Other granted categories remain usable. */ }
        }
        return items.sortedByDescending { it.date }
    }
    /**
     * Lists photos and videos on a drive folder. Folders are read in parallel (USB storage answers several queries at once),
     * hidden and system folders are skipped, and [onProgress] receives the growing list a few times per second.
     */
    suspend fun drive(uri: String, onProgress: (suspend (List<MediaEntry>) -> Unit)? = null): List<MediaEntry> = coroutineScope {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(uri)) ?: error("Drive unavailable")
        require(root.exists() && root.canRead()) { "Drive offline or permission expired. Reconnect it or add its folder again." }
        val result = java.util.concurrent.ConcurrentLinkedQueue<MediaEntry>()
        val visited = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val folders = Channel<Pair<Uri, String>>(Channel.UNLIMITED)
        val pending = java.util.concurrent.atomic.AtomicInteger(1)
        val lastReport = java.util.concurrent.atomic.AtomicLong(android.os.SystemClock.elapsedRealtime())
        folders.send(root.uri to (root.name ?: "Drive"))
        val workers = List(DRIVE_WORKERS) {
            launch(Dispatchers.IO) {
                for ((folder, path) in folders) {
                    try {
                        if (!visited.add(folder.toString())) continue
                        // Query directly: DocumentFile.listFiles can silently return an empty list on provider errors.
                        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
                        val cursor = context.contentResolver.query(childUri, arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified"), null, null, null) ?: error("Cannot read drive folder: $path")
                        cursor.use { c -> while (c.moveToNext()) {
                            ensureActive()
                            val child = DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0))
                            val name = c.getString(1) ?: "Media"
                            val mime = c.getString(2) ?: "application/octet-stream"
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (!skipFolder(name)) { pending.incrementAndGet(); folders.send(child to "$path/$name") }
                            } else if ((mime.startsWith("image/") || mime.startsWith("video/")) && !name.endsWith(".part") && !name.startsWith(".")) {
                                // Hidden files are never photos: "._name" files are Mac metadata written next to each file on exFAT/FAT drives;
                                // ".photos-part-*" are unfinished copies.
                                result += MediaEntry(child.toString(), name, mime, c.getLong(3), c.getLong(4), path)
                            }
                        } }
                        val now = android.os.SystemClock.elapsedRealtime()
                        val last = lastReport.get()
                        if (onProgress != null && now - last > 350 && lastReport.compareAndSet(last, now)) onProgress(result.sortedByDescending { it.date })
                    } finally {
                        if (pending.decrementAndGet() == 0) folders.close()
                    }
                }
            }
        }
        workers.joinAll()
        result.sortedByDescending { it.date }
    }

    private companion object {
        const val NAME_FILE = ".photos-drive.json"
        const val MAX_NAME = 40
        const val DRIVE_WORKERS = 6
        // Hidden folders and the ones Windows, macOS and Android create on removable drives never hold your photos.
        private val SYSTEM = setOf("system volume information", "\$recycle.bin", "recycler", "lost.dir", "found.000")
        fun skipFolder(name: String) = name.startsWith(".") || name.lowercase() in SYSTEM
    }
}
