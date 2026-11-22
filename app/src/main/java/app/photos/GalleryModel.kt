package app.photos

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.time.LocalDate

data class DayGroup(val date: LocalDate, val items: List<MediaEntry>)

/** Drive status counts for the phone library against the selected backup drive. */
data class BackupSummary(val total: Int = 0, val backedUp: Int = 0, val changed: Int = 0, val missing: Int = 0, val unchecked: Int = 0)

enum class Backed { VERIFIED, CHANGED, MISSING, UNCHECKED }

fun SyncRecord?.backed(item: MediaEntry): Backed = when (this?.label(item.size, item.modified)) {
    "Backed up ✓" -> Backed.VERIFIED
    "Changed" -> Backed.CHANGED
    "Not backed up" -> Backed.MISSING
    else -> Backed.UNCHECKED
}

class GalleryModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("library", 0)
    val repo = MediaRepository(app)
    var drives by mutableStateOf(repo.drives()); private set
    var connected by mutableStateOf<Map<String, Boolean>>(emptyMap()); private set
    var source by mutableStateOf(""); private set
    var destination by mutableStateOf(prefs.getString("destination", "") ?: ""); private set
    var media by mutableStateOf<List<MediaEntry>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf("")
    var access by mutableStateOf(repo.accessLabel()); private set
    var folder by mutableStateOf("All folders")
    var kind by mutableStateOf("All media")
    var search by mutableStateOf("")
    var selected by mutableStateOf(setOf<String>())
    val collections = CollectionsStore(app)
    var favorites by mutableStateOf(collections.favorites()); private set
    var albums by mutableStateOf(collections.albums()); private set
    var collection by mutableStateOf("")
    var statuses by mutableStateOf<Map<String, SyncRecord>>(emptyMap()); private set
    var driveOnline by mutableStateOf(false); private set
    var deletionReview by mutableStateOf(DeletionReview(emptyList(), "Choose a drive to review.")); private set
    var reviewing by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var columns by mutableIntStateOf(prefs.getInt("columns", 4).coerceIn(MIN_COLUMNS, MAX_COLUMNS)); private set

    // Derived state is recomputed only when an input changes, not on every recomposition or scroll frame.
    private val members by derivedStateOf {
        when {
            collection.isEmpty() -> null
            collection == "favorites" -> favorites
            else -> albums.find { it.id == collection }?.members ?: emptySet()
        }
    }
    /** A date typed into search ("may 2023", "12/05/2023", "yesterday"…), or null when the text isn't a date. */
    val searchDate: DateQuery? by derivedStateOf { DateQuery.parse(search) }
    val shown: List<MediaEntry> by derivedStateOf {
        val set = members
        val query = search.trim()
        val date = searchDate
        val videos = kind == "Videos"
        media.filter {
            (folder == "All folders" || it.folder == folder) &&
                (kind == "All media" || videos == it.video) &&
                // Name or date: "2023" finds photos taken in 2023 as well as names containing 2023.
                (query.isEmpty() || it.name.contains(query, true) || it.folder.trimEnd('/').substringAfterLast('/').contains(query, true) || (date != null && date.matches(LocalDate.ofEpochDay(it.day)))) &&
                (set == null || it.uri in set)
        }
    }
    val groups: List<DayGroup> by derivedStateOf {
        val out = ArrayList<DayGroup>()
        var day = Long.MIN_VALUE
        var bucket = ArrayList<MediaEntry>()
        for (item in shown) {
            if (item.day != day) {
                if (bucket.isNotEmpty()) out += DayGroup(LocalDate.ofEpochDay(day), bucket)
                day = item.day; bucket = ArrayList()
            }
            bucket += item
        }
        if (bucket.isNotEmpty()) out += DayGroup(LocalDate.ofEpochDay(day), bucket)
        out
    }
    val groupStarts: List<Int> by derivedStateOf { TimelineIndex.groupStarts(groups.map { it.items.size }) }
    val folders: List<String> by derivedStateOf { listOf("All folders") + media.asSequence().map { it.folder }.distinct().sorted().toList() }
    val folderGroups: List<Pair<String, List<MediaEntry>>> by derivedStateOf { media.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() } }
    val summary: BackupSummary by derivedStateOf {
        if (source.isNotEmpty() || destination.isEmpty()) BackupSummary()
        else {
            var ok = 0; var changed = 0; var missing = 0; var unchecked = 0
            for (item in media) when (statuses[item.uri].backed(item)) {
                Backed.VERIFIED -> ok++
                Backed.CHANGED -> changed++
                Backed.MISSING -> missing++
                Backed.UNCHECKED -> unchecked++
            }
            BackupSummary(media.size, ok, changed, missing, unchecked)
        }
    }
    val selectedEntries: List<MediaEntry> get() = media.filter { it.uri in selected }
    fun driveName(uri: String = destination) = drives.find { it.uri == uri }?.name

    private val vaultPrefs = app.getSharedPreferences("vault_ui", 0)
    /** When true, nothing in the interface mentions the vault; it opens only via the hidden long-press gestures. */
    var vaultHidden by mutableStateOf(vaultPrefs.getBoolean("hidden", false)); private set
    fun vaultHidden(value: Boolean) { vaultHidden = value; vaultPrefs.edit().putBoolean("hidden", value).apply() }
    var parallel by mutableIntStateOf(prefs.getInt("parallel", TransferService.DEFAULT_PARALLEL).coerceIn(1, TransferService.MAX_PARALLEL)); private set
    fun parallel(value: Int) {
        val next = value.coerceIn(1, TransferService.MAX_PARALLEL)
        if (next != parallel) { parallel = next; prefs.edit().putInt("parallel", next).apply() }
    }
    fun zoomColumns(delta: Int) {
        val next = (columns + delta).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        if (next != columns) { columns = next; prefs.edit().putInt("columns", next).apply() }
    }

    fun keepBackup(targets: List<String>, kept: Boolean) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { Journal(getApplication()).use { j -> targets.forEach { j.keep(it, kept) } } }; reloadStatus(); loadReview() }
            catch (e: Exception) { error = e.message ?: "Could not save your choice" }
        }
    }

    private var statusJob: Job? = null
    private var statusDrive = ""
    /** Cheap: journal read and one drive reachability check. Existing badges stay visible until the new ones arrive. */
    fun reloadStatus() {
        statusJob?.cancel()
        val drive = destination
        if (drive != statusDrive) { statuses = emptyMap(); driveOnline = false; statusDrive = drive }
        if (drive.isEmpty()) return
        statusJob = viewModelScope.launch {
            val (records, online) = withContext(Dispatchers.IO) {
                val records = Journal(getApplication()).use { it.statuses(drive) }
                records to reachable(drive)
            }
            if (destination == drive) { statuses = records; driveOnline = online }
        }
    }

    private var reviewJob: Job? = null
    /** Expensive (queries the phone for every recorded backup), so it only runs when the review is shown. */
    fun loadReview() {
        reviewJob?.cancel()
        val drive = destination
        if (drive.isEmpty()) { deletionReview = DeletionReview(emptyList(), "Choose a backup drive first."); reviewing = false; return }
        reviewing = true
        reviewJob = viewModelScope.launch {
            val review = withContext(Dispatchers.IO) {
                try { DeletionManager(getApplication()).review(drive) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { DeletionReview(emptyList(), "Review unavailable: ${e.message}") }
            }
            if (destination == drive) { deletionReview = review; reviewing = false }
        }
    }

    private fun reachable(uri: String) = try { DocumentFile.fromTreeUri(getApplication(), Uri.parse(uri))?.let { it.exists() && it.canRead() } ?: false } catch (_: Exception) { false }

    fun refreshDrives() {
        val list = drives
        viewModelScope.launch { connected = withContext(Dispatchers.IO) { list.associate { it.uri to reachable(it.uri) } } }
    }

    fun favorite(uri: String) { collections.favorite(uri); favorites = collections.favorites() }
    fun saveAlbum(name: String, id: String? = null) { collections.save(name, selected, id); albums = collections.albums() }
    fun openCollection(id: String) { collection = id; folder = "All folders"; kind = "All media"; search = ""; selected = emptySet() }

    fun destination(value: String) {
        destination = value
        prefs.edit().putString("destination", value).apply()
        reloadStatus()
        deletionReview = DeletionReview(emptyList(), "Checking recorded backups…")
    }
    fun source(value: String) {
        if (value != source) { source = value; media = emptyList() }
        collection = ""; folder = "All folders"; selected = emptySet()
        refresh(force = true)
    }
    /** A drive that was just added, waiting for you to confirm or change its name. */
    var naming by mutableStateOf<Drive?>(null)
    fun renameDrive(uri: String, name: String) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repo.rename(uri, name) }; drives = repo.drives() }
            catch (e: Exception) { error = e.message ?: "Could not rename the drive" }
        }
    }
    fun remember(uri: Uri) {
        viewModelScope.launch {
            try {
                val drive = withContext(Dispatchers.IO) { repo.remember(uri) }
                drives = repo.drives()
                naming = drive
                if (destination.isEmpty()) destination(drive.uri)
                source(drive.uri)
            } catch (e: Exception) { error = e.message ?: "Could not access folder" }
        }
    }

    private var scan: Job? = null
    private var scanSource: String? = null
    private var scannedAt = 0L
    /** Returning to the app rescans the phone (fast), but only rescans a USB folder if it is stale. */
    fun onResume() {
        startTicker()
        if (source.isEmpty() || SystemClock.elapsedRealtime() - scannedAt > 60_000) refresh() else { access = repo.accessLabel(); reloadStatus(); refreshDrives() }
    }
    fun refresh(force: Boolean = false) {
        access = repo.accessLabel()
        reloadStatus()
        refreshDrives()
        val src = source
        if (!force && scan?.isActive == true && scanSource == src) return
        scan?.cancel()
        scanSource = src
        scan = viewModelScope.launch {
            val self = coroutineContext[Job]
            loading = true; error = ""
            try {
                val items = if (src.isEmpty()) withContext(Dispatchers.IO) { repo.phone() } else driveItems(src)
                if (source == src) {
                    media = items
                    scannedAt = SystemClock.elapsedRealtime()
                    // Keep the user's selection across refreshes, minus items that disappeared.
                    if (selected.isNotEmpty()) { val uris = items.mapTo(HashSet()) { it.uri }; selected = selected.filterTo(HashSet()) { it in uris } }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load media" }
            finally { if (scan === self) loading = false }
        }
    }
    private val driveIndex = DriveIndex(app)
    /**
     * A drive opens instantly from its saved listing; the fresh scan then replaces it.
     * On a first visit there is nothing saved, so photos appear in batches while the scan runs.
     */
    /** Fast index coverage per drive: photos indexed with a thumbnail, photos in the PhotoS folder, and whether the drive can be written. */
    data class IndexStatus(val indexed: Int, val total: Int, val writable: Boolean) { val missing get() = (total - indexed).coerceAtLeast(0) }
    var indexStatus by mutableStateOf<Map<String, IndexStatus>>(emptyMap()); private set
    var autoIndex by mutableStateOf(prefs.getBoolean("autoIndex", true)); private set
    fun autoIndex(on: Boolean) { autoIndex = on; prefs.edit().putBoolean("autoIndex", on).apply(); if (on) { lastAutoMissing.clear(); maybeAutoIndex(source) } }
    // After an automatic run, files it could not read stay "missing"; only start again when more are missing than that.
    private val lastAutoMissing = HashMap<String, Int>()
    private var autoRunning: String? = null
    private fun maybeAutoIndex(drive: String) {
        val status = indexStatus[drive] ?: return
        if (autoRunning == drive && !TransferService.status.value.busy) { lastAutoMissing[drive] = status.missing; autoRunning = null }
        if (!autoIndex || drive.isEmpty() || !status.writable || status.missing == 0 || TransferService.status.value.busy) return
        if (status.missing <= (lastAutoMissing[drive] ?: -1)) return
        runCatching { TransferService.index(getApplication(), drive, background = true) }.onSuccess { autoRunning = drive; lastAutoMissing[drive] = status.missing }
    }
    /** Re-reads the drive being browsed every 10 minutes while the app is open, so new photos appear and get indexed. */
    private var ticker: Job? = null
    fun onPause() { ticker?.cancel(); ticker = null }
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch { while (true) { delay(10 * 60 * 1000L); if (source.isNotEmpty() && !TransferService.status.value.busy) refresh(force = true) } }
    }

    private suspend fun driveItems(src: String): List<MediaEntry> {
        // The drive's own index (written by backups or the Windows app): capture dates and packed thumbnails.
        val catalog = withContext(Dispatchers.IO) { DriveCatalog.open(getApplication(), Uri.parse(src), create = false) }
        DriveCatalogs.register(src, catalog)
        fun withDates(list: List<MediaEntry>): List<MediaEntry> = if (catalog == null || catalog.size == 0) list else
            list.map { e -> catalog.entryFor(e.uri)?.takeIf { it.size == e.size && it.date > 0 && it.date != e.date }?.let { e.copy(date = it.date) } ?: e }.sortedByDescending { it.date }
        var showedSaved = false
        if (media.isEmpty()) {
            // This phone's last listing of the drive, else the drive's catalog (first visit on this phone).
            val saved = withContext(Dispatchers.IO) { driveIndex.load(src)?.let(::withDates)?.takeIf { it.isNotEmpty() } ?: catalog?.mediaEntries() }
            if (!saved.isNullOrEmpty() && source == src) { media = saved; showedSaved = true }
        }
        val fresh = withContext(Dispatchers.IO) {
            withDates(repo.drive(src, onProgress = if (showedSaved) null else { partial -> withContext(Dispatchers.Main) { if (source == src) media = partial } }))
        }
        withContext(Dispatchers.IO) { driveIndex.save(src, fresh) }
        val (indexed, total) = withContext(Dispatchers.IO) { catalog?.coverage(fresh) ?: (0 to fresh.count { it.folder.split('/').contains("PhotoS") }) }
        indexStatus = indexStatus + (src to IndexStatus(indexed, total, catalog?.writable ?: true))
        maybeAutoIndex(src)
        return fresh
    }

    fun toggle(item: MediaEntry) { selected = if (item.uri in selected) selected - item.uri else selected + item.uri }

    companion object { const val MIN_COLUMNS = 2; const val MAX_COLUMNS = 6 }
}
