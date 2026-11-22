package app.photos

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun Glyph(resource: Int, description: String?, modifier: Modifier = Modifier.size(24.dp), tint: Color = LocalContentColor.current) {
    Icon(painterResource(resource), description, modifier, tint)
}

private val sameYear = DateTimeFormatter.ofPattern("EEE, d MMM")
private val otherYear = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
fun dateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(if (date.year == today.year) sameYear else otherYear)
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun bytes(size: Long): String = when {
    size >= 1L shl 30 -> "%.2f GB".format(size / (1L shl 30).toDouble())
    size >= 1L shl 20 -> "%.1f MB".format(size / (1L shl 20).toDouble())
    else -> "${(size / 1024).coerceAtLeast(1)} KB"
}

fun Backed.describe(online: Boolean) = when (this) {
    Backed.VERIFIED -> if (online) "Backed up" else "Last verified"
    Backed.CHANGED -> "Changed since backup"
    Backed.MISSING -> "Not backed up"
    Backed.UNCHECKED -> "Not checked"
}

/** Small corner badge; colour plus shape so status never depends on colour alone. */
@Composable
fun StatusBadge(state: Backed, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    when (state) {
        Backed.VERIFIED -> Box(modifier.size(size).background(Palette.Verified, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, Modifier.size(size * .7f), tint = Color.Black)
        }
        Backed.CHANGED -> Box(modifier.size(size).background(Palette.Warning, CircleShape), contentAlignment = Alignment.Center) {
            Text("!", color = Color.Black, fontSize = (size.value * .62f).sp, fontWeight = FontWeight.Bold)
        }
        Backed.MISSING -> Box(modifier.size(size).background(Color.Black.copy(alpha = .45f), CircleShape).border(1.5.dp, Color.White.copy(alpha = .85f), CircleShape))
        Backed.UNCHECKED -> Unit
    }
}

