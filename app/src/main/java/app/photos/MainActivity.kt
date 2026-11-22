package app.photos

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.clickable
import android.Manifest
import android.app.Activity
import android.content.*
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { PhotoSTheme { VaultApp() } }
    }
}

private val mediaPermissions = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION)
private val tabs = listOf("Photos", "Collections", "Backup", "Drives")
private val tabIcons = listOf(R.drawable.ic_photos, R.drawable.ic_folder, R.drawable.ic_backup, R.drawable.ic_drive)

@Composable
private fun VaultApp(model: GalleryModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val transfer by TransferService.status.collectAsStateWithLifecycle()
    val loader = remember { Images.loader(context) }
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveableInt(0)
    var viewing by remember { mutableStateOf<Pair<List<MediaEntry>, Int>?>(null) }
    var confirm by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val busy = transfer.busy || preparing
    var searching by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var albumDialog by remember { mutableStateOf(false) }
    var phoneDelete by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var permanentPhone by remember { mutableStateOf(false) }
    var driveDelete by remember { mutableStateOf<List<DeleteTarget>?>(null) }
    var reviewOpen by remember { mutableStateOf(false) }
    var vaultOpen by remember { mutableStateOf(false) }
    var vaultImport by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var recentOpen by remember { mutableStateOf(false) }
    var duplicatesOf by remember { mutableStateOf<Drive?>(null) }
    // The setup screen is only opened from the menu; first launch uses Android's own permission prompts.
    var setupOpen by remember { mutableStateOf(false) }
    // When Photos shows an album, folder or drive opened from another tab, Back returns to that tab.
    var origin by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Int?>(null) }
    val photoGrid = rememberLazyGridState()
    val filtered = model.collection.isNotEmpty() || model.kind != "All media" || model.folder != "All folders"
    val viewTitle: String? = when {
        tab != 0 -> null
        model.collection == "favorites" -> "Favorites"
        model.collection.isNotEmpty() -> model.albums.find { it.id == model.collection }?.name ?: "Album"
        origin == 1 && model.folder != "All folders" -> model.folder.trimEnd('/').substringAfterLast('/').ifEmpty { model.folder }
        origin == 1 && model.kind == "Videos" -> "Videos"
        origin == 3 && model.source.isNotEmpty() -> model.driveName(model.source) ?: "Drive"
        else -> null
    }
    val selectionMode = tab == 0 && (selecting || model.selected.isNotEmpty())
    val phoneDeleteResult = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        DeletionManager(context).finishPhoneRequest(result.resultCode == Activity.RESULT_OK)
        model.message = if (result.resultCode == Activity.RESULT_OK) "Deleted from phone. Drive copies were kept — review them when the drive is connected." else "Phone deletion cancelled. Drive copies were kept."
        selecting = false; model.selected = emptySet()
        model.refresh(force = true)
    }
    fun askDelete(items: List<MediaEntry>) {
        if (busy || items.isEmpty()) return
        val drive = model.source
        viewing = null
        if (drive.isEmpty()) { permanentPhone = false; phoneDelete = items }
        else {
            preparing = true
            scope.launch {
                try { driveDelete = withContext(Dispatchers.IO) { DeletionManager(context).prepare(drive, items) } }
                catch (e: Exception) { model.error = e.message ?: "Cannot prepare deletion" }
                finally { preparing = false }
            }
        }
    }
    fun checkDrive(deep: Boolean = false) {
        if (busy || model.destination.isEmpty()) return
        preparing = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { TransferService.start(context, model.repo.phone(), model.destination, checkOnly = true, deep = deep) }
                tab = 2
            } catch (e: Exception) { model.error = e.message ?: "Could not check drive" }
            finally { preparing = false }
        }
    }
    fun backUpMissing() {
        scope.launch {
            try { confirm = withContext(Dispatchers.IO) { model.repo.phone() }; model.source("") }
            catch (e: Exception) { model.error = e.message ?: "Could not read phone photos" }
        }
    }
    fun exitSelection() { selecting = false; model.selected = emptySet() }
    fun clearView() { model.openCollection(""); model.kind = "All media"; if (origin == 3) model.source("") }
    fun openFrom(from: Int) { origin = from; tab = 0 }
    /** Leaves an opened album, folder or drive and returns to the tab it was opened from. */
    fun closeView() { val back = origin; clearView(); origin = null; if (back != null) tab = back }
    fun selectTab(index: Int) {
        if (index == tab) {
            // Tapping the current tab: leave an opened view, otherwise scroll back to the top.
            if (index == 0 && (origin != null || filtered)) { clearView(); origin = null }
            else if (index == 0) scope.launch { photoGrid.animateScrollToItem(0) }
            return
        }
        if (tab == 0 && origin != null) { clearView(); origin = null }
        exitSelection(); searching = false; model.search = ""
        tab = index
    }
    // A new view starts at the top; returning to the same view keeps its place.
    // Switching back to "This phone" from the source menu ends a drive view opened from Drives.
    LaunchedEffect(model.source) { if (origin == 3 && model.source.isEmpty()) origin = null }
    LaunchedEffect(model.collection, model.kind, model.folder, model.source) { if (photoGrid.firstVisibleItemIndex > 0) photoGrid.scrollToItem(0) }
    LaunchedEffect(model.source) { selecting = false }
    LaunchedEffect(model.message) {
        if (model.message.isNotEmpty()) {
            val text = model.message
            snackbar.showSnackbar(text, "OK", withDismissAction = false, duration = SnackbarDuration.Long)
            if (model.message == text) model.message = ""
        }
    }
    LaunchedEffect(tab, model.destination) { if (tab == 2) model.loadReview() }

    var openSettingsIfDenied by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val activity = context as? Activity
        // Android stops showing the prompt after repeated denials; send the user to app settings instead of doing nothing.
        val blocked = result.any { (permission, granted) -> !granted && permission != Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED && activity?.shouldShowRequestPermissionRationale(permission) == false }
        if (openSettingsIfDenied && blocked && !model.repo.has(Manifest.permission.READ_MEDIA_IMAGES)) {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        openSettingsIfDenied = false
        model.refresh(force = true)
    }
    fun requestAccess() { openSettingsIfDenied = true; permissions.launch(mediaPermissions) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("setup", 0)
        val noAccess = !model.repo.has(Manifest.permission.READ_MEDIA_IMAGES) && !model.repo.has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        // Ask once on first launch; afterwards only when there is no photo access at all (respects "selected photos" choices).
        if (!prefs.getBoolean("asked", false) || noAccess) {
            prefs.edit().putBoolean("asked", true).apply()
            permissions.launch(mediaPermissions + Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(model::remember) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.onResume(); if (event == Lifecycle.Event.ON_PAUSE) model.onPause() }
        lifecycle.addObserver(observer)
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { model.refresh(force = true) } }
        context.registerReceiver(receiver, IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }, Context.RECEIVER_EXPORTED)
        // Storage volumes mount a few seconds after the USB device attaches; this is when drive folders become readable.
        val volumes = IntentFilter().apply { addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED); addAction(Intent.ACTION_MEDIA_EJECT); addDataScheme("file") }
        context.registerReceiver(receiver, volumes, Context.RECEIVER_EXPORTED)
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(receiver) }
    }
    var wasBusy by remember { mutableStateOf(transfer.busy) }
    LaunchedEffect(transfer.busy) { if (wasBusy && !transfer.busy) model.refresh(force = true); wasBusy = transfer.busy }
    // One Back handler, most specific first: selection, other tab, search, opened view, filters, drive library.
    BackHandler(selectionMode || tab != 0 || searching || origin != null || filtered || model.source.isNotEmpty()) {
        when {
            selectionMode -> exitSelection()
            tab != 0 -> tab = 0
            searching -> { searching = false; model.search = "" }
            origin != null -> closeView()
            filtered -> clearView()
            else -> model.source("")
        }
    }

    Scaffold(containerColor = Palette.Black, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if (selectionMode) SelectionBar(model, busy, onTransfer = { confirm = model.selectedEntries }, onVault = { vaultImport = model.selectedEntries; vaultOpen = true }, onAlbum = { albumDialog = true }, onDelete = { askDelete(model.selectedEntries) }, onChooseDrive = { tab = 2; exitSelection() })
        else NavigationBar(containerColor = Palette.Bar, tonalElevation = 0.dp) {
            tabs.forEachIndexed { index, title ->
                NavigationBarItem(selected = tab == index, onClick = { selectTab(index) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Palette.CardHigh, selectedIconColor = Color.White, selectedTextColor = Color.White, unselectedIconColor = Palette.Faint, unselectedTextColor = Palette.Faint),
                    icon = {
                        BadgedBox(badge = { if (index == 2 && transfer.busy) Badge(containerColor = Palette.Verified) }) { Glyph(tabIcons[index], title) }
                    }, label = { Text(title) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (selectionMode) SelectionHeader(model.selected.size, onClose = ::exitSelection, onAll = { model.selected = if (model.selected.size == model.shown.size) emptySet() else model.shown.mapTo(HashSet()) { it.uri } }, allSelected = model.selected.size == model.shown.size && model.shown.isNotEmpty())
            else Header(tab, viewTitle, ::closeView, searching, model.source.isEmpty(), model.vaultHidden,
                onSearch = { searching = !searching; if (!searching) model.search = "" },
                onSelect = { selecting = true }, onSetup = { setupOpen = true }, onVault = { vaultImport = emptyList(); vaultOpen = true },
                onRecent = { recentOpen = true }, onRefresh = { model.refresh(force = true) }, onAddDrive = { picker.launch(null) },
                onAccess = ::requestAccess)
            if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White, trackColor = Palette.CardHigh)
            if (model.error.isNotEmpty()) ErrorBanner(model.error) { model.error = "" }
            when (tab) {
                0 -> PhotosTab(model, loader, photoGrid, showChip = viewTitle == null, selectionMode, searching, busy,
                    onOpen = { item -> val list = model.shown; viewing = list to list.indexOf(item).coerceAtLeast(0) },
                    onLongPress = { item -> selecting = true; model.toggle(item) },
                    onCheck = { checkDrive() }, onAccess = ::requestAccess, onAddDrive = { picker.launch(null) })
                1 -> CollectionsTab(model, loader, onOpen = { openFrom(1) }, onVault = { vaultImport = emptyList(); vaultOpen = true }, onRecent = { recentOpen = true })
                2 -> BackupTab(model, transfer, busy, onAddDrive = { picker.launch(null) }, onCheck = { checkDrive() }, onDeep = { checkDrive(deep = true) }, onMissing = ::backUpMissing,
                    onReview = { model.reloadStatus(); model.loadReview(); reviewOpen = true },
                    onNotifications = { permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
                    onStop = { context.startService(Intent(context, TransferService::class.java).setAction("cancel")) },
                    onPause = { context.startService(Intent(context, TransferService::class.java).setAction(if (transfer.paused) "resume" else "pause")) })
                else -> DrivesTab(model, busy, onBrowse = { model.source(it); openFrom(3) }, onReview = { model.destination(it); model.loadReview(); reviewOpen = true }, onAddDrive = { picker.launch(null) },
                    onIndex = { uri, rebuild -> try { TransferService.index(context, uri, rebuild = rebuild); selectTab(2) } catch (e: Exception) { model.error = e.message ?: "Could not start indexing" } },
                    onDuplicates = { duplicatesOf = it })
            }
        }
    }
    model.naming?.let { drive ->
        DriveNameDialog("Name this drive", drive.name, onDismiss = { model.naming = null }) { model.renameDrive(drive.uri, it); model.naming = null }
    }
    if (vaultOpen) PrivateVault(vaultImport, model.vaultHidden, model::vaultHidden, onChanged = { model.refresh(force = true) }, onClose = { vaultOpen = false; vaultImport = emptyList(); exitSelection(); model.refresh(force = true) })
    duplicatesOf?.let { drive ->
        DuplicatesScreen(drive, model, loader, busy,
            onIndex = { try { TransferService.index(context, drive.uri); duplicatesOf = null; selectTab(2) } catch (e: Exception) { model.error = e.message ?: "Could not start indexing" } },
            onRemove = { sets -> try { TransferService.removeDuplicates(context, drive.uri, sets); duplicatesOf = null; selectTab(2) } catch (e: Exception) { model.error = e.message ?: "Could not remove duplicates" } },
            onClose = { duplicatesOf = null })
    }
    if (recentOpen) RecentlyDeleted(loader, onChanged = { model.refresh(force = true) }, onClose = { recentOpen = false })
    if (setupOpen) PermissionSetup(
        driveCount = model.drives.size,
        onAddDrive = { picker.launch(null) },
        onClose = {
            context.getSharedPreferences("setup", 0).edit().putBoolean("completed", true).apply()
            setupOpen = false
            model.refresh(force = true)
        }
    )
    if (reviewOpen) BackupDeletionReview(
        model.deletionReview, model.reviewing, busy,
        model.driveName() ?: "Selected drive",
        loader, onClose = { reviewOpen = false }, onRefresh = { model.reloadStatus(); model.loadReview() },
        onKeep = { items, kept -> model.keepBackup(items.map { it.target.uri }, kept) },
        onDelete = { items -> if (items.isNotEmpty()) driveDelete = items.map { it.target } }
    )
    phoneDelete?.let { entries ->
        AlertDialog(onDismissRequest = { phoneDelete = null }, title = { Text("Delete ${entries.size} from phone?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${entries.take(3).joinToString { it.name }}${if (entries.size > 3) "…" else ""}", color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Drive backups will be kept. You can review and delete those separately when the drive is connected.")
                Row(Modifier.clip(MaterialTheme.shapes.small).clickable { permanentPhone = !permanentPhone }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(permanentPhone, { permanentPhone = it })
                    Text("Delete permanently instead of moving to Trash")
                }
                if (entries.size > 500) Text("Select up to 500 files per deletion request.", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton(onClick = {
                try {
                    val uris = entries.map { Uri.parse(it.uri) }
                    val request = if (permanentPhone) MediaStore.createDeleteRequest(context.contentResolver, uris) else MediaStore.createTrashRequest(context.contentResolver, uris, true)
                    DeletionManager(context).beginPhoneRequest(entries.map { it.uri })
                    phoneDeleteResult.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    phoneDelete = null
                } catch (e: Exception) { DeletionManager(context).finishPhoneRequest(false); model.error = e.message ?: "Could not request deletion"; phoneDelete = null }
            }, enabled = !busy && entries.size <= 500) { Text(if (permanentPhone) "Delete permanently" else "Move to Trash", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { phoneDelete = null }) { Text("Cancel") } })
    }
    driveDelete?.let { targets ->
        AlertDialog(onDismissRequest = { driveDelete = null }, title = { Text("Permanently delete ${targets.size} drive file${if (targets.size == 1) "" else "s"}?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(targets.take(5).joinToString("\n") { it.name } + if (targets.size > 5) "\n…" else "", color = Palette.Muted)
                Text("From: ${model.driveName(targets.first().drive) ?: "selected drive folder"}")
                Text("This cannot be undone by PhotoS. A file may be your only remaining copy or be shared by duplicate phone photos. Phone files will not be deleted. Files whose content or phone status changed will be kept.")
            }
        }, confirmButton = {
            TextButton(onClick = {
                driveDelete = null; preparing = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { TransferService.delete(context, targets) }; reviewOpen = false; exitSelection(); tab = 2 }
                    catch (e: Exception) { model.error = e.message ?: "Could not delete" }
                    finally { preparing = false }
                }
            }, enabled = !busy) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { driveDelete = null }) { Text("Keep files") } })
    }
    confirm?.let { entries ->
        val restore = model.source.isNotEmpty()
        AlertDialog(onDismissRequest = { confirm = null },
            icon = { Glyph(if (restore) R.drawable.ic_restore else R.drawable.ic_backup, null) },
            title = { Text(if (restore) "Restore ${entries.size} to phone?" else "Back up ${entries.size} to USB?") }, text = {
            Text(if (restore) "Copies go to Pictures or Movies / PhotoS Restored. Files on the drive remain intact." else "Copies go to ${model.driveName() ?: "the selected drive"} / PhotoS, sorted into folders that match your phone (Camera, Screenshots, WhatsApp Images…). The drive is checked first; only missing content is copied, and every copy is verified. Phone originals remain intact.")
        }, confirmButton = {
            Button(onClick = {
                val destination = if (restore) null else model.destination
                confirm = null; preparing = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { TransferService.start(context, entries, destination) }
                        exitSelection(); tab = 2
                    } catch (e: Exception) { model.error = e.message ?: "Could not start transfer" }
                    finally { preparing = false }
                }
            }, enabled = !busy) { Text("Start") }
        }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
    if (albumDialog) AlbumDialog(model.albums, onPick = { model.saveAlbum(it.name, it.id); albumDialog = false; model.message = "Added to ${it.name}" }, onCreate = { model.saveAlbum(it); albumDialog = false; model.message = "Album “$it” created" }, onDismiss = { albumDialog = false })
    viewing?.let { (list, start) ->
        Viewer(list, start, loader, model.favorites, model::favorite,
            if (model.source.isEmpty() && model.destination.isNotEmpty()) model.statuses else null, model.driveOnline, model.driveName(),
            onDelete = { askDelete(listOf(it)) }, deleteEnabled = !busy, onClose = { viewing = null },
            // Hidden gesture: long-press the heart to move the open photo into the private vault.
            onVault = if (model.source.isEmpty() && !busy) ({ item -> viewing = null; vaultImport = listOf(item); vaultOpen = true }) else null)
    }
}

@Composable
private fun rememberSaveableInt(initial: Int): MutableIntState = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(initial) }

@Composable
private fun Header(tab: Int, viewTitle: String?, onBack: () -> Unit, searching: Boolean, phone: Boolean, vaultHidden: Boolean, onSearch: () -> Unit, onSelect: () -> Unit, onSetup: () -> Unit, onVault: () -> Unit, onRecent: () -> Unit, onRefresh: () -> Unit, onAddDrive: () -> Unit, onAccess: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = if (viewTitle != null) 4.dp else 20.dp, end = 6.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Long-pressing the Photos title is the hidden way into the vault.
        val haptics = LocalHapticFeedback.current
        if (viewTitle != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(viewTitle, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Text(listOf("Photos", "Collections", "Backup", "Drives")[tab], Modifier.weight(1f).then(if (tab == 0) Modifier.combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onVault() }) else Modifier), style = MaterialTheme.typography.headlineLarge)
        if (tab == 0) {
            IconButton(onClick = onSearch) { if (searching) Icon(Icons.Default.Close, "Close search") else Glyph(R.drawable.ic_search, "Search photos") }
            Box {
                IconButton(onClick = { menu = true }) { Glyph(R.drawable.ic_more, "Gallery options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Select photos") }, onClick = { onSelect(); menu = false })
                    DropdownMenuItem(text = { Text("Refresh") }, onClick = { onRefresh(); menu = false })
                    HorizontalDivider(color = Palette.Line)
                    if (!vaultHidden) DropdownMenuItem(text = { Text("Private vault") }, leadingIcon = { Glyph(R.drawable.ic_lock, null, Modifier.size(18.dp)) }, onClick = { onVault(); menu = false })
                    DropdownMenuItem(text = { Text("Recently deleted") }, leadingIcon = { Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)) }, onClick = { onRecent(); menu = false })
                    DropdownMenuItem(text = { Text("Add drive") }, leadingIcon = { Glyph(R.drawable.ic_drive, null, Modifier.size(18.dp)) }, onClick = { onAddDrive(); menu = false })
                    HorizontalDivider(color = Palette.Line)
                    if (phone) DropdownMenuItem(text = { Text("Manage photo access") }, onClick = { onAccess(); menu = false })
                    DropdownMenuItem(text = { Text("Permissions & setup") }, onClick = { onSetup(); menu = false })
                }
            }
        } else if (tab != 1) IconButton(onClick = onAddDrive) { Glyph(R.drawable.ic_add, "Add drive folder") }
    }
}

