package app.photos

import android.provider.DocumentsContract

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

 data class TransferStatus(val busy: Boolean = false, val message: String = "Ready for your first backup", val done: Int = 0, val total: Int = 0, val paused: Boolean = false)

class TransferService : Service() {
    companion object {
        val status = MutableStateFlow(TransferStatus())
        /** While true, no new file starts; files already being copied finish and are verified. */
        private val pausedFlow = MutableStateFlow(false)
        const val DEFAULT_PARALLEL = 4
        const val MAX_PARALLEL = 10
        fun delete(context: Context, targets: List<DeleteTarget>) {
            require(targets.isNotEmpty())
            check(!status.value.busy) { "Wait for the current operation to finish." }
            val array = JSONArray()
            targets.forEach { item -> array.put(JSONObject().put("drive", item.drive).put("uri", item.uri).put("name", item.name).put("hash", item.hash).put("review", item.review).put("sources", JSONArray(item.sources))) }
            val request = File(context.filesDir, "transfer-${UUID.randomUUID()}.json")
            request.writeText(JSONObject().put("mode", "delete").put("items", array).toString())
            try { context.startForegroundService(Intent(context, TransferService::class.java).putExtra("request", request.name)) }
            catch (e: Exception) { request.delete(); throw e }
        }
        /** Builds the drive's fast index (catalog + packed thumbnails, docs/DRIVE-INDEX.md) for files already on it. */
        /** [background]: two files at a time so the phone stays responsive. [rebuild]: discard the existing index first. */
        fun index(context: Context, destination: String, background: Boolean = false, rebuild: Boolean = false) {
            check(!status.value.busy) { "Wait for the current operation to finish." }
            val request = File(context.filesDir, "transfer-${UUID.randomUUID()}.json")
            val parallel = if (background) 2 else context.getSharedPreferences("library", Context.MODE_PRIVATE).getInt("parallel", DEFAULT_PARALLEL).coerceIn(1, MAX_PARALLEL)
            request.writeText(JSONObject().put("mode", "index").put("destination", destination).put("parallel", parallel).put("rebuild", rebuild).put("background", background).put("items", JSONArray()).toString())
            try { context.startForegroundService(Intent(context, TransferService::class.java).putExtra("request", request.name)) }
            catch (e: Exception) { request.delete(); throw e }
        }
        /**
         * Removes extra copies of identical files from a drive. Each set keeps [DuplicateSet.keep]; before each deletion the kept file
         * and the copy are re-read and must still match the set's fingerprint. USB drives have no Trash on Android, so this is permanent.
         */
        fun removeDuplicates(context: Context, destination: String, sets: List<DuplicateSet>) {
            check(!status.value.busy) { "Wait for the current operation to finish." }
            val array = JSONArray()
            sets.forEach { set -> array.put(JSONObject().put("hash", set.hash).put("size", set.size).put("keep", set.keep).put("remove", JSONArray(set.files.map { it.uri }.filter { it != set.keep }))) }
            val request = File(context.filesDir, "transfer-${UUID.randomUUID()}.json")
            request.writeText(JSONObject().put("mode", "dedupe").put("destination", destination).put("items", array).toString())
            try { context.startForegroundService(Intent(context, TransferService::class.java).putExtra("request", request.name)) }
            catch (e: Exception) { request.delete(); throw e }
        }
        /** [deep] re-reads every file byte-for-byte; the default quick mode trusts unchanged size/date and cached fingerprints. */
        fun start(context: Context, items: List<MediaEntry>, destination: String?, checkOnly: Boolean = false, deep: Boolean = false) {
            require(items.isNotEmpty()) { "Select at least one file" }
            check(!status.value.busy) { "A transfer is already running" }
            val array = JSONArray()
            items.forEach { array.put(JSONObject().put("uri", it.uri).put("name", it.name).put("mime", it.mime).put("size", it.size).put("modified", it.modified).put("date", it.date).put("folder", it.folder)) }
            val request = File(context.filesDir, "transfer-${UUID.randomUUID()}.json")
            val parallel = context.getSharedPreferences("library", Context.MODE_PRIVATE).getInt("parallel", DEFAULT_PARALLEL).coerceIn(1, MAX_PARALLEL)
            request.writeText(JSONObject().put("items", array).put("destination", destination ?: "").put("checkOnly", checkOnly).put("deep", deep).put("parallel", parallel).toString())
            try { context.startForegroundService(Intent(context, TransferService::class.java).putExtra("request", request.name)) }
            catch (e: Exception) { request.delete(); throw e }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("transfer", "Backup and restore", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        val paused = pausedFlow.value
        val toggle = PendingIntent.getService(this, 2, Intent(this, TransferService::class.java).setAction(if (paused) "resume" else "pause"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "transfer").setSmallIcon(R.drawable.ic_vault).setContentTitle(if (paused) "PhotoS transfer paused" else "PhotoS transfer")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (paused) "Resume" else "Pause", toggle)
            .addAction(0, "Stop", cancel).build()
    }
    private var lastProgress = 0L
    /** UI and notification updates are throttled; per-file updates on large batches otherwise flood both. */
    @Synchronized
    private fun progress(message: String, done: Int, total: Int, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastProgress < 400) return
        lastProgress = now
        // While paused, files that were already copying still finish: update the counts but keep the "Paused" message.
        if (pausedFlow.value) { status.value = status.value.copy(done = done, total = total); return }
        status.value = TransferStatus(true, message, done, total)
        getSystemService(NotificationManager::class.java).notify(1, notification(message))
    }
    private fun setPaused(value: Boolean) {
        if (job?.isActive != true || pausedFlow.value == value) return
        pausedFlow.value = value
        // Let the phone sleep while paused; take the wake lock again on resume.
        wake?.let { if (value && it.isHeld) it.release() else if (!value && !it.isHeld) it.acquire(6 * 60 * 60 * 1000L) }
        val current = status.value
        val message = if (value) "Paused · ${current.done} of ${current.total} done. Copies already made are kept." else "Resuming…"
        status.value = current.copy(message = message, paused = value)
        getSystemService(NotificationManager::class.java).notify(1, notification(message))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "cancel" -> { pausedFlow.value = false; job?.cancel(); return START_NOT_STICKY }
            "pause" -> { setPaused(true); return START_NOT_STICKY }
            "resume" -> { setPaused(false); return START_NOT_STICKY }
        }
        val requestName = intent?.getStringExtra("request") ?: run { stopSelf(); return START_NOT_STICKY }
        val request = File(filesDir, File(requestName).name)
        if (job?.isActive == true) { request.delete(); return START_NOT_STICKY }
        try {
            startForeground(1, notification("Preparing transfer"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            status.value = TransferStatus(message = "Could not start: ${e.message}")
            request.delete(); stopSelf(); return START_NOT_STICKY
        }
        pausedFlow.value = false
        status.value = TransferStatus(true, "Preparing transfer")
        lastProgress = 0L
        folders.clear()
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoS:transfer").apply { acquire(6 * 60 * 60 * 1000L) }
        job = scope.launch {
            val doneCount = AtomicInteger(0)
            var total = 0
            var summary: String
            val copiedCount = AtomicInteger(0)
            val matchedCount = AtomicInteger(0)
            var deleting = false
            // The drive's fast index; filled while checking and backing up, saved at the end even if stopped.
            var catalog: DriveCatalog? = null
            var catalogDrive = ""
            try {
                val data = JSONObject(request.readText())
                request.delete()
                val entries = data.getJSONArray("items")
                deleting = data.optString("mode") == "delete"
                if (deleting) {
                    total = entries.length()
                    val manager = DeletionManager(this@TransferService)
                    for (i in 0 until total) {
                        ensureActive()
                        val j = entries.getJSONObject(i)
                        val sources = j.getJSONArray("sources")
                        progress("Deleting ${i + 1}/$total • ${j.getString("name")}", doneCount.get(), total)
                        manager.delete(DeleteTarget(j.getString("drive"), j.getString("uri"), j.getString("name"), j.getString("hash"), (0 until sources.length()).map { sources.getString(it) }, j.getBoolean("review")))
                        doneCount.incrementAndGet()
                    }
                    summary = "Permanently deleted ${doneCount.get()} drive files. Phone files were not touched."
                } else if (data.optString("mode") == "index") {
                    val destination = data.getString("destination")
                    val workers = data.optInt("parallel", DEFAULT_PARALLEL).coerceIn(1, MAX_PARALLEL)
                    progress("Reading the drive…", 0, 0, force = true)
                    renameLegacyFolder(destination)
                    if (data.optBoolean("rebuild")) DriveCatalog.deleteIndex(this@TransferService, Uri.parse(destination))
                    val c = DriveCatalog.open(this@TransferService, Uri.parse(destination), create = true)?.takeIf { it.writable }
                        ?: error("This drive has no PhotoS folder yet, or it is read-only. Back up to it first.")
                    catalog = c; catalogDrive = destination
                    val onDrive = MediaRepository(this@TransferService).drive(destination).filter { c.relative(it.uri) != null }
                    // Only files the index doesn't cover yet (new, changed, or without a thumbnail).
                    val todo = onDrive.filter { item -> val e = c.entryFor(item.uri); e == null || e.size != item.size || e.hash.isEmpty() || !c.hasThumb(e.hash) }
                    total = todo.size
                    Journal(this@TransferService).use { journal ->
                        parallel(total, workers) { i ->
                            val item = todo[i]
                            val uri = Uri.parse(item.uri)
                            val known = c.entryFor(item.uri)?.takeIf { it.size == item.size && it.hash.isNotEmpty() }?.hash ?: journal.cachedHash(item.uri, item.size, item.modified)
                            val result = DriveThumbs.index(this@TransferService, uri, item.mime, item.size, known)
                            if (known == null) journal.cacheHash(item.uri, item.size, item.modified, result.hash)
                            val previous = c.entryFor(item.uri)
                            c.put(DriveCatalog.Entry(c.relative(item.uri)!!, item.size, item.modified, result.date.takeIf { it > 0 } ?: previous?.date?.takeIf { it > 0 } ?: Integrity.dateFromName(item.name) ?: 0, result.hash, item.mime))
                            result.jpeg?.let { c.addThumb(result.hash, it) }
                            val done = doneCount.incrementAndGet()
                            if (done % 200 == 0) runCatching { c.save() }
                            progress("Building fast index $done of $total", done, total, force = done == total)
                        }
                    }
                    summary = if (total == 0) "Fast index is already up to date • ${onDrive.size} photos" else "Fast index ready • $total photos added • drive opens faster on any phone or PC"
                } else if (data.optString("mode") == "dedupe") {
                    val destination = data.getString("destination")
                    val c = DriveCatalog.open(this@TransferService, Uri.parse(destination), create = false)
                    catalog = c?.takeIf { it.writable }; catalogDrive = destination
                    total = (0 until entries.length()).sumOf { entries.getJSONObject(it).getJSONArray("remove").length() }
                    var removed = 0; var freed = 0L; var kept = 0
                    for (i in 0 until entries.length()) {
                        val set = entries.getJSONObject(i)
                        val expected = set.getString("hash")
                        val size = set.getLong("size")
                        val keeperOk = runCatching { hash(Uri.parse(set.getString("keep"))) == expected }.getOrDefault(false)
                        val remove = set.getJSONArray("remove")
                        for (j in 0 until remove.length()) {
                            pausedFlow.first { !it }
                            ensureActive()
                            val uri = remove.getString(j)
                            val ok = keeperOk && runCatching { hash(Uri.parse(uri)) == expected }.getOrDefault(false) &&
                                runCatching { DocumentsContract.deleteDocument(contentResolver, Uri.parse(uri)) }.getOrDefault(false)
                            if (ok) { removed++; freed += size; c?.relative(uri)?.let { c.remove(it) } } else kept++
                            val done = doneCount.incrementAndGet()
                            progress("Removing duplicates $done of $total", done, total, force = done == total)
                        }
                    }
                    summary = "Removed $removed extra ${if (removed == 1) "copy" else "copies"} • ${android.text.format.Formatter.formatShortFileSize(this@TransferService, freed)} freed" +
                        if (kept > 0) " • $kept kept because they changed or could not be checked" else ""
                } else {
                val destination = data.getString("destination")
                val checkOnly = data.optBoolean("checkOnly")
                val workers = data.optInt("parallel", DEFAULT_PARALLEL).coerceIn(1, MAX_PARALLEL)
                total = entries.length()
                if (destination.isNotEmpty()) require(MediaRepository(this@TransferService).has(Manifest.permission.ACCESS_MEDIA_LOCATION)) { "Grant original metadata access before backup." }
                val deep = data.optBoolean("deep")
                // Shared between workers: content hash -> drive files known to have that content.
                val driveIndex = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
                fun index(hash: String) = driveIndex.computeIfAbsent(hash) { CopyOnWriteArrayList() }
                // Identical photos must not be copied twice when two workers reach them at the same moment.
                val contentLocks = ConcurrentHashMap<String, Mutex>()
                suspend fun <T> locked(hash: String, block: suspend () -> T): T = contentLocks.computeIfAbsent(hash) { Mutex() }.withLock { block() }
                Journal(this@TransferService).use { journal ->
                    var driveItems = emptyList<MediaEntry>()
                    if (destination.isNotEmpty()) {
                        progress("Reading the selected drive…", 0, total, force = true)
                        renameLegacyFolder(destination)
                        driveItems = MediaRepository(this@TransferService).drive(destination)
                        if (deep) {
                            val indexed = AtomicInteger(0)
                            parallel(driveItems.size, workers) { i ->
                                val item = driveItems[i]
                                val digest = journal.cachedHash(item.uri, item.size, item.modified)
                                    ?: hash(Uri.parse(item.uri)).also { journal.cacheHash(item.uri, item.size, item.modified, it) }
                                index(digest).add(item.uri)
                                progress("Indexing drive files ${indexed.incrementAndGet()}/${driveItems.size}", 0, total)
                            }
                        }
                        val root = DocumentFile.fromTreeUri(this@TransferService, Uri.parse(destination))
                        require(root?.exists() == true && root.canRead()) { "Drive disconnected during scan. Previous status was kept." }
                        catalog = DriveCatalog.open(this@TransferService, Uri.parse(destination), create = true)?.takeIf { it.writable }
                        catalogDrive = destination
                    }
                    val recordedCount = AtomicInteger(0)
                    val previous = if (destination.isEmpty()) emptyMap() else journal.statuses(destination)
                    // Quick mode: a phone file can only be on the drive if a drive file has exactly the same size.
                    val bySize = driveItems.groupBy { it.size }
                    val byDocument = driveItems.associateBy { documentId(it.uri) }
                    val driveHashes = ConcurrentHashMap<String, String>()
                    val readThisRun = ConcurrentHashMap.newKeySet<String>()
                    suspend fun driveHash(item: MediaEntry): String = driveHashes[item.uri] ?: run {
                        val digest = journal.cachedHash(item.uri, item.size, item.modified)
                            ?: hash(Uri.parse(item.uri)).also { journal.cacheHash(item.uri, item.size, item.modified, it); readThisRun.add(item.uri) }
                        if (driveHashes.putIfAbsent(item.uri, digest) == null) index(digest).add(item.uri)
                        digest
                    }
                    val verb = when { destination.isEmpty() -> "Restoring"; checkOnly -> "Checking"; else -> "Backing up" }
                    parallel(total, workers) { i ->
                        val entry = entries.getJSONObject(i)
                        val sourceUri = entry.optString("uri")
                        val size = entry.optLong("size")
                        val modified = entry.optLong("modified")
                        val source = MediaStore.setRequireOriginal(Uri.parse(sourceUri))
                        val old = previous[sourceUri]
                        // Adds a drive file to the fast index. The phone already has a thumbnail of the same content, so this costs almost nothing.
                        fun record(hash: String, target: String) {
                            val c = catalog ?: return
                            val path = c.relative(target) ?: return
                            c.put(DriveCatalog.Entry(path, size, byDocument[documentId(target)]?.modified ?: System.currentTimeMillis(), entry.optLong("date"), hash, entry.optString("mime")))
                            if (!c.hasThumb(hash)) runCatching { contentResolver.loadThumbnail(Uri.parse(sourceUri), android.util.Size(512, 512), null) }.getOrNull()?.let { c.addThumb(hash, it) }
                            if (recordedCount.incrementAndGet() % 300 == 0) runCatching { c.save() }
                        }
                        suspend fun present(hash: String, target: String, verifiedNow: Boolean) {
                            record(hash, target)
                            val now = System.currentTimeMillis()
                            journal.sync(destination, sourceUri, SyncRecord(hash, target, "present", now, if (verifiedNow) now else old?.verified?.takeIf { it > 0 } ?: now, size, modified))
                            matchedCount.incrementAndGet()
                        }
                        fun absent(hash: String?) {
                            val lastVerifiedHash = old?.takeIf { it.verified > 0 }?.hash
                            // Without a fresh hash, an unchanged file keeps its last known hash; a modified one counts as changed.
                            val current = hash ?: if (old != null && old.size == size && old.modified == modified) lastVerifiedHash.orEmpty() else ""
                            journal.sync(destination, sourceUri, SyncRecord(lastVerifiedHash ?: hash ?: "", "", SyncRules.absentState(lastVerifiedHash, current), System.currentTimeMillis(), old?.verified ?: 0, size, modified))
                        }
                        suspend fun copyNew(hash: String) {
                            val (actualHash, target) = backup(entry, destination, journal, hash)
                            DeletionManager(this@TransferService).stat(Uri.parse(target))?.let { journal.cacheHash(target, it.size, it.modified, actualHash) }
                            val verifiedAt = System.currentTimeMillis()
                            journal.sync(destination, sourceUri, SyncRecord(actualHash, target, "present", verifiedAt, verifiedAt, size, modified))
                            index(actualHash).add(target)
                            record(actualHash, target)
                            copiedCount.incrementAndGet()
                        }
                        suspend fun freshHash() = hash(source).also { journal.cacheHash(sourceUri, size, modified, it) }
                        if (destination.isEmpty()) restore(entry, journal)
                        else if (deep) {
                            // Deep verify: read the phone file and the matching drive file in full.
                            val expected = freshHash()
                            locked(expected) {
                                val match = driveIndex[expected]?.firstOrNull { hash(Uri.parse(it)) == expected }
                                if (match != null) present(expected, match, verifiedNow = true)
                                else { absent(expected); if (!checkOnly) copyNew(expected) }
                            }
                        } else {
                            val recorded = old?.takeIf { it.state == "present" && it.target.isNotEmpty() && it.size == size && it.modified == modified }
                            val stillThere = recorded?.let { byDocument[documentId(it.target)] }?.takeIf { it.size == size }
                            val candidates = bySize[size].orEmpty()
                            if (recorded != null && stillThere != null) present(recorded.hash, recorded.target, verifiedNow = false)
                            else if (candidates.isEmpty() && checkOnly) absent(null)
                            else {
                                val cached = journal.cachedHash(sourceUri, size, modified)
                                val expected = cached ?: freshHash()
                                locked(expected) {
                                    val match = driveIndex[expected]?.firstOrNull() ?: candidates.firstOrNull { driveHash(it) == expected }?.uri
                                    if (match != null) present(expected, match, verifiedNow = match in readThisRun)
                                    else {
                                        absent(expected)
                                        // Copies always start from a freshly read hash, never a cached one.
                                        if (!checkOnly) copyNew(if (cached == null) expected else freshHash())
                                    }
                                }
                            }
                        }
                        val done = doneCount.incrementAndGet()
                        progress("$verb $done of $total${if (workers > 1) " • $workers at a time" else ""}", done, total, force = done == total)
                    }
                }
                val done = doneCount.get(); val matched = matchedCount.get(); val copied = copiedCount.get()
                summary = when {
                    destination.isEmpty() -> "Restore complete • $done files verified"
                    checkOnly -> "Check complete • $matched already on drive • ${total - matched} missing or changed"
                    else -> "Backup complete • $copied copied • $matched already on drive (skipped)"
                }
                }
            } catch (_: CancellationException) {
                val done = doneCount.get()
                summary = if (deleting) "Stopped • $done of $total files deleted. Remaining files were kept; no automatic retry." else "Stopped • $done of $total checked. Run again to resume."
            } catch (e: Exception) {
                val done = doneCount.get()
                summary = if (deleting) "Stopped • $done of $total files deleted. ${e.message}. Remaining files were kept." else "Paused • $done of $total checked. ${e.message ?: "Reconnect the drive and retry."}"
            } finally {
                request.delete()
                withContext(NonCancellable) { catalog?.let { c -> runCatching { c.save() }; DriveCatalogs.register(catalogDrive, c) } }
            }
            getSharedPreferences("library", MODE_PRIVATE).edit().putString("lastTransfer", summary).apply()
            pausedFlow.value = false
            status.value = TransferStatus(false, summary, doneCount.get(), total)
            withContext(NonCancellable + Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun documentId(uri: String) = try { android.provider.DocumentsContract.getDocumentId(Uri.parse(uri)) } catch (_: Exception) { uri }
    /** Runs [work] for indexes 0 until [count] on up to [workers] coroutines. The first failure cancels the rest. */
    private suspend fun parallel(count: Int, workers: Int, work: suspend (Int) -> Unit) = coroutineScope {
        val next = AtomicInteger(0)
        repeat(workers.coerceIn(1, count.coerceAtLeast(1))) {
            launch {
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= count) break
                    pausedFlow.first { !it }
                    ensureActive()
                    work(i)
                }
            }
        }
    }
    private suspend fun hash(uri: Uri): String {
        val coroutine = currentCoroutineContext()
        return contentResolver.openInputStream(uri)?.use { Integrity.digest(it) { coroutine.ensureActive() } } ?: error("Cannot read file. Check the drive connection.")
    }
    private suspend fun copy(source: Uri, target: Uri): String {
        val coroutine = currentCoroutineContext()
        return contentResolver.openInputStream(source)?.use { input ->
            contentResolver.openOutputStream(target, "wt")?.use { output -> Integrity.transfer(input, output) { coroutine.ensureActive() } } ?: error("Cannot write to destination. Check free space and drive access.")
        } ?: error("Cannot read source file")
    }
    private suspend fun existing(journal: Journal, key: String, expected: String, restore: Boolean): Boolean {
        val old = journal.get(key) ?: return false
        if (old.complete && old.hash == expected) {
            val verified = try { hash(Uri.parse(old.uri)) == expected } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (verified) return true
        }
        if (!old.complete) {
            // Only remove a partial file created by this app, identified in its private journal.
            val uri = Uri.parse(old.uri)
            if (restore) contentResolver.delete(uri, null, null)
            else DocumentFile.fromSingleUri(this, uri)?.let { if (it.exists()) check(it.delete()) { "Could not clean up an interrupted copy" } }
        }
        return false
    }
    private val folders = mutableMapOf<String, DocumentFile>()
    /** PhotoS/<album> on the drive, created on first use and cached for the rest of the run. A drive backed up before the rename has its DualVault folder renamed to PhotoS once. */
    /** Drives backed up before the rename keep their copies in DualVault; rename that folder to PhotoS once, before listing the drive. */
    private fun renameLegacyFolder(destination: String) {
        runCatching {
            val root = DocumentFile.fromTreeUri(this, Uri.parse(destination)) ?: return
            if (root.canWrite() && root.findFile("PhotoS") == null) root.findFile("DualVault")?.takeIf { it.isDirectory }?.renameTo("PhotoS")
        }
    }
    private fun folder(root: DocumentFile, destination: String, album: String): DocumentFile = synchronized(folders) { folders.getOrPut("$destination|$album") {
        val base = folders.getOrPut("$destination|") {
            (root.findFile("PhotoS") ?: root.findFile("DualVault")?.takeIf { it.isDirectory && it.renameTo("PhotoS") } ?: root.createDirectory("PhotoS") ?: error("Cannot create backup folder")).also { require(it.isDirectory) { "A file named PhotoS blocks the backup folder" } }
        }
        val existing = base.findFile(album)
        if (existing != null) { require(existing.isDirectory) { "A file named $album blocks the backup folder" }; existing }
        else base.createDirectory(album) ?: error("Cannot create folder $album on the drive")
    } }
    /** [expected] is the source hash computed moments earlier; the copy re-hashes the source stream and must match it. */
    private suspend fun backup(entry: JSONObject, destination: String, journal: Journal, expected: String): Pair<String, String> {
        val source = MediaStore.setRequireOriginal(Uri.parse(entry.getString("uri")))
        val key = "backup|$destination|${entry.getString("uri")}|$expected"
        if (existing(journal, key, expected, false)) return expected to requireNotNull(journal.get(key)).uri
        val root = DocumentFile.fromTreeUri(this, Uri.parse(destination)) ?: error("Drive unavailable")
        require(root.exists() && root.canWrite()) { "Reconnect a writable drive and retry." }
        val directory = folder(root, destination, Integrity.albumFolder(entry.optString("folder")))
        val originalName = Integrity.safeName(entry.getString("name"))
        val partial = directory.createFile(entry.getString("mime"), ".photos-part-${UUID.randomUUID()}.part") ?: error("Cannot create backup file")
        journal.put(key, partial.uri.toString(), expected, false)
        val copied = copy(source, partial.uri)
        check(copied == expected && hash(partial.uri) == expected) { "Verification failed. The source changed or the drive could not store the complete file." }
        // Keep the original name. Existing files are never replaced: if the name is taken, fall back to a content-prefixed name.
        check(partial.renameTo(originalName) || partial.renameTo("${expected.take(12)}_$originalName")) { "File verified, but renaming failed. Retry with a writable drive." }
        journal.put(key, partial.uri.toString(), expected, true)
        return expected to partial.uri.toString()
    }
    private suspend fun restore(entry: JSONObject, journal: Journal) {
        val source = Uri.parse(entry.getString("uri"))
        val expected = hash(source)
        val key = "restore|$source|$expected"
        if (existing(journal, key, expected, true)) return
        val video = entry.getString("mime").startsWith("video/")
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, Integrity.safeName(entry.getString("name")))
            put(MediaStore.MediaColumns.MIME_TYPE, entry.getString("mime"))
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/PhotoS Restored/" else "Pictures/PhotoS Restored/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = contentResolver.insert(collection, values) ?: error("Cannot create restored file")
        journal.put(key, target.toString(), expected, false)
        check(copy(source, target) == expected && hash(target) == expected) { "Restore verification failed" }
        check(contentResolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        journal.put(key, target.toString(), expected, true)
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        status.value = TransferStatus(message = "Android paused this long transfer. Open the app and run it again.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        if (status.value.busy) status.value = TransferStatus(message = "Transfer interrupted. Run again to resume verified copies.")
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