@Composable
fun Pill(text: String, color: Color = Palette.Muted, dot: Color? = null, modifier: Modifier = Modifier) {
    Row(modifier.background(Palette.CardHigh, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) { Box(Modifier.size(7.dp).background(dot, CircleShape)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        trailing?.invoke()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val inner: @Composable () -> Unit = { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    if (onClick != null) Surface(onClick = onClick, color = Palette.Card, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth(), content = inner)
    else Surface(color = Palette.Card, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth(), content = inner)
}

/** One grid cell. Selection shrinks the photo and shows a check, like a physical contact sheet being marked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCell(item: MediaEntry, loader: ImageLoader, selecting: Boolean, checked: Boolean, favorite: Boolean, status: Backed?, statusLabel: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember(item.uri) { mutableStateOf(false) }
    val request = remember(item.uri) { ImageRequest.Builder(context).data(Thumb(item.uri, item.modified)).allowRgb565(true).build() }
    val inset by animateDpAsState(if (checked) 10.dp else 0.dp, label = "inset")
    val corner by animateDpAsState(if (checked) 12.dp else 0.dp, label = "corner")
    val description = buildString {
        append(if (item.video) "Video " else "Photo "); append(item.name)
        if (favorite) append(", favorite")
        if (statusLabel != null) append(", ").append(statusLabel)
        if (selecting) append(if (checked) ", selected" else ", not selected")
    }
    Box(Modifier.aspectRatio(1f).background(if (checked) Palette.CardHigh else Palette.Placeholder).then(if (loaded) Modifier else Modifier.shimmer())
        .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
        .semantics(mergeDescendants = true) { contentDescription = description }) {
        AsyncImage(request, null, imageLoader = loader, contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { loaded = true }, modifier = Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(corner)))
        if (item.video) Row(Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_play, null, Modifier.size(11.dp), Color.White)
            if (item.duration > 0) { Spacer(Modifier.width(3.dp)); Text(duration(item.duration), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium) }
        }
        if (favorite) Icon(Icons.Default.Favorite, null, Modifier.align(Alignment.BottomStart).padding(6.dp).size(15.dp), tint = Color.White)
        if (status != null) StatusBadge(status, Modifier.align(Alignment.BottomEnd).padding(6.dp), 16.dp)
        if (selecting) {
            val scale by animateFloatAsState(if (checked) 1f else .9f, label = "check")
            Box(Modifier.align(Alignment.TopStart).padding(7.dp).size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if (checked) Modifier.background(Color.White, CircleShape) else Modifier.background(Color.Black.copy(alpha = .25f), CircleShape).border(1.5.dp, Color.White, CircleShape)),
                contentAlignment = Alignment.Center) {
                if (checked) Icon(Icons.Default.Check, null, Modifier.size(15.dp), tint = Color.Black)
            }
        }
    }
}

/** Two-finger pinch changes grid density; single-finger scrolling is left untouched. */
fun Modifier.pinchColumns(onChange: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                if (zoom > 1.3f) { onChange(-1); zoom = 1f } else if (zoom < 0.77f) { onChange(1); zoom = 1f }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
fun CollectionTile(title: String, count: Int, cover: MediaEntry?, loader: ImageLoader, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null, open: () -> Unit) {
    val context = LocalContext.current
    Column(modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = open)) {
        var loaded by remember(cover?.uri) { mutableStateOf(cover == null) }
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium).background(Palette.Card).then(if (loaded) Modifier else Modifier.shimmer()), contentAlignment = Alignment.Center) {
            if (cover != null) AsyncImage(remember(cover.uri) { ImageRequest.Builder(context).data(Thumb(cover.uri, cover.modified)).build() }, null, imageLoader = loader, contentScale = ContentScale.Crop, onSuccess = { loaded = true }, onError = { loaded = true }, modifier = Modifier.fillMaxSize())
            else icon?.invoke() ?: Glyph(R.drawable.ic_photos, null, Modifier.size(32.dp), Palette.Faint)
        }
        Text(title, Modifier.padding(top = 8.dp, start = 2.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count item${if (count == 1) "" else "s"}", Modifier.padding(start = 2.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    }
}

@Composable
fun SourceChoice(options: List<Pair<String, String>>, selected: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, color = Palette.Card, shape = CircleShape) {
            Row(Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (selected.isEmpty()) R.drawable.ic_phone else R.drawable.ic_drive, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(options.find { it.first == selected }?.second ?: "Choose source", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                Spacer(Modifier.width(4.dp))
                Glyph(R.drawable.ic_chevron, null, Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, leadingIcon = { Glyph(if (key.isEmpty()) R.drawable.ic_phone else R.drawable.ic_drive, null, Modifier.size(18.dp)) },
                    trailingIcon = if (key == selected) ({ Icon(Icons.Default.Check, null) }) else null, onClick = { change(key); expanded = false })
            }
        }
    }
}

@Composable
fun FolderChoice(options: List<String>, selected: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected != "All folders", onClick = { expanded = true }, label = { Text(if (selected == "All folders") "Folders" else selected.trimEnd('/').substringAfterLast('/'), maxLines = 1) }, leadingIcon = { Glyph(R.drawable.ic_folder, null, Modifier.size(16.dp)) }, shape = CircleShape)
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { folder -> DropdownMenuItem(text = { Text(if (folder == "All folders") folder else folder.trimEnd('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { change(folder); expanded = false }) }
        }
    }
}

@Composable
fun Choice(label: String, options: List<Pair<String, String>>, selected: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Text("$label: ${options.find { it.first == selected }?.second ?: "Choose"}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Glyph(R.drawable.ic_chevron, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; change(key) }) }
        }
    }
}

/** Soft light sweep over a placeholder while its content loads. */
@Composable
fun Modifier.shimmer(): Modifier {
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "sweep")
    return this.drawBehind {
        val w = size.width.coerceAtLeast(1f)
        drawRect(Brush.linearGradient(listOf(Palette.Placeholder, Color(0xFF262626), Palette.Placeholder), start = Offset(w * sweep - w, 0f), end = Offset(w * sweep, size.height)))
    }
}

/** Grid of shimmering squares shown while the library is first read. */
@Composable
fun SkeletonGrid(columns: Int, modifier: Modifier = Modifier) {
    LazyVerticalGrid(GridCells.Fixed(columns), modifier.fillMaxSize(), userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(columns * 9) { Box(Modifier.aspectRatio(1f).shimmer()) }
    }
}

/** Placeholder with the same shape as [CollectionTile]. */
@Composable
fun SkeletonTile(modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium).shimmer())
        Box(Modifier.padding(top = 10.dp, start = 2.dp).size(90.dp, 12.dp).clip(CircleShape).shimmer())
        Box(Modifier.padding(top = 6.dp, start = 2.dp, bottom = 4.dp).size(50.dp, 10.dp).clip(CircleShape).shimmer())
    }
}