@Composable
private fun SelectionHeader(count: Int, allSelected: Boolean, onClose: () -> Unit, onAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cancel selection") }
        Text(if (count == 0) "Select items" else "$count selected", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onAll) { Text(if (allSelected) "Clear" else "Select all") }
    }
}

@Composable
private fun SelectionBar(model: GalleryModel, busy: Boolean, onTransfer: () -> Unit, onVault: () -> Unit, onAlbum: () -> Unit, onDelete: () -> Unit, onChooseDrive: () -> Unit) {
    val phone = model.source.isEmpty()
    val any = model.selected.isNotEmpty()
    Surface(color = Palette.Bar) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
            if (phone && model.destination.isEmpty() && any) TextButton(onClick = onChooseDrive, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Choose a backup drive to back up") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                // Hidden gesture: long-press Back up moves the selection into the private vault.
                BarAction(if (phone) "Back up" else "Restore", any && !busy && (!phone || model.destination.isNotEmpty()), onTransfer,
                    onLongClick = if (phone && any && !busy) onVault else null) { Glyph(if (phone) R.drawable.ic_backup else R.drawable.ic_restore, null) }
                if (phone && !model.vaultHidden) BarAction("Vault", any && !busy, onVault) { Glyph(R.drawable.ic_lock, null) }
                BarAction("Album", any, onAlbum) { Icon(Icons.Default.Add, null) }
                BarAction("Delete", any && !busy, onDelete, danger = true) { Icon(Icons.Outlined.Delete, null) }
            }
        }
    }
}

