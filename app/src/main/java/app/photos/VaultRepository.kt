package app.photos

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

enum class SecretKind(val label: String, val rule: String) {
    PIN("PIN", "Use a PIN of at least 6 digits."),
    PASSWORD("Password", "Use a password of at least 10 characters.");
    /** For use mid-sentence: "PIN" stays capitalised, "password" does not. */
    val noun get() = if (this == PIN) "PIN" else "password"
    fun valid(secret: CharArray) = if (this == PIN) secret.size >= 6 && secret.all { it in '0'..'9' } else secret.size >= 10
}

data class VaultEntry(val id: String, val name: String, val mime: String, val hash: String, val size: Long, val date: Long)
class VaultRepository(private val context: Context) {
    private val directory = File(context.filesDir, "private-vault").apply { mkdirs() }
    private val config = AtomicFile(File(directory, "key.json"))
    private val catalog = AtomicFile(File(directory, "catalog.enc"))
    fun exists() = config.baseFile.exists() || File(directory, "key.json.bak").exists()
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    private fun atomic(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    private fun readConfig() = JSONObject(config.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    /** PIN or password. Stored in plain text so the unlock screen can show the right keyboard. Version 1 vaults used a password. */
    fun kind(): SecretKind = runCatching { if (readConfig().optString("kind") == "pin") SecretKind.PIN else SecretKind.PASSWORD }.getOrDefault(SecretKind.PASSWORD)
    fun create(secret: CharArray, kind: SecretKind): ByteArray {
        check(!exists()) { "Vault already exists" }
        require(kind.valid(secret)) { kind.rule }
        val master = VaultCrypto.randomBytes(32)
        writeSecret(master, secret, kind, null)
        save(master, emptyList())
        return master
    }
    /** Re-wraps the same master key under a new PIN or password; vault files are untouched. Fingerprint unlock is kept. */
    fun changeSecret(master: ByteArray, secret: CharArray, kind: SecretKind) {
        require(kind.valid(secret)) { kind.rule }
        writeSecret(master, secret, kind, readConfig().optJSONObject("bio"))
    }
    private fun writeSecret(master: ByteArray, secret: CharArray, kind: SecretKind, bio: JSONObject?) {
        val salt = VaultCrypto.randomBytes(32)
        val wrappingKey = VaultCrypto.derive(secret, salt)
        try {
            // Inner layer needs the PIN/password; outer layer needs this phone's Keystore, so a copied file cannot be brute-forced elsewhere.
            val wrapped = VaultKeys.deviceSeal(VaultCrypto.seal(wrappingKey, master, "PhotoS master v2"))
            val json = JSONObject().put("version", 2).put("kind", if (kind == SecretKind.PIN) "pin" else "password")
                .put("iterations", VaultCrypto.ITERATIONS).put("salt", encode(salt)).put("wrapped", encode(wrapped))
            bio?.let { json.put("bio", it) }
            atomic(config, json.toString().toByteArray())
        } finally { wrappingKey.fill(0) }
    }
    fun unlock(secret: CharArray): ByteArray {
        val json = readConfig()
        val version = json.getInt("version")
        require((version == 1 || version == 2) && json.getInt("iterations") == VaultCrypto.ITERATIONS)
        val wrappingKey = VaultCrypto.derive(secret, decode(json.getString("salt")))
        return try {
            if (version == 1) VaultCrypto.unseal(wrappingKey, decode(json.getString("wrapped")), "PhotoS master v1")
            else VaultCrypto.unseal(wrappingKey, VaultKeys.deviceOpen(decode(json.getString("wrapped"))), "PhotoS master v2")
        } finally { wrappingKey.fill(0) }
    }

    fun biometricEnabled() = runCatching { readConfig().has("bio") }.getOrDefault(false)
    /** Cipher to hand to the fingerprint prompt; null (and fingerprint unlock is switched off) if the key was invalidated. */
    fun biometricCipher(): javax.crypto.Cipher? {
        val bio = runCatching { readConfig().optJSONObject("bio") }.getOrNull() ?: return null
        return VaultKeys.biometricDecrypt(decode(bio.getString("iv"))) ?: run { disableBiometric(); null }
    }
    fun unlockBiometric(cipher: javax.crypto.Cipher): ByteArray = cipher.doFinal(decode(readConfig().getJSONObject("bio").getString("wrapped")))
    /** [cipher] must come from [VaultKeys.biometricEncrypt] and have passed the fingerprint prompt. */
    fun enableBiometric(cipher: javax.crypto.Cipher, master: ByteArray) {
        val wrapped = cipher.doFinal(master)
        atomic(config, readConfig().put("bio", JSONObject().put("iv", encode(cipher.iv)).put("wrapped", encode(wrapped))).toString().toByteArray())
    }
    fun disableBiometric() {
        runCatching { val json = readConfig(); if (json.has("bio")) { json.remove("bio"); atomic(config, json.toString().toByteArray()) } }
        VaultKeys.removeBiometric()
    }
    fun entries(key: ByteArray): List<VaultEntry> {
        // Empty catalog can only occur if setup was interrupted before the first import.
        if (!catalog.baseFile.exists() && !File(directory, "catalog.enc.bak").exists()) return emptyList()
        val plain = VaultCrypto.unseal(key, catalog.openRead().use { it.readBytes() }, "PhotoS catalog v1")
        try {
            val a = JSONArray(plain.toString(Charsets.UTF_8))
            return (0 until a.length()).map { a.getJSONObject(it).let { j -> VaultEntry(j.getString("id"), j.getString("name"), j.getString("mime"), j.getString("hash"), j.getLong("size"), j.getLong("date")) } }
        } finally { plain.fill(0) }
    }
    private fun save(key: ByteArray, entries: List<VaultEntry>) {
        val a = JSONArray()
        entries.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("hash", it.hash).put("size", it.size).put("date", it.date)) }
        val plain = a.toString().toByteArray()
        try { atomic(catalog, VaultCrypto.seal(key, plain, "PhotoS catalog v1")) } finally { plain.fill(0) }
    }
    private fun encrypted(entry: VaultEntry): File {
        require(runCatching { UUID.fromString(entry.id).toString() == entry.id }.getOrDefault(false)) { "Invalid vault entry" }
        return File(directory, "${entry.id}.enc")
    }
    suspend fun import(key: ByteArray, items: List<MediaEntry>, progress: (Int) -> Unit): Map<String, String> {
        val job = currentCoroutineContext()
        val entries = entries(key).toMutableList()
        val importedHashes = mutableMapOf<String, String>()
        items.forEachIndexed { index, item ->
            job.ensureActive()
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id.enc")
            var committed = false
            try {
                val uri = MediaStore.setRequireOriginal(Uri.parse(item.uri))
                val hash = context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> VaultCrypto.encrypt(input, output, key, id) { job.ensureActive() }.also { output.fd.sync() } } } ?: error("Cannot read ${item.name}")
                val verified = file.inputStream().use { VaultCrypto.decrypt(it, null, key, id) { job.ensureActive() } }
                check(hash == verified) { "Vault verification failed" }
                job.ensureActive()
                entries.add(VaultEntry(id, item.name, item.mime, hash, item.size, item.date))
                save(key, entries)
                committed = true
                importedHashes[item.uri] = hash
                // The phone's own thumbnail is the cheapest source; the vault can rebuild it later if this fails.
                runCatching { context.contentResolver.loadThumbnail(Uri.parse(item.uri), android.util.Size(512, 512), null).let { saveThumb(key, id, it) } }
                progress(index + 1)
            } finally { if (!committed) file.delete() }
        }
        return importedHashes
    }
    suspend fun verifyOriginals(hashes: Map<String, String>) {
        val job = currentCoroutineContext()
        hashes.forEach { (uri, expected) ->
            val actual = context.contentResolver.openInputStream(MediaStore.setRequireOriginal(Uri.parse(uri)))?.use { Integrity.digest(it) { job.ensureActive() } }
            check(actual == expected) { "A phone original changed or became unavailable. Encrypted copies are saved; phone originals were kept." }
        }
    }
    /** Decrypts on demand; the caller must close it. */
    fun reader(key: ByteArray, entry: VaultEntry) = VaultReader(encrypted(entry), key, entry.id)
    private fun thumb(id: String) = File(directory, "$id.thumb")
    private fun saveThumb(key: ByteArray, id: String, bitmap: android.graphics.Bitmap) {
        val out = ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        val plain = out.toByteArray()
        try { atomic(AtomicFile(thumb(id)), VaultCrypto.seal(key, plain, "$id:thumb")) } finally { plain.fill(0) }
    }
    /** Small encrypted preview for the vault grid. Built once from the encrypted original for items imported before thumbnails existed. */
    fun thumbnail(key: ByteArray, entry: VaultEntry): android.graphics.Bitmap? {
        encrypted(entry) // validates the id
        val file = thumb(entry.id)
        if (file.exists()) runCatching { VaultCrypto.unseal(key, file.readBytes(), "${entry.id}:thumb") }.getOrNull()?.let { plain ->
            try { decodeImage(plain, 512)?.let { return it } } finally { plain.fill(0) }
        }
        val bitmap = reader(key, entry).use { r ->
            if (entry.mime.startsWith("video/")) videoFrame(r.asMediaSource())
            else if (r.length <= 64L * 1024 * 1024) r.readAll().let { try { decodeImage(it, 512) } finally { it.fill(0) } }
            else null
        } ?: return null
        runCatching { saveThumb(key, entry.id, bitmap) }
        return bitmap
    }
    /** Full-size photo for the viewer, decoded in memory only. */
    fun photo(key: ByteArray, entry: VaultEntry): android.graphics.Bitmap? = reader(key, entry).use { r ->
        require(r.length <= 256L * 1024 * 1024) { "Too large to preview. Restore it to view." }
        r.readAll().let { try { decodeImage(it, 4096) } finally { it.fill(0) } }
    }
    suspend fun restore(key: ByteArray, entry: VaultEntry) {
        val video = entry.mime.startsWith("video/")
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, Integrity.safeName(entry.name))
            put(MediaStore.MediaColumns.MIME_TYPE, entry.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/PhotoS Restored/" else "Pictures/PhotoS Restored/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(collection, values) ?: error("Cannot create restored file")
        var published = false
        try {
            val job = currentCoroutineContext()
            val hash = resolver.openOutputStream(target, "wt")!!.use { output -> encrypted(entry).inputStream().use { VaultCrypto.decrypt(it, output, key, entry.id) { job.ensureActive() } } }
            check(hash == entry.hash)
            val verified = resolver.openInputStream(target)!!.use { Integrity.digest(it) { job.ensureActive() } }
            check(verified == entry.hash)
            job.ensureActive()
            check(resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            published = true
        } finally { if (!published) resolver.delete(target, null, null) }
    }
    fun delete(key: ByteArray, entry: VaultEntry) {
        // Keep metadata if file removal fails, so the item remains visible for recovery.
        val file = encrypted(entry)
        check(!file.exists() || file.delete()) { "Could not remove encrypted file" }
        thumb(entry.id).delete()
        save(key, entries(key).filterNot { it.id == entry.id })
    }
}
