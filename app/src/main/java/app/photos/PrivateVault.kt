package app.photos

import android.app.Activity
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateVault(importItems: List<MediaEntry>, hidden: Boolean, onHidden: (Boolean) -> Unit, onChanged: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { VaultRepository(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf<ByteArray?>(null) }
    var entries by remember { mutableStateOf<List<VaultEntry>>(emptyList()) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var exists by remember { mutableStateOf(repo.exists()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var moveConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<VaultEntry?>(null) }
    var restoreConfirm by remember { mutableStateOf<VaultEntry?>(null) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var settings by remember { mutableStateOf(false) }
    var usePin by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    var imported by remember { mutableStateOf(false) }
    val guard = remember { VaultGuard(context) }
    var kind by remember { mutableStateOf(if (repo.exists()) repo.kind() else SecretKind.PIN) }
    val bioAvailable = remember { VaultKeys.biometricAvailable(context) }
    var bioOn by remember { mutableStateOf(repo.biometricEnabled()) }
    var wait by remember { mutableLongStateOf(guard.waitMillis()) }
    var changing by remember { mutableStateOf(false) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    LaunchedEffect(wait) { if (wait > 0) { delay(1000); wait = guard.waitMillis() } }
    fun lock() {
        signal?.cancel(); signal = null; changing = false
        job?.cancel(); key?.fill(0); key = null; entries = emptyList()
        viewing = null; settings = false; usePin = false; thumbs.clear(); password = ""; repeat = ""; busy = false
        moveConfirm = false; deleteConfirm = null; restoreConfirm = null; message = ""
    }
    val removal = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        DeletionManager(context).finishPhoneRequest(it.resultCode == Activity.RESULT_OK)
        message = if (it.resultCode == Activity.RESULT_OK) "Phone originals removed. Encrypted vault copies are saved." else "Encrypted copies saved, but phone originals were kept."
        onChanged()
    }
    fun operation(block: suspend (ByteArray) -> Unit) {
        val copy = key?.copyOf() ?: return
        busy = true
        job = scope.launch {
            try { block(copy) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Operation failed. Phone originals were kept." }
            finally { copy.fill(0); busy = false }
        }
    }
    fun open(failure: String, counts: Boolean, unlock: suspend () -> ByteArray) {
        busy = true; message = ""
        job = scope.launch {
            var opened: ByteArray? = null
            try {
                opened = withContext(Dispatchers.IO) { unlock() }
                val contents = withContext(Dispatchers.IO) { repo.entries(opened!!) }
                key = opened; opened = null; entries = contents; exists = true
                guard.reset(); wait = 0; bioOn = repo.biometricEnabled()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { exists = repo.exists(); if (counts) { guard.failed(); wait = guard.waitMillis() }; message = failure }
            finally { opened?.fill(0); busy = false }
        }
    }
    fun fingerprint() {
        if (busy || signal != null || !bioAvailable) return
        val cipher = repo.biometricCipher() ?: run {
            bioOn = false; message = "Fingerprint unlock was turned off because the phone's fingerprints or screen lock changed. Unlock with your ${kind.noun}, then turn it on again."; return
        }
        signal = VaultKeys.prompt(context, "Unlock private vault", "Use ${kind.label}", cipher) { unlocked, error ->
            signal = null
            if (unlocked != null) open("Fingerprint unlock failed. Use your ${kind.noun}.", false) { repo.unlockBiometric(unlocked) }
            else { usePin = true; if (error != null) message = error }
        }
    }
    fun enableFingerprint() {
        val cipher = try { VaultKeys.biometricEncrypt() } catch (e: Exception) { message = "Fingerprint unlock is unavailable on this phone."; return }
        signal = VaultKeys.prompt(context, "Turn on fingerprint unlock", "Cancel", cipher) { unlocked, error ->
            signal = null
            if (unlocked != null) operation { k -> withContext(Dispatchers.IO) { repo.enableBiometric(unlocked, k) }; bioOn = true; message = "Fingerprint unlock is on." }
            else { VaultKeys.removeBiometric(); error?.let { message = it } }
        }
    }
    // Fingerprint is the default: ask for it on opening and whenever the app returns while locked.
    LaunchedEffect(resumes) { if (resumes > 0 && key == null && exists && bioOn && !usePin) fingerprint() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) lock()
            if (event == Lifecycle.Event.ON_RESUME) resumes++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); lock() }
    }
    fun withKey(): ByteArray? = key?.copyOf()
    Dialog(onDismissRequest = { lock(); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        val unlocked = key != null
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(horizontal = if (unlocked) 4.dp else 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.padding(start = if (unlocked) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Private vault", style = MaterialTheme.typography.headlineSmall)
                    if (unlocked) Text("${entries.size} encrypted ${if (entries.size == 1) "item" else "items"}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
                if (unlocked) {
                    IconButton(onClick = ::lock) { Icon(Icons.Filled.Lock, "Lock vault") }
                    IconButton(onClick = { settings = true }, enabled = !busy) { Icon(Icons.Filled.Settings, "Vault settings") }
                }
                IconButton(onClick = { lock(); onClose() }) { Icon(Icons.Filled.Close, "Close") }
            }
            if (!unlocked && exists && bioOn && bioAvailable && !usePin) {
                // Fingerprint-first screen; the PIN/password form is one tap away.
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(onClick = ::fingerprint, enabled = !busy, shape = CircleShape, color = Palette.CardHigh, modifier = Modifier.size(112.dp)) {
                        Box(contentAlignment = Alignment.Center) { Glyph(R.drawable.ic_fingerprint, "Unlock with fingerprint", Modifier.size(56.dp), Color.White) }
                    }
                    Text("Touch the sensor to unlock", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { signal?.cancel(); signal = null; usePin = true }) { Text("Use ${kind.label} instead") }
                }
                Spacer(Modifier.weight(1f))
            } else if (!unlocked) {
                val noun = kind.noun
                Text(if (exists) "Unlock with your $noun" else "Protect your vault with a PIN or password")
                Text("Encrypted on this phone. No recovery if you forget it. Uninstalling the app or clearing its data deletes the vault. Vault files are not included in USB backups.", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                if (!exists) KindChooser(kind, !busy) { kind = it; password = ""; repeat = ""; message = "" }
                val ready = !busy && wait == 0L
                SecretField(password, { password = it }, if (exists) kind.label else "New $noun", kind, ready)
                if (!exists) {
                    SecretField(repeat, { repeat = it }, "Repeat $noun", kind, ready)
                    Text(kind.rule, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
                if (wait > 0) Text("Too many wrong attempts. Try again in ${(wait + 999) / 1000} s.", color = Palette.Warning)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = ready && password.isNotEmpty(), onClick = {
                        val chars = password.toCharArray()
                        if (!exists && (!kind.valid(chars) || password != repeat)) { chars.fill('\u0000'); message = "${kind.rule} Both entries must match." }
                        else {
                            password = ""; repeat = ""
                            val creating = !exists; val chosen = kind
                            open(if (creating) "Could not create the vault." else "Wrong $noun. The vault may also be damaged.", !creating) {
                                try { if (creating) repo.create(chars, chosen) else repo.unlock(chars) } finally { chars.fill('\u0000') }
                            }
                        }
                    }) { Text(if (exists) "Unlock" else "Create vault") }
                    if (exists && bioOn && bioAvailable) OutlinedButton(enabled = !busy, onClick = { usePin = false; fingerprint() }) {
                        Glyph(R.drawable.ic_fingerprint, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Use fingerprint")
                    }
                }
            } else {
                if (importItems.isNotEmpty() && !imported) Button(onClick = { moveConfirm = true }, enabled = !busy && importItems.size <= 500, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Move ${importItems.size} selected into vault") }
                if (entries.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No private photos yet.\nSelect photos in the gallery and long-press Back up,\nor long-press the heart while viewing one.", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                else VaultGrid(entries, thumbs, loadThumb = { entry -> withKey()?.let { k -> try { repo.thumbnail(k, entry) } finally { k.fill(0) } } }, Modifier.weight(1f)) { viewing = it }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message, Modifier.padding(horizontal = if (unlocked) 12.dp else 0.dp))
        }
        if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = Palette.Surface, properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
            // Only someone who has unlocked the vault can hide or reveal it, or change how it unlocks.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Vault settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                SettingRow("Unlock with fingerprint", if (bioAvailable) "Asked for first every time the vault opens. Your ${kind.noun} still works. Adding a new fingerprint to the phone turns this off." else "Add a fingerprint or face unlock in phone Settings first.", bioOn, !busy && (bioAvailable || bioOn)) { on ->
                    if (on) enableFingerprint() else { repo.disableBiometric(); bioOn = false; message = "Fingerprint unlock is off." }
                }
                SettingRow("Hide private vault", if (hidden) "Hidden. Open it by long-pressing the Photos title. To move photos in, select them and long-press Back up, or long-press the heart while viewing a photo." else "Removes every vault button and menu item from the app.", hidden, !busy) { onHidden(it) }
                TextButton(enabled = !busy, onClick = { settings = false; changing = true }) { Text("Change PIN or password") }
            }
        }
    }
    viewing?.let { start ->
        VaultViewer(entries, start, thumbs,
            loadPhoto = { entry -> withKey()?.let { k -> try { repo.photo(k, entry) } finally { k.fill(0) } } },
            video = { entry -> VaultDataSource.Factory { val k = withKey() ?: error("Vault is locked"); try { repo.reader(k, entry) } finally { k.fill(0) } } },
            busy = busy, onRestore = { restoreConfirm = it }, onDelete = { deleteConfirm = it }, onClose = { viewing = null })
    }
    if (moveConfirm) AlertDialog(onDismissRequest = { moveConfirm = false }, title = { Text("Move into private vault?") }, text = { Text("Encrypted copies will be verified before the phone originals are permanently removed. Existing USB, cloud and other copies remain visible separately. Keep this screen open until encryption finishes.") }, confirmButton = {
        TextButton(onClick = {
            moveConfirm = false
            operation { k ->
                val hashes = withContext(Dispatchers.IO) { repo.import(k, importItems) {} }
                withContext(Dispatchers.IO) { repo.verifyOriginals(hashes) }
                entries = withContext(Dispatchers.IO) { repo.entries(k) }; imported = true
                message = "Encrypted copies verified. Requesting removal of phone originals."
                val request = MediaStore.createDeleteRequest(context.contentResolver, importItems.map { Uri.parse(it.uri) })
                DeletionManager(context).beginPhoneRequest(importItems.map { it.uri })
                removal.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
        }) { Text("Move into vault") }
    }, dismissButton = { TextButton(onClick = { moveConfirm = false }) { Text("Cancel") } }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn))
    if (changing) ChangeSecret(kind, onDismiss = { changing = false }) { chars, chosen ->
        changing = false
        operation { k -> try { withContext(Dispatchers.IO) { repo.changeSecret(k, chars, chosen) } } finally { chars.fill('\u0000') }; kind = chosen; message = "${chosen.label} changed." }
    }
    restoreConfirm?.let { entry -> AlertDialog(onDismissRequest = { restoreConfirm = null }, title = { Text("Restore to phone gallery?") }, text = { Text("A decrypted copy will become visible in your phone gallery. The encrypted vault copy stays here.") }, confirmButton = { TextButton(onClick = { restoreConfirm = null; operation { k -> withContext(Dispatchers.IO) { repo.restore(k, entry) }; message = "Restored to phone gallery."; onChanged() } }) { Text("Restore") } }, dismissButton = { TextButton(onClick = { restoreConfirm = null }) { Text("Cancel") } }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) }
    deleteConfirm?.let { entry -> AlertDialog(onDismissRequest = { deleteConfirm = null }, title = { Text("Permanently delete vault copy?") }, text = { Text("This encrypted copy cannot be recovered from Recently Deleted. Other copies stay separate.") }, confirmButton = { TextButton(onClick = { deleteConfirm = null; operation { k -> entries = withContext(Dispatchers.IO) { repo.delete(k, entry); repo.entries(k) }; thumbs.remove(entry.id); message = "Vault copy deleted." } }) { Text("Delete permanently") } }, dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("Cancel") } }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) }
}

@Composable
private fun KindChooser(kind: SecretKind, enabled: Boolean, onKind: (SecretKind) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SecretKind.entries.forEachIndexed { i, option ->
            SegmentedButton(selected = kind == option, onClick = { onKind(option) }, shape = SegmentedButtonDefaults.itemShape(i, SecretKind.entries.size), enabled = enabled) { Text(option.label) }
        }
    }
}

@Composable
private fun SecretField(value: String, onValue: (String) -> Unit, label: String, kind: SecretKind, enabled: Boolean) {
    val pin = kind == SecretKind.PIN
    OutlinedTextField(value, { onValue(if (pin) it.filter(Char::isDigit).take(16) else it) }, Modifier.fillMaxWidth(), label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (pin) KeyboardType.NumberPassword else KeyboardType.Password, autoCorrectEnabled = false))
}

@Composable
private fun SettingRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(color = Palette.Card, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

@Composable
private fun ChangeSecret(current: SecretKind, onDismiss: () -> Unit, onSave: (CharArray, SecretKind) -> Unit) {
    var kind by remember { mutableStateOf(current) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val noun = kind.noun
    val valid = kind.valid(first.toCharArray()) && first == second
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Change PIN or password") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KindChooser(kind, true) { kind = it; first = ""; second = "" }
            SecretField(first, { first = it }, "New $noun", kind, true)
            SecretField(second, { second = it }, "Repeat $noun", kind, true)
            Text(kind.rule, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { val chars = first.toCharArray(); first = ""; second = ""; onSave(chars, kind) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn))
}