@Composable
private fun BarAction(label: String, enabled: Boolean, onClick: () -> Unit, danger: Boolean = false, onLongClick: (() -> Unit)? = null, icon: @Composable () -> Unit) {
    val tint = when { !enabled -> Palette.Faint.copy(alpha = .6f); danger -> Palette.Danger; else -> Color.White }
    val haptics = LocalHapticFeedback.current
    // Long-press still works when the tap action is unavailable (e.g. no backup drive chosen yet).
    Column(Modifier.clip(MaterialTheme.shapes.medium).combinedClickable(enabled = enabled || onLongClick != null, onClick = { if (enabled) onClick() },
        onLongClick = onLongClick?.let { action -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); action() } }).padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CompositionLocalProvider(LocalContentColor provides tint) { icon() }
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Surface(color = Color(0xFF2A1715), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = Palette.Danger, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss error", tint = Palette.Danger) }
        }
    }
}

@Composable
private fun PhotosTab(model: GalleryModel, loader: ImageLoader, gridState: LazyGridState, showChip: Boolean, selectionMode: Boolean, searching: Boolean, busy: Boolean, onOpen: (MediaEntry) -> Unit, onLongPress: (MediaEntry) -> Unit, onCheck: () -> Unit, onAccess: () -> Unit, onAddDrive: () -> Unit) {
    val shown = model.shown
    val phone = model.source.isEmpty()
    val tracking = phone && model.destination.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SourceChoice(listOf("" to "This phone") + model.drives.map { it.uri to it.name }, model.source, model::source) }
            Text("${"%,d".format(shown.size)} ${if (shown.size == 1) "item" else "items"}", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
        AnimatedVisibility(searching, enter = expandVertically(), exit = shrinkVertically()) {
            OutlinedTextField(model.search, { model.search = it }, placeholder = { Text("Search name, album or date") }, leadingIcon = { Glyph(R.drawable.ic_search, null, Modifier.size(20.dp)) }, shape = CircleShape, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Palette.Card, focusedContainerColor = Palette.Card, unfocusedBorderColor = Color.Transparent, focusedBorderColor = Palette.Line),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        }
        // What the search understood as a date, so "05/06/2023" is clearly 5 June (day first).
        model.searchDate?.takeIf { searching }?.let { date ->
            Text("Photos from ${date.label()}, and names containing “${model.search.trim()}”", Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showChip && model.collection.isNotEmpty()) InputChip(selected = true, onClick = { model.collection = "" },
                label = { Text(if (model.collection == "favorites") "Favorites" else model.albums.find { it.id == model.collection }?.name ?: "Album", maxLines = 1) },
                trailingIcon = { Icon(Icons.Default.Close, "Show all photos", Modifier.size(16.dp)) }, shape = CircleShape)
            listOf("All media" to "All", "Photos" to "Photos", "Videos" to "Videos").forEach { (value, title) ->
                FilterChip(selected = model.kind == value, onClick = { model.kind = value; model.selected = emptySet() }, label = { Text(title) }, shape = CircleShape)
            }
            FolderChoice(model.folders, model.folder) { model.folder = it; model.selected = emptySet() }
        }
        if (tracking) BackupStrip(model, busy, onCheck)
        if (phone && model.access != "Full photo and video access") {
            Surface(color = Palette.Card, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(model.access, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onAccess) { Text("Allow") }
                }
            }
        }
        Box(Modifier.height(2.dp).fillMaxWidth()) { if (model.loading) LinearProgressIndicator(Modifier.fillMaxSize(), color = Color.White, trackColor = Palette.Card) }
        when {
            model.loading && shown.isEmpty() -> SkeletonGrid(model.columns)
            shown.isEmpty() -> EmptyState(model, phone, onAccess, onAddDrive)
            else -> PhotoGrid(model, loader, gridState, selectionMode, tracking, onOpen, onLongPress)
        }
    }
}

