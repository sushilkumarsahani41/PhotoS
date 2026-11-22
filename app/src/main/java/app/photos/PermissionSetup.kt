package app.photos

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun PermissionSetup(driveCount: Int, onAddDrive: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repo = remember { MediaRepository(context) }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    // Real OS state is read again on return; launching Settings does not imply a grant.
    val full = remember(revision) { repo.has(Manifest.permission.READ_MEDIA_IMAGES) && repo.has(Manifest.permission.READ_MEDIA_VIDEO) }
    val originals = remember(revision) { repo.has(Manifest.permission.ACCESS_MEDIA_LOCATION) }
    val manager = remember(revision) { MediaStore.canManageMedia(context) }
    val notifications = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    fun openMediaSettings() {
        error = ""
        try { settings.launch(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}"))) }
        catch (_: Exception) {
            try { settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            catch (e: Exception) { error = "Open Android Settings → Apps → Special app access → Media management apps. ${e.message ?: ""}" }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
        if (repo.has(Manifest.permission.READ_MEDIA_IMAGES) && repo.has(Manifest.permission.READ_MEDIA_VIDEO) && !MediaStore.canManageMedia(context)) openMediaSettings()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(20.dp)) {
            Text("Set up PhotoS", style = MaterialTheme.typography.headlineLarge)
            Text("Allow access once, then manage your gallery.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("1. Photos and original files", style = MaterialTheme.typography.titleMedium)
                Text("${if (full) "✓ Full photo/video access" else repo.accessLabel()}\n${if (originals) "✓ Original metadata access" else "Original metadata access is needed for verified backups"}", style = MaterialTheme.typography.bodyMedium)
                Text("Choose Allow all photos and videos. Original metadata access preserves embedded photo information; it does not track your device location.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { permissions.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS)) }, modifier = Modifier.fillMaxWidth()) { Text(if (full && originals) "Review photo permissions" else "Grant photo permissions") }
                Text("2. Allow photo modifications", style = MaterialTheme.typography.titleMedium)
                Text(if (manager) "✓ Media management enabled" else "Media management not enabled")
                Text("Turn on Allow in Android’s media-management settings to avoid repeated Android prompts for Trash and deletion. PhotoS still confirms your selected action once per batch. This does not authorize access to every file on your phone.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = ::openMediaSettings, modifier = Modifier.fillMaxWidth()) { Text(if (manager) "Manage media setting" else "Enable media management") }
                Text("3. Transfer notifications", style = MaterialTheme.typography.titleMedium)
                Text(if (notifications) "✓ Notifications enabled" else "Notifications not enabled")
                TextButton(onClick = {
                    if (!repo.has(Manifest.permission.POST_NOTIFICATIONS)) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else settings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Notification settings") }
                Text("4. USB drive folder", style = MaterialTheme.typography.titleMedium)
                Text(if (driveCount > 0) "✓ $driveCount saved drive folder(s)" else "Connect your drive and select a folder. You can do this later.")
                OutlinedButton(onClick = onAddDrive, modifier = Modifier.fillMaxWidth()) { Text("Choose USB folder") }
                Text("Each drive needs its own folder permission. If you skip or deny access, you can reopen this setup from Photos → ⋮ → Permissions & setup. Android may still ask if permissions are revoked or your device imposes extra restrictions.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open app settings") }
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(if (full && originals && manager) "Continue to gallery" else "Continue for now") }
        }
    }
}
