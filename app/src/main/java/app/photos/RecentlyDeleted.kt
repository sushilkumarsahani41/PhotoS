package app.photos

import android.app.Activity
import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.*

private suspend fun phoneTrash(context: android.content.Context): List<MediaEntry> {
    val results = mutableListOf<MediaEntry>()
    for (collection in listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "is_trashed=1")
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "date_modified DESC")
        }
        context.contentResolver.query(collection, arrayOf("_id", "_display_name", "mime_type", "_size", "date_modified", "relative_path"), args, null)?.use { c ->
            while(c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                results += MediaEntry(ContentUris.withAppendedId(collection, c.getLong(0)).toString(), c.getString(1) ?: "Media", c.getString(2) ?: "application/octet-stream", c.getLong(3), c.getLong(4) * 1000, c.getString(5) ?: "Phone Trash")
            }
        }
    }
    return results.sortedByDescending { it.date }
}

@Composable
fun RecentlyDeleted(loader: ImageLoader, onChanged: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Boolean?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun load() {
        job?.cancel()
        job = scope.launch {
            loading = true
            try { items = withContext(Dispatchers.IO) { phoneTrash(context) }; selected = emptySet() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { items = emptyList(); message = "Grant photo/video access to read phone Trash." }
            finally { loading = false }
        }
    }
    val result = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (!restoring) DeletionManager(context).finishPhoneRequest(it.resultCode == Activity.RESULT_OK)
        busy = false
        message = if (it.resultCode == Activity.RESULT_OK) (if (restoring) "Selected files restored to phone." else "Selected files permanently deleted from phone. Drive copies were kept.") else "Cancelled."
        load(); onChanged()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) load() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }
    LaunchedEffect(Unit) { load() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recently deleted", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onClose) { Text("Close") }
            }
            Text("Phone Trash • Android controls its retention. Permanent USB deletions cannot be recovered here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(MediaRepository(context).accessLabel(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${items.size} items", Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.isEmpty()) items.map { it.uri }.toSet() else emptySet() }, enabled = !busy) { Text(if (selected.isEmpty()) "Select all" else "Clear") }
                TextButton(onClick = ::load, enabled = !busy && !loading) { Text("Refresh") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message, Modifier.padding(vertical = 8.dp))
            if (!loading && items.isEmpty()) Text("No accessible items in phone Trash.", Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.uri }) { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(item.uri, item.name, imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
                        Text(item.name, Modifier.weight(1f).padding(12.dp), maxLines = 2)
                        Checkbox(item.uri in selected, { selected = if (item.uri in selected) selected - item.uri else selected + item.uri }, enabled = !busy)
                    }
                }
            }
            if (selected.isNotEmpty()) {
                Button(onClick = { confirmation = true }, enabled = !busy && selected.size <= 500, modifier = Modifier.fillMaxWidth()) { Text("Restore ${selected.size}") }
                TextButton(onClick = { confirmation = false }, enabled = !busy && selected.size <= 500, modifier = Modifier.fillMaxWidth()) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
                if (selected.size > 500) Text("Select up to 500 items at a time.")
            }
        }
    }
    confirmation?.let { restore ->
        val snapshot = selected.toList()
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (restore) "Restore ${snapshot.size} files?" else "Permanently delete ${snapshot.size} files?") }, text = { Text(if (restore) "These items will return to the phone gallery." else "These phone files cannot be restored from Trash afterward. Drive copies remain separate.") }, confirmButton = {
            TextButton(onClick = {
                confirmation = null
                try {
                    val uris = snapshot.map(android.net.Uri::parse)
                    val request = if (restore) MediaStore.createTrashRequest(context.contentResolver, uris, false) else MediaStore.createDeleteRequest(context.contentResolver, uris)
                    restoring = restore; busy = true
                    if (!restore) DeletionManager(context).beginPhoneRequest(snapshot)
                    result.launch(IntentSenderRequest.Builder(request.intentSender).build())
                } catch (e: Exception) { if (!restore) DeletionManager(context).finishPhoneRequest(false); busy = false; message = e.message ?: "Operation unavailable" }
            }) { Text(if (restore) "Restore" else "Delete permanently") }
        }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } })
    }
}