@Composable
private fun BackupStrip(model: GalleryModel, busy: Boolean, onCheck: () -> Unit) {
    val s = model.summary
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(model.driveName() ?: "Backup drive", Palette.Text, dot = if (model.driveOnline) Palette.Verified else Palette.Faint)
        if (s.backedUp > 0) Pill("${"%,d".format(s.backedUp)} ${if (model.driveOnline) "backed up" else "last verified"}", dot = Palette.Verified)
        if (s.changed > 0) Pill("${"%,d".format(s.changed)} changed", dot = Palette.Warning)
        if (s.missing > 0) Pill("${"%,d".format(s.missing)} not backed up")
        if (s.unchecked > 0) Pill("${"%,d".format(s.unchecked)} not checked", Palette.Faint)
        TextButton(onClick = onCheck, enabled = !busy && model.driveOnline, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Check") }
    }
}

@Composable
private fun EmptyState(model: GalleryModel, phone: Boolean, onAccess: () -> Unit, onAddDrive: () -> Unit) {
    val filtered = model.search.isNotEmpty() || model.collection.isNotEmpty() || model.kind != "All media" || model.folder != "All folders"
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).background(Palette.Card, CircleShape), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_photos, null, Modifier.size(36.dp), Palette.Muted) }
        Spacer(Modifier.height(20.dp))
        Text(if (filtered) "Nothing matches" else if (phone) "Your library starts here" else "No photos in this folder", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(when {
            filtered -> "Try a different search, filter or folder."
            phone -> "Allow access to your photos, or connect a drive to explore your memories."
            else -> "Pick another drive folder, or check that the drive is connected."
        }, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (filtered) OutlinedButton(onClick = { model.openCollection(""); model.kind = "All media" }) { Text("Clear filters") }
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (phone && model.access != "Full photo and video access") Button(onClick = onAccess) { Text("Allow access") }
            OutlinedButton(onClick = onAddDrive) { Text("Add drive") }
        }
    }
}

