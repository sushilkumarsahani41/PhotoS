package app.photos
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class VaultCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    @Test fun roundTripAndRandomNonces() {
        val bytes = "private photo".toByteArray()
        val a = VaultCrypto.seal(key, bytes, "catalog")
        val b = VaultCrypto.seal(key, bytes, "catalog")
        assertFalse(a.contentEquals(b)); assertArrayEquals(bytes, VaultCrypto.unseal(key, a, "catalog"))
    }
    @Test fun wrongKeyTamperAndWrongPurposeRejected() {
        val sealed = VaultCrypto.seal(key, ByteArray(100), "file1")
        assertTrue(runCatching { VaultCrypto.unseal(ByteArray(32), sealed, "file1") }.isFailure)
        assertTrue(runCatching { VaultCrypto.unseal(key, sealed, "file2") }.isFailure)
        sealed[30] = (sealed[30].toInt() xor 1).toByte()
        assertTrue(runCatching { VaultCrypto.unseal(key, sealed, "file1") }.isFailure)
    }
    @Test fun streamedVideoRoundTripAndTruncation() {
        val plain = ByteArray(2 * 1024 * 1024 + 19) { (it % 251).toByte() }
        val encrypted = ByteArrayOutputStream()
        val hash = VaultCrypto.encrypt(plain.inputStream(), encrypted, key, "id") {}
        val decoded = ByteArrayOutputStream()
        assertEquals(hash, VaultCrypto.decrypt(encrypted.toByteArray().inputStream(), decoded, key, "id") {})
        assertArrayEquals(plain, decoded.toByteArray())
        val truncated = encrypted.toByteArray().dropLast(32).toByteArray()
        assertTrue(runCatching { VaultCrypto.decrypt(truncated.inputStream(), null, key, "id") {} }.isFailure)
        assertTrue(runCatching { VaultCrypto.decrypt((encrypted.toByteArray() + byteArrayOf(1)).inputStream(), null, key, "id") {} }.isFailure)
    }
    @Test fun passwordsAndSaltsChangeDerivedKey() {
        val a = VaultCrypto.derive("test password".toCharArray(), ByteArray(32))
        assertArrayEquals(a, VaultCrypto.derive("test password".toCharArray(), ByteArray(32)))
        assertFalse(a.contentEquals(VaultCrypto.derive("wrong password".toCharArray(), ByteArray(32))))
        assertFalse(a.contentEquals(VaultCrypto.derive("test password".toCharArray(), ByteArray(32) { 1 })))
    }
}
