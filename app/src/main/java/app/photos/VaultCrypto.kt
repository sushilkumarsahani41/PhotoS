package app.photos

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object VaultCrypto {
    const val ITERATIONS = 600_000
    private val random = SecureRandom()
    fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)
    fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, purpose: String) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(purpose.toByteArray(Charsets.UTF_8))
    }
    fun seal(key: ByteArray, bytes: ByteArray, purpose: String): ByteArray {
        val iv = randomBytes(12)
        return iv + cipher(Cipher.ENCRYPT_MODE, key, iv, purpose).doFinal(bytes)
    }
    fun unseal(key: ByteArray, bytes: ByteArray, purpose: String): ByteArray {
        require(bytes.size >= 28) { "Invalid encrypted file" }
        return cipher(Cipher.DECRYPT_MODE, key, bytes.copyOfRange(0, 12), purpose).doFinal(bytes, 12, bytes.size - 12)
    }
    // Independently authenticated 64 KiB frames keep large videos bounded in memory.
    // An authenticated empty final frame detects truncation; indices prevent reordering.
    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray, purpose: String, check: () -> Unit): String {
        val target = java.io.DataOutputStream(output)
        target.writeInt(0x44563101)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var index = 0L
        while (true) {
            check()
            var count = 0
            while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; if (n > 0) count += n }
            val sealed = seal(key, buffer.copyOf(count), "$purpose:$index")
            target.writeInt(sealed.size); target.write(sealed)
            digest.update(buffer, 0, count)
            index++
            if (count == 0) break
        }
        buffer.fill(0); target.flush()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun decrypt(input: InputStream, output: OutputStream?, key: ByteArray, purpose: String, check: () -> Unit): String {
        val source = java.io.DataInputStream(input)
        require(source.readInt() == 0x44563101) { "Invalid vault file" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var index = 0L
        while (true) {
            check()
            val size = source.readInt()
            require(size in 28..65564) { "Invalid encrypted frame" }
            val frame = ByteArray(size); source.readFully(frame)
            val plain = unseal(key, frame, "$purpose:$index")
            index++
            try {
                if (plain.isEmpty()) { require(source.read() == -1) { "Unexpected trailing data" }; break }
                digest.update(plain); output?.write(plain)
            } finally { plain.fill(0) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