@Composable
private fun PhotoGrid(model: GalleryModel, loader: ImageLoader, gridState: LazyGridState, selectionMode: Boolean, tracking: Boolean, onOpen: (MediaEntry) -> Unit, onLongPress: (MediaEntry) -> Unit) {
    val scope = rememberCoroutineScope()
    val groups = model.groups
    val groupStarts = model.groupStarts
    val currentDateIndex by remember(groupStarts) { derivedStateOf { groupStarts.indexOfLast { it <= gridState.firstVisibleItemIndex }.coerceAtLeast(0) } }
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    val selected = model.selected
    val favorites = model.favorites
    val statuses = model.statuses
    val online = model.driveOnline
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(model.columns), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().pinchColumns(model::zoomColumns), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(model.shown, key = { it.uri }, contentType = { "media" }) { item ->
                val state = if (tracking) statuses[item.uri].backed(item) else null
                MediaCell(item, loader, selecting = selectionMode, checked = item.uri in selected, favorite = item.uri in favorites,
                    status = state, statusLabel = state?.describe(online),
                    onClick = { if (selectionMode) model.toggle(item) else onOpen(item) },
                    onLongClick = { onLongPress(item) })
            }
        }
        if (groups.size > 1) TimelineRail(remember(groups) { groups.map { it.date } }, currentDateIndex, gridState.isScrollInProgress, Modifier.align(Alignment.CenterEnd).fillMaxHeight()) { index ->
            jumpJob?.cancel()
            jumpJob = scope.launch { gridState.scrollToItem(groupStarts[index]) }
        }
    }
}

