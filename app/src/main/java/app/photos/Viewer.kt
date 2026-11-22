package app.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.DateFormat
import java.util.Date

/** Full-screen viewer over a snapshot of the visible list. Swipe between items; tap toggles controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Viewer(
    items: List<MediaEntry>, start: Int, loader: ImageLoader, favorites: Set<String>, onFavorite: (String) -> Unit,
    statuses: Map<String, SyncRecord>?, online: Boolean, driveName: String?,
    onDelete: (MediaEntry) -> Unit, deleteEnabled: Boolean, onClose: () -> Unit, onVault: ((MediaEntry) -> Unit)? = null,
) {
    if (items.isEmpty()) { onClose(); return }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    LaunchedEffect(pager.currentPage) { zoomed = false }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { items[it].uri }, beyondViewportPageCount = 1, pageSpacing = 12.dp) { page ->
                val item = items[page]
                if (item.video) VideoPage(item, active = page == pager.currentPage, loader = loader, chrome = chrome)
                else PhotoPage(item, loader, onZoom = { if (page == pager.currentPage) zoomed = it }, onTap = { chrome = !chrome })
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent))).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(dateLabel(java.time.LocalDate.ofEpochDay(current.day)), color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(current.date)) + "  ·  ${pager.currentPage + 1} of ${items.size}", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                    }
                    if (statuses != null && driveName != null) StatusBadge(statuses[current.uri].backed(current), Modifier.padding(end = 12.dp), 20.dp)
                }
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))).navigationBarsPadding().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val fav = current.uri in favorites
                    ViewerAction(if (fav) "Unfavorite" else "Favorite", { Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, tint = Color.White) }, onLongClick = onVault?.let { { it(current) } }) { onFavorite(current.uri) }
                    ViewerAction("Info", { Icon(Icons.Outlined.Info, null, tint = Color.White) }) { info = true }
                    ViewerAction("Delete", { Icon(Icons.Outlined.Delete, null, tint = if (deleteEnabled) Color.White else Color.White.copy(alpha = .4f)) }, enabled = deleteEnabled) { onDelete(current) }
                }
            }
        }
        if (info) ModalBottomSheet(onDismissRequest = { info = false }, containerColor = Palette.Card) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(current.name, style = MaterialTheme.typography.titleMedium)
                InfoRow("Taken", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.date)))
                InfoRow("Type", current.mime + if (current.video && current.duration > 0) " · ${duration(current.duration)}" else "")
                InfoRow("Size", bytes(current.size))
                InfoRow("Folder", current.folder)
                if (statuses != null && driveName != null) {
                    val record = statuses[current.uri]
                    val state = record.backed(current)
                    HorizontalDivider(color = Palette.Line)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(state, size = 20.dp)
                        if (state != Backed.UNCHECKED) Spacer(Modifier.width(10.dp))
                        Text(state.describe(online), style = MaterialTheme.typography.titleSmall)
                    }
                    InfoRow("Drive", "$driveName · ${if (online) "connected" else "offline, showing saved status"}")
                    record?.let { InfoRow("Checked", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.checked))) }
                    record?.verified?.takeIf { it > 0 }?.let { InfoRow("Last verified", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(110.dp), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ViewerAction(label: String, icon: @Composable () -> Unit, enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).combinedClickable(enabled = enabled, onClick = onClick,
            onLongClick = onLongClick?.let { action -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); action() } }), contentAlignment = Alignment.Center) { icon() }
        Text(label, color = Color.White.copy(alpha = if (enabled) .85f else .4f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PhotoPage(item: MediaEntry, loader: ImageLoader, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
        onZoom(scale > 1f)
    }
    // Thumbnail first (instant, from cache), full image on top when decoded.
    val thumb = remember(item.uri) { ImageRequest.Builder(context).data(Thumb(item.uri, item.modified)).build() }
    Box(Modifier.fillMaxSize()
        .pointerInput(item.uri) {
            detectTapGestures(onTap = { onTap() }, onDoubleTap = { tap ->
                if (scale > 1f) { scale = 1f; offset = Offset.Zero; onZoom(false) }
                else { scale = 2.5f; offset = (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f; onZoom(true) }
            })
        }
        .transformable(transform, canPan = { scale > 1f }), contentAlignment = Alignment.Center) {
        val layer = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
        AsyncImage(thumb, null, imageLoader = loader, contentScale = ContentScale.Fit, modifier = layer)
        AsyncImage(item.uri, item.name, imageLoader = loader, contentScale = ContentScale.Fit, modifier = layer)
    }
}

@Composable
private fun VideoPage(item: MediaEntry, active: Boolean, loader: ImageLoader, chrome: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (!active) {
        // Only the visible page owns a player; neighbours show a still frame.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(remember(item.uri) { ImageRequest.Builder(context).data(Thumb(item.uri, item.modified)).build() }, item.name, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Glyph(R.drawable.ic_play, null, Modifier.size(56.dp), Color.White)
        }
        return
    }
    val player = remember(item.uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri)); prepare(); playWhenReady = true } }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxSize().systemBarsPadding().padding(bottom = if (chrome) 72.dp else 0.dp))
}
