package app.photos

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Thumbnails decrypt a few at a time so scrolling stays smooth and memory stays bounded. */
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
private val thumbWork = Dispatchers.IO.limitedParallelism(3)

private val VaultEntry.video get() = mime.startsWith("video/")

/** Photo grid of the unlocked vault. Thumbnails live only in memory and are dropped when the vault locks. */
@Composable
fun VaultGrid(entries: List<VaultEntry>, thumbs: SnapshotStateMap<String, ImageBitmap>, loadThumb: suspend (VaultEntry) -> Bitmap?, modifier: Modifier = Modifier, onOpen: (Int) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(110.dp), modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
            var failed by remember(entry.id) { mutableStateOf(false) }
            LaunchedEffect(entry.id) {
                if (entry.id !in thumbs) {
                    val bitmap = withContext(thumbWork) { runCatching { loadThumb(entry) }.getOrNull() }
                    if (bitmap != null) thumbs[entry.id] = bitmap.asImageBitmap() else failed = true
                }
            }
            val image = thumbs[entry.id]
            Box(Modifier.aspectRatio(1f).background(Palette.Placeholder).then(if (image == null && !failed) Modifier.shimmer() else Modifier).clickable { onOpen(index) }, contentAlignment = Alignment.Center) {
                if (image != null) Image(image, entry.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (entry.video) Glyph(R.drawable.ic_play, "Video", Modifier.size(if (image == null) 32.dp else 28.dp), Color.White)
                else if (failed) Glyph(R.drawable.ic_photos, null, Modifier.size(28.dp), Palette.Faint)
            }
        }
    }
}

/** Full-screen swipe viewer for vault items. Photos and videos are decrypted in memory only while their page is shown. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultViewer(
    entries: List<VaultEntry>, start: Int, thumbs: Map<String, ImageBitmap>,
    loadPhoto: suspend (VaultEntry) -> Bitmap?, video: (VaultEntry) -> DataSource.Factory,
    busy: Boolean, onRestore: (VaultEntry) -> Unit, onDelete: (VaultEntry) -> Unit, onClose: () -> Unit,
) {
    if (entries.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, entries.lastIndex)) { entries.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val current = entries[pager.currentPage.coerceIn(0, entries.lastIndex)]
    LaunchedEffect(pager.currentPage) { zoomed = false }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { entries[it].id }, pageSpacing = 12.dp) { page ->
                val entry = entries[page]
                if (entry.video) VaultVideo(entry, active = page == pager.currentPage, thumb = thumbs[entry.id], factory = video, chrome = chrome)
                else VaultPhoto(entry, thumbs[entry.id], loadPhoto, onZoom = { if (page == pager.currentPage) zoomed = it }, onTap = { chrome = !chrome })
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent))).statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(current.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pager.currentPage + 1} of ${entries.size}", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))).navigationBarsPadding().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ViewerButton("Restore", { Glyph(R.drawable.ic_restore, null, tint = Color.White) }, !busy) { onRestore(current) }
                    ViewerButton("Info", { Icon(Icons.Outlined.Info, null, tint = Color.White) }, true) { info = true }
                    ViewerButton("Delete", { Icon(Icons.Outlined.Delete, null, tint = Color.White) }, !busy) { onDelete(current) }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        if (info) ModalBottomSheet(onDismissRequest = { info = false }, containerColor = Palette.Card, properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current.name, style = MaterialTheme.typography.titleMedium)
                Detail("Taken", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.date)))
                Detail("Type", current.mime)
                Detail("Size", bytes(current.size))
                Detail("Stored", "Encrypted on this phone only")
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) = Row {
    Text(label, Modifier.width(100.dp), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ViewerButton(label: String, icon: @Composable () -> Unit, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) { icon() }
        Text(label, color = Color.White.copy(alpha = if (enabled) .85f else .4f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun VaultPhoto(entry: VaultEntry, thumb: ImageBitmap?, load: suspend (VaultEntry) -> Bitmap?, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    val full by produceState<ImageBitmap?>(null, entry.id) {
        value = withContext(Dispatchers.IO) { runCatching { load(entry) }.getOrNull() }?.asImageBitmap()
        failed = value == null
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
        onZoom(scale > 1f)
    }
    Box(Modifier.fillMaxSize()
        .pointerInput(entry.id) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = { tap ->
                if (scale > 1f) { scale = 1f; offset = Offset.Zero; onZoom(false) }
                else { scale = 2.5f; offset = (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f; onZoom(true) }
            })
        }
        .transformable(transform, canPan = { scale > 1f }), contentAlignment = Alignment.Center) {
        val image = full ?: thumb
        val layer = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
        if (image != null) Image(image, entry.name, layer, contentScale = ContentScale.Fit)
        if (full == null && !failed) CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 2.dp)
        if (failed) Text("Preview unavailable. Restore it to view in another app.", Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp), color = Palette.Muted)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VaultVideo(entry: VaultEntry, active: Boolean, thumb: ImageBitmap?, factory: (VaultEntry) -> DataSource.Factory, chrome: Boolean) {
    if (!active) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, entry.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Glyph(R.drawable.ic_play, null, Modifier.size(56.dp), Color.White)
        }
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(entry.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(factory(entry)).createMediaSource(MediaItem.fromUri("vault://${entry.id}")))
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxSize().systemBarsPadding().padding(bottom = if (chrome) 72.dp else 0.dp))
}
