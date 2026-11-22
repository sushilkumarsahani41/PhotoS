package app.photos

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class VaultReaderTest {
    private val key = ByteArray(32) { it.toByte() }

    private fun roundTrip(size: Int) {
        val plain = Random(size).nextBytes(size)
        val file = File.createTempFile("vault", ".enc").apply { deleteOnExit() }
        file.outputStream().use { VaultCrypto.encrypt(plain.inputStream(), it, key, "id") {} }
        VaultReader(file, key, "id").use { r ->
            assertEquals("length for $size", size.toLong(), r.length)
            assertArrayEquals(plain, r.readAll())
            if (size > 10) {
                // Random access across a frame boundary.
                val start = (size / 2 - 5).coerceAtLeast(0); val buf = ByteArray(minOf(70_000, size - start))
                var got = 0; while (got < buf.size) { val n = r.readAt((start + got).toLong(), buf, got, buf.size - got); if (n <= 0) break; got += n }
                assertArrayEquals(plain.copyOfRange(start, start + buf.size), buf)
            }
            assertEquals(-1, r.readAt(size.toLong(), ByteArray(4), 0, 4))
        }
    }

    @Test fun lengthsAndRandomAccess() {
        for (size in listOf(0, 1, 1000, 65_535, 65_536, 65_537, 131_072, 200_001)) roundTrip(size)
    }

    @Test fun wrongKeyFails() {
        val file = File.createTempFile("vault", ".enc").apply { deleteOnExit() }
        file.outputStream().use { VaultCrypto.encrypt(ByteArray(100).inputStream(), it, key, "id") {} }
        VaultReader(file, ByteArray(32), "id").use { r -> assertThrows(Exception::class.java) { r.readAll() } }
        VaultReader(file, key, "other").use { r -> assertThrows(Exception::class.java) { r.readAll() } }
    }
}
