package app.photos

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import android.content.ContentUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Missing is not equivalent to deleted when access is restricted. */
enum class PhonePresence { PRESENT, TRASHED, MISSING, UNKNOWN }
object DeletionRules {
    fun absentResult(fullAccess: Boolean) = if (fullAccess) PhonePresence.MISSING else PhonePresence.UNKNOWN
    fun eligible(states: List<PhonePresence>): Boolean = states.isNotEmpty() && states.all { it == PhonePresence.TRASHED || it == PhonePresence.MISSING }
}
data class DeleteTarget(val drive: String, val uri: String, val name: String, val hash: String, val sources: List<String> = emptyList(), val review: Boolean = false)
data class ReviewItem(val target: DeleteTarget, val reason: String, val kept: Boolean)
data class DeletionReview(val items: List<ReviewItem>, val notice: String)

class DeletionManager(private val context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("deletions", Context.MODE_PRIVATE)
    private val repo = MediaRepository(context)
    fun fullAccess() = repo.has(Manifest.permission.READ_MEDIA_IMAGES) && repo.has(Manifest.permission.READ_MEDIA_VIDEO)
    fun beginPhoneRequest(uris: List<String>) {
        check(prefs.edit().putStringSet("pending", uris.toSet()).commit())
    }
    fun finishPhoneRequest(success: Boolean) {
        val pending = prefs.getStringSet("pending", emptySet())!!.toSet()
        val confirmed = prefs.getStringSet("confirmed", emptySet())!!.toSet()
        prefs.edit().putStringSet("confirmed", if (success) confirmed + pending else confirmed).remove("pending").apply()
    }
    /** Phone state for many sources at once: two MediaStore queries total instead of one per photo. */
    class PhoneSnapshot(val trashed: Map<String, Boolean>, val confirmed: Set<String>, val full: Boolean)
    private val collections = listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
    fun snapshot(): PhoneSnapshot {
        val map = HashMap<String, Boolean>()
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        for (collection in collections) {
            try {
                resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
                    while (c.moveToNext()) map[ContentUris.withAppendedId(collection, c.getLong(0)).toString()] = c.getInt(1) == 1
                }
            } catch (_: SecurityException) { }
        }
        return PhoneSnapshot(map, prefs.getStringSet("confirmed", emptySet())!!.toSet(), fullAccess())
    }
    fun presence(source: String, snapshot: PhoneSnapshot): PhonePresence {
        val uri = Uri.parse(source)
        if (uri.authority != MediaStore.AUTHORITY) return PhonePresence.UNKNOWN
        if (!snapshot.full && source !in snapshot.confirmed) return PhonePresence.UNKNOWN
        // Sources recorded in another URI form fall back to an individual query.
        if (collections.none { source.startsWith("$it/") }) return presence(source)
        return when (snapshot.trashed[source]) { true -> PhonePresence.TRASHED; false -> PhonePresence.PRESENT; null -> DeletionRules.absentResult(snapshot.full) }
    }
    /** One provider query for name/type/size/date instead of separate exists/isFile/name calls. */
    data class DriveFile(val name: String, val isFile: Boolean, val size: Long, val modified: Long)
    fun stat(uri: Uri): DriveFile? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else DriveFile(c.getString(0) ?: "Backup", c.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(2), c.getLong(3))
        }
    } catch (_: Exception) { null }
    fun presence(source: String): PhonePresence {
        val uri = Uri.parse(source)
        if (uri.authority != MediaStore.AUTHORITY) return PhonePresence.UNKNOWN
        val explicit = source in prefs.getStringSet("confirmed", emptySet())!!
        val full = fullAccess()
        // Do not interpret a selected-photos permission change as deletion.
        if (!full && !explicit) return PhonePresence.UNKNOWN
        return try {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { c ->
                if (!c.moveToFirst()) DeletionRules.absentResult(full)
                else if (c.getInt(0) == 1) PhonePresence.TRASHED else PhonePresence.PRESENT
            } ?: PhonePresence.UNKNOWN
        } catch (_: Exception) { PhonePresence.UNKNOWN }
    }
    suspend fun review(drive: String): DeletionReview {
        if (drive.isEmpty()) return DeletionReview(emptyList(), "Choose a backup drive first.")
        val root = DocumentFile.fromTreeUri(context, Uri.parse(drive))
        if (root?.exists() != true || !root.canRead()) return DeletionReview(emptyList(), "Drive offline or unavailable. Reconnect it to review stored copies.")
        val links = Journal(context).use { it.links(drive) }
        val phone = snapshot()
        // Phone checks are now in-memory lookups; only eligible backups touch the drive.
        val eligible = links.groupBy { it.target }.mapNotNull { (target, copies) ->
            val sources = copies.map { it.source }.distinct()
            val presence = sources.map { presence(it, phone) }
            if (DeletionRules.eligible(presence)) Triple(target, copies, presence) else null
        }
        val gate = Semaphore(8)
        val result = coroutineScope {
            eligible.map { (target, copies, presence) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        val file = stat(Uri.parse(target))?.takeIf { it.isFile } ?: return@withPermit null
                        val reason = if (presence.all { it == PhonePresence.TRASHED }) "In phone Trash" else "Not found on phone — may have been deleted or moved"
                        ReviewItem(DeleteTarget(drive, target, file.name, copies.first().hash, copies.map { it.source }.distinct(), review = true), reason, copies.all { it.kept })
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val notice = if (!phone.full) "Limited photo access: only readable, confirmed trashed items can be reviewed. Grant full photo/video access to review missing files and deletions made elsewhere." else "Only recorded backups with no active linked phone item are listed. A missing file may have been moved. Keep is the default; nothing is deleted automatically."
        return DeletionReview(result, notice)
    }
    /** Content check before deleting: a cached fingerprint is reused only while the file's size and date are unchanged. */
    suspend fun fingerprint(uri: Uri, file: DriveFile?): String {
        val cached = file?.let { Journal(context).use { j -> j.cachedHash(uri.toString(), it.size, it.modified) } }
        return cached ?: digest(uri).also { hash -> file?.let { Journal(context).use { j -> j.cacheHash(uri.toString(), it.size, it.modified, hash) } } }
    }
    suspend fun digest(uri: Uri): String {
        val job = currentCoroutineContext()
        return resolver.openInputStream(uri)?.use { Integrity.digest(it) { job.ensureActive() } } ?: error("Cannot read file. Reconnect the drive.")
    }
    suspend fun prepare(drive: String, items: List<MediaEntry>): List<DeleteTarget> = items.map {
        currentCoroutineContext().ensureActive()
        val uri = Uri.parse(it.uri)
        DeleteTarget(drive, it.uri, it.name, fingerprint(uri, stat(uri)))
    }
    suspend fun delete(target: DeleteTarget, phone: PhoneSnapshot? = null) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(target.drive)) ?: error("Drive unavailable")
        val uri = Uri.parse(target.uri)
        require(root.exists() && root.canWrite()) { "Drive disconnected or read-only. Nothing else will be deleted." }
        require(uri.authority == root.uri.authority && DocumentsContract.isTreeUri(uri) && DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(root.uri)) { "File is not in the confirmed drive folder." }
        val document = DocumentFile.fromSingleUri(context, uri) ?: error("File unavailable")
        val file = stat(uri) ?: error("${target.name} is no longer on the drive.")
        require(file.isFile) { "Only individual media files can be deleted." }
        require(fingerprint(uri, file) == target.hash) { "${target.name} changed since it was recorded. It was not deleted. Review it in the drive gallery." }
        if (target.review) {
            require(DeletionRules.eligible(target.sources.map { if (phone != null) presence(it, phone) else presence(it) })) { "Phone state changed or access is unavailable. Review again before deleting." }
        }
        currentCoroutineContext().ensureActive()
        check(document.delete()) { "Could not delete ${target.name}. Check drive access." }
        Journal(context).use { it.deletedTarget(target.uri) }
    }
}