@Composable
private fun CollectionsTab(model: GalleryModel, loader: ImageLoader, onOpen: () -> Unit, onVault: () -> Unit, onRecent: () -> Unit) {
    val media = model.media
    val favorites = remember(media, model.favorites) { media.filter { it.uri in model.favorites } }
    val videos = remember(media) { media.filter { it.video } }
    val albums = remember(media, model.albums) { model.albums.map { album -> album to media.filter { it.uri in album.members } } }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row { SourceChoice(listOf("" to "This phone") + model.drives.map { it.uri to it.name }, model.source, model::source) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!model.vaultHidden) UtilityTile("Private vault", "Encrypted", Modifier.weight(1f), onVault) { Glyph(R.drawable.ic_lock, null) }
                    UtilityTile("Recently deleted", "Phone Trash", Modifier.weight(1f), onRecent) { Icon(Icons.Outlined.Delete, null) }
                }
                if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White, trackColor = Palette.Card)
                SectionTitle("Highlights")
            }
        }
        if (model.loading && media.isEmpty()) { items(4) { SkeletonTile() }; return@LazyVerticalGrid }
        item { CollectionTile("Favorites", favorites.size, favorites.firstOrNull(), loader, icon = { Icon(Icons.Default.Favorite, null, Modifier.size(32.dp), tint = Palette.Faint) }) { model.openCollection("favorites"); onOpen() } }
        item { CollectionTile("Videos", videos.size, videos.firstOrNull(), loader, icon = { Glyph(R.drawable.ic_play, null, Modifier.size(32.dp), Palette.Faint) }) { model.openCollection(""); model.kind = "Videos"; onOpen() } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionTitle("Albums")
                if (albums.isEmpty()) Text("Select photos and tap Album to create one. Albums keep references; they don’t make extra copies.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        items(albums, key = { it.first.id }) { (album, entries) -> CollectionTile(album.name, entries.size, entries.firstOrNull(), loader) { model.openCollection(album.id); onOpen() } }
        item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Folders") }
        items(model.folderGroups, key = { "folder-" + it.first }) { (folder, entries) ->
            CollectionTile(folder.trimEnd('/').substringAfterLast('/').ifEmpty { folder }, entries.size, entries.firstOrNull(), loader) { model.openCollection(""); model.folder = folder; onOpen() }
        }
    }
}

