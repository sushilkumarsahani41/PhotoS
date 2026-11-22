package app.photos

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Identical photos and videos on a drive, found from the fast index fingerprints. One copy of each set is kept
 * (tap another copy to keep it instead; untick a set to leave it alone). Removal runs in the background service,
 * which re-reads both files before each deletion.
 */
@Composable
fun DuplicatesScreen(drive: Drive, model: GalleryModel, loader: ImageLoader, busy: Boolean, onIndex: () -> Unit, onRemove: (List<DuplicateSet>) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val result by produceState<Pair<Duplicates, DriveCatalog?>?>(null, drive.uri, reload) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val items = if (model.source == drive.uri && model.media.isNotEmpty()) model.media else MediaRepository(context).drive(drive.uri)
                val catalog = DriveCatalog.open(context, Uri.parse(drive.uri), create = false)
                (catalog?.duplicates(items) ?: Duplicates(emptyList(), items.size)) to catalog
            }.getOrElse { Duplicates(emptyList(), 0) to null }
        }
    }
    val off = remember(result) { mutableStateListOf<String>() }
    val keep = remember(result) { mutableStateMapOf<String, String>() }
    var confirm by remember { mutableStateOf(false) }
    val chosen = result?.first?.groups.orEmpty().filter { it.hash !in off }.map { it.copy(keep = keep[it.hash] ?: it.keep) }
    val extra = chosen.sumOf { it.files.size - 1 }
    val freed = chosen.sumOf { it.size * (it.files.size - 1) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Palette.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Duplicates", style = MaterialTheme.typography.headlineSmall)
                    Text(drive.name, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { reload++ }, enabled = result != null) { Text("Search again") }
            }
            val data = result
            if (data == null) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text("Comparing fingerprints…", color = Palette.Muted)
                }
                return@Column
            }
            val (dupes, catalog) = data
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Surface(color = Palette.Card, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(Formatter.formatShortFileSize(context, freed) + " can be freed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text("${dupes.groups.size} ${if (dupes.groups.size == 1) "set" else "sets"} of identical files · $extra extra ${if (extra == 1) "copy" else "copies"}", color = Palette.Muted)
                            if (dupes.unchecked > 0) {
                                Text("${dupes.unchecked} photos aren't in the fast index yet, so they weren't compared.", style = MaterialTheme.typography.bodySmall, color = Palette.Warning)
                                TextButton(onClick = onIndex, enabled = !busy, contentPadding = PaddingValues(0.dp)) { Text("Update fast index") }
                            }
                        }
                    }
                }
                if (dupes.groups.isEmpty()) item {
                    Text(if (catalog == null) "This drive has no fast index yet. Build it from Drives first." else "No duplicates found.", Modifier.padding(vertical = 24.dp), color = Palette.Muted)
                } else item {
                    Text("Copies are byte-for-byte identical. Tap a copy to keep it instead; untick a set to leave it alone.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
                items(dupes.groups, key = { it.hash }) { set ->
                    val included = set.hash !in off
                    val keeper = keep[set.hash] ?: set.keep
                    Surface(color = Palette.Surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, Palette.Line), modifier = Modifier.alpha(if (included) 1f else .5f)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(included, { if (it) off.remove(set.hash) else off.add(set.hash) })
                                Text("${set.files.size} copies", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text("${Formatter.formatShortFileSize(context, set.size)} each", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(set.files, key = { it.uri }) { file ->
                                    val isKeep = file.uri == keeper
                                    Column(Modifier.width(118.dp).clip(MaterialTheme.shapes.small)
                                        .border(2.dp, if (isKeep) Palette.Verified else Color.Transparent, MaterialTheme.shapes.small)
                                        .clickable(enabled = included) { keep[set.hash] = file.uri }.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        AsyncImage(remember(file.uri) { ImageRequest.Builder(context).data(Thumb(file.uri, file.modified)).build() }, file.name, imageLoader = loader,
                                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraSmall).background(Palette.Placeholder))
                                        Text(if (isKeep) "Keep" else "Remove", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                                            color = if (isKeep) Color.Black else Palette.Danger,
                                            modifier = Modifier.background(if (isKeep) Palette.Verified else Palette.CardHigh, CircleShape).padding(horizontal = 8.dp, vertical = 1.dp))
                                        Text(file.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(catalog?.albumOf(file.uri).orEmpty(), style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Surface(color = Palette.Bar) {
                Button(onClick = { confirm = true }, enabled = extra > 0 && !busy, modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                    Text(if (extra == 0) "Nothing selected" else "Remove $extra extra ${if (extra == 1) "copy" else "copies"} · ${Formatter.formatShortFileSize(context, freed)}")
                }
            }
        }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false },
            title = { Text("Delete $extra extra ${if (extra == 1) "copy" else "copies"}?") },
            text = { Text("One copy of each photo stays on the drive. Each copy is re-read and compared just before it is removed. Android has no Trash for USB drives, so removed copies are deleted permanently.") },
            confirmButton = { TextButton(onClick = { confirm = false; onRemove(chosen) }) { Text("Delete permanently", color = Palette.Danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    }
}
