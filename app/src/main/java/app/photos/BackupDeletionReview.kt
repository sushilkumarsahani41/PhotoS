package app.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Backups whose phone originals are gone. Multi-select with Select all; Keep and Delete apply to the whole selection. */
@Composable
fun BackupDeletionReview(review: DeletionReview, loading: Boolean, busy: Boolean, driveName: String, loader: ImageLoader, onClose: () -> Unit, onRefresh: () -> Unit, onKeep: (List<ReviewItem>, Boolean) -> Unit, onDelete: (List<ReviewItem>) -> Unit) {
    val context = LocalContext.current
    var includeKept by remember { mutableStateOf(false) }
    val visible = remember(review, includeKept) { review.items.filter { includeKept || !it.kept } }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // Drop selections that are no longer listed after a refresh or filter change.
    LaunchedEffect(visible) { val uris = visible.mapTo(HashSet()) { it.target.uri }; selected = selected.filterTo(HashSet()) { it in uris } }
    val chosen = visible.filter { it.target.uri in selected }
    val allSelected = visible.isNotEmpty() && chosen.size == visible.size
    val enabled = !busy && !loading
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Palette.Black).safeDrawingPadding()) {
            Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                Column(Modifier.weight(1f)) {
                    Text(if (chosen.isEmpty()) "Phone deletions" else "${chosen.size} selected", style = MaterialTheme.typography.titleLarge)
                    Text(driveName, style = MaterialTheme.typography.labelMedium, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { selected = if (allSelected) emptySet() else visible.mapTo(HashSet()) { it.target.uri } }, enabled = enabled && visible.isNotEmpty()) { Text(if (allSelected) "Clear" else "Select all") }
            }
            Text(review.notice, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable { includeKept = !includeKept }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(includeKept, { includeKept = it })
                    Text("Show kept copies")
                }
                Text("${visible.size} item${if (visible.size == 1) "" else "s"}", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onRefresh, enabled = enabled) { Text("Refresh") }
            }
            Box(Modifier.height(2.dp).fillMaxWidth()) { if (loading) LinearProgressIndicator(Modifier.fillMaxSize(), color = Color.White, trackColor = Palette.Card) }
            if (!loading && visible.isEmpty()) Text("No recorded backup copies need review here.", Modifier.padding(24.dp), color = Palette.Muted)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.target.uri }) { item ->
                    val checked = item.target.uri in selected
                    Surface(onClick = { selected = if (checked) selected - item.target.uri else selected + item.target.uri }, enabled = enabled, shape = MaterialTheme.shapes.medium, color = if (checked) Palette.CardHigh else Palette.Card) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(24.dp).then(if (checked) Modifier.background(Color.White, CircleShape) else Modifier.background(Color.Transparent, CircleShape)), contentAlignment = Alignment.Center) {
                                if (checked) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Color.Black)
                                else Box(Modifier.size(20.dp).background(Palette.Line, CircleShape).padding(2.dp).background(Palette.Card, CircleShape))
                            }
                            Spacer(Modifier.width(10.dp))
                            AsyncImage(remember(item.target.uri) { ImageRequest.Builder(context).data(Thumb(item.target.uri)).build() }, item.target.name, imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(Palette.Placeholder))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(item.target.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (item.kept) "Kept on drive" else item.reason, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 2)
                            }
                        }
                    }
                }
            }
            if (chosen.isNotEmpty()) Surface(color = Palette.Bar) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val keepAll = chosen.any { !it.kept }
                    OutlinedButton(onClick = { onKeep(chosen, keepAll); selected = emptySet() }, enabled = enabled, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                        Text(if (keepAll) "Keep ${chosen.size}" else "Review again", maxLines = 1)
                    }
                    Button(onClick = { onDelete(chosen) }, enabled = enabled, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger, contentColor = Color.Black)) { Text("Delete ${chosen.size}", maxLines = 1) }
                }
            }
        }
    }
}