@Composable
private fun UtilityTile(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Surface(onClick = onClick, color = Palette.Card, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(Palette.CardHigh, CircleShape), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun BackupTab(model: GalleryModel, transfer: TransferStatus, busy: Boolean, onAddDrive: () -> Unit, onCheck: () -> Unit, onDeep: () -> Unit, onMissing: () -> Unit, onReview: () -> Unit, onNotifications: () -> Unit, onStop: () -> Unit, onPause: () -> Unit) {
    val context = LocalContext.current
    val s = model.summary
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel {
            if (transfer.busy) {
                val fraction = if (transfer.total == 0) 0f else transfer.done.toFloat() / transfer.total
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Text("${transfer.done} of ${transfer.total}", color = Palette.Muted, modifier = Modifier.padding(bottom = 6.dp))
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = if (transfer.paused) Palette.Warning else Palette.Verified, trackColor = Palette.CardHigh, drawStopIndicator = {})
                Text(transfer.message, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPause) { Text(if (transfer.paused) "Resume" else "Pause") }
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                }
            } else {
                Box(Modifier.size(48.dp).background(Palette.CardHigh, CircleShape), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_backup, null, Modifier.size(24.dp)) }
                Text("A second home for your photos", style = MaterialTheme.typography.headlineSmall)
                Text(if (transfer.message == "Ready for your first backup") context.getSharedPreferences("library", 0).getString("lastTransfer", "Ready when you are") ?: "Ready when you are" else transfer.message, color = Palette.Muted)
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Backup drive", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (model.destination.isNotEmpty()) Pill(if (model.driveOnline) "Connected" else "Offline", dot = if (model.driveOnline) Palette.Verified else Palette.Faint)
            }
            Choice("Drive", listOf("" to "Choose a drive folder") + model.drives.map { it.uri to it.name }, model.destination, model::destination)
            TextButton(onClick = onAddDrive, contentPadding = PaddingValues(0.dp)) { Glyph(R.drawable.ic_add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (model.drives.isEmpty()) "Connect a drive folder" else "Connect another drive folder") }
        }
        if (model.destination.isNotEmpty()) Panel {
            Text("This phone on ${model.driveName() ?: "drive"}", style = MaterialTheme.typography.titleMedium)
            if (model.source.isNotEmpty()) Text("Switch Photos to This phone to see backup status.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            else if (s.total > 0) {
                val coverage = s.backedUp.toFloat() / s.total
                LinearProgressIndicator(progress = { coverage }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Palette.Verified, trackColor = Palette.CardHigh, drawStopIndicator = {})
                StatRow(Backed.VERIFIED, if (model.driveOnline) "Backed up" else "Last verified", s.backedUp)
                if (s.changed > 0) StatRow(Backed.CHANGED, "Changed since backup", s.changed)
                StatRow(Backed.MISSING, "Not backed up", s.missing)
                if (s.unchecked > 0) StatRow(Backed.UNCHECKED, "Not checked yet", s.unchecked)
            }
            Button(onClick = onMissing, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Glyph(R.drawable.ic_backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Back up missing photos")
            }
            OutlinedButton(onClick = onCheck, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Check what’s on the drive") }
            Text("Quick check compares sizes and saved fingerprints, so only new or changed photos are read. Files are matched by content, even with different names, and nothing that matches is copied again.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            TextButton(onClick = onDeep, enabled = !busy, contentPadding = PaddingValues(0.dp)) { Text("Deep verify — re-read every file (slow)") }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Parallel copies", style = MaterialTheme.typography.titleMedium)
                    Text(if (model.parallel == 1) "One file at a time" else "${model.parallel} files at a time", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("${model.parallel}", style = MaterialTheme.typography.headlineSmall)
            }
            Slider(value = model.parallel.toFloat(), onValueChange = { model.parallel(it.roundToInt()) }, valueRange = 1f..TransferService.MAX_PARALLEL.toFloat(), steps = TransferService.MAX_PARALLEL - 2, enabled = !transfer.busy,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Palette.CardHigh, activeTickColor = Color.Black.copy(alpha = .3f), inactiveTickColor = Palette.Faint))
            Text("Higher is faster on USB flash drives and SSDs. Spinning hard drives are often fastest at 1–2. Every copy is still verified.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (model.destination.isNotEmpty()) Panel(onClick = if (busy) null else onReview) {
            val count = model.deletionReview.items.count { !it.kept }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Review phone deletions", style = MaterialTheme.typography.titleMedium)
                    Text(if (model.reviewing) "Checking recorded backups…" else if (count == 0) "Nothing to review" else "$count backup${if (count == 1) "" else "s"} whose phone original is gone", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (model.reviewing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else if (count > 0) Badge(containerColor = Palette.Warning, contentColor = Color.Black) { Text("$count") }
            }
        }
        SectionTitle("How PhotoS protects your copies")
        Text("Every copy is read back and checked before it counts. If a transfer stops, run it again to continue with the remaining files.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Text("Your cloud backup stays in Google Photos. PhotoS handles your drive copy separately.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onNotifications, contentPadding = PaddingValues(0.dp)) { Text("Enable transfer notifications") }
    }
}

@Composable
private fun StatRow(state: Backed, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (state == Backed.UNCHECKED) Box(Modifier.size(8.dp).background(Palette.Faint, CircleShape))
            else if (state == Backed.MISSING) Box(Modifier.size(14.dp).border(1.5.dp, Palette.Muted, CircleShape))
            else StatusBadge(state, size = 16.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("%,d".format(count), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun DrivesTab(model: GalleryModel, busy: Boolean, onBrowse: (String) -> Unit, onReview: (String) -> Unit, onAddDrive: () -> Unit, onIndex: (String, Boolean) -> Unit, onDuplicates: (Drive) -> Unit) {
    var rebuilding by remember { mutableStateOf<Drive?>(null) }
    rebuilding?.let { drive ->
        AlertDialog(onDismissRequest = { rebuilding = null }, title = { Text("Rebuild fast index?") },
            text = { Text("Every photo on ${drive.name} is read again to rebuild its catalog and thumbnails. This can take a while for a large drive. Photos are not changed.") },
            confirmButton = { TextButton(onClick = { rebuilding = null; onIndex(drive.uri, true) }) { Text("Rebuild") } },
            dismissButton = { TextButton(onClick = { rebuilding = null }) { Text("Cancel") } })
    }
    var renaming by remember { mutableStateOf<Drive?>(null) }
    renaming?.let { drive -> DriveNameDialog("Rename drive", drive.name, onDismiss = { renaming = null }) { model.renameDrive(drive.uri, it); renaming = null } }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your library, wherever you keep it.", color = Palette.Muted)
        model.drives.forEach { drive ->
            val online = model.connected[drive.uri]
            val isDestination = model.destination == drive.uri
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(Palette.CardHigh, CircleShape), contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_drive, null, Modifier.size(22.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Rename drive") { renaming = drive }) {
                        Text(drive.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (isDestination) "Backup destination" else "Saved folder", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                    }
                    when (online) {
                        true -> Pill("Connected", dot = Palette.Verified)
                        false -> Pill("Not connected", dot = Palette.Faint)
                        null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Muted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { onBrowse(drive.uri) }, enabled = online != false) { Text("Browse") }
                    if (!isDestination) OutlinedButton(onClick = { model.destination(drive.uri) }) { Text("Use for backup") }
                    else TextButton(onClick = { onReview(drive.uri) }, enabled = !busy && online != false) { Text("Review deletions") }
                }
                // Fast index: catalog + thumbnails on the drive, so it opens fast on any phone or computer.
                val status = model.indexStatus[drive.uri]
                Text(when {
                    status == null -> "Fast index: browse the drive to check it"
                    status.total == 0 -> "Fast index: nothing backed up here yet"
                    status.missing == 0 -> "Fast index ready ✓ · ${"%,d".format(status.total)} photos"
                    else -> "Fast index: ${"%,d".format(status.indexed)} of ${"%,d".format(status.total)} · ${"%,d".format(status.missing)} new"
                }, style = MaterialTheme.typography.bodySmall, color = if (status != null && status.missing > 0) Palette.Warning else Palette.Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onIndex(drive.uri, false) }, enabled = !busy && online == true) { Text("Update index") }
                    TextButton(onClick = { onDuplicates(drive) }, enabled = online == true) { Text("Duplicates") }
                    Spacer(Modifier.weight(1f))
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) { Glyph(R.drawable.ic_more, "More for ${drive.name}") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = drive })
                            DropdownMenuItem(text = { Text("Rebuild fast index") }, enabled = !busy && online == true, onClick = { menu = false; rebuilding = drive })
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onAddDrive, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Glyph(R.drawable.ic_add, null); Spacer(Modifier.width(8.dp)); Text("Add drive folder") }
        Surface(color = Palette.Card, shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().clickable { model.autoIndex(!model.autoIndex) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Keep fast index updated automatically")
                    Text("When you browse a drive, new photos are indexed in the background (two at a time). While the app is open, the drive is re-read every 10 minutes.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
                Switch(checked = model.autoIndex, onCheckedChange = model::autoIndex)
            }
        }
        Text("Connect your USB drive, then choose a folder on it in Android’s picker. Browsing a drive won’t change your backup destination.", color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AlbumDialog(albums: List<Album>, onPick: (Album) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add to album") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            albums.forEach { album ->
                Surface(onClick = { onPick(album) }, color = Palette.CardHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Text(album.name, Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                }
            }
            if (albums.isNotEmpty()) Spacer(Modifier.height(8.dp))
            OutlinedTextField(name, { name = it }, label = { Text("New album name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create album") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun DriveNameDialog(title: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(current, androidx.compose.ui.text.TextRange(0, current.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = name.text.isNotBlank()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { if (it.text.length <= 40) name = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                label = { Text("Drive name") }, placeholder = { Text("e.g. Family backup, Red SSD") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onSave(name.text) }))
            Text("Shown in PhotoS on this phone. Also saved on the drive, so other phones and the Windows app show the same name.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
    }, confirmButton = { TextButton(onClick = { onSave(name.text) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
