package app.photos

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException

class IntegrityTest {
    @Test fun knownSha256Vector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Integrity.digest("abc".byteInputStream()))
    }
    @Test fun streamsLargeFilesWithoutChangingBytes() {
        val bytes = ByteArray(4 * 1024 * 1024 + 19) { (it % 251).toByte() }
        val destination = ByteArrayOutputStream()
        val copied = Integrity.transfer(ByteArrayInputStream(bytes), destination)
        assertArrayEquals(bytes, destination.toByteArray())
        assertEquals(copied, Integrity.digest(destination.toByteArray().inputStream()))
        val corrupt = destination.toByteArray().apply { this[200] = (this[200].toInt() xor 1).toByte() }
        assertNotEquals(copied, Integrity.digest(corrupt.inputStream()))
    }
    @Test(expected = IOException::class) fun writeFailureIsNeverReportedAsSuccess() {
        val fullDrive = object : OutputStream() { override fun write(b: Int) { throw IOException("Disk full") } }
        Integrity.transfer("photo".byteInputStream(), fullDrive)
    }
    @Test(expected = CancellationException::class) fun transferRespondsToCancellationBetweenChunks() {
        Integrity.transfer(ByteArray(1024).inputStream(), ByteArrayOutputStream()) { throw CancellationException() }
    }
    @Test fun safeNamesCannotEscapeDestination() {
        assertEquals(".._folder_photo.jpg", Integrity.safeName("../folder/photo.jpg"))
        assertEquals("Media", Integrity.safeName(""))
        assertTrue(Integrity.safeName("a".repeat(300)).length <= 140)
    }
    @Test fun emptyFileHasCorrectDigest() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Integrity.digest(byteArrayOf().inputStream()))
    }
    @Test fun backupFoldersFollowPhoneAlbums() {
        assertEquals("Camera", Integrity.albumFolder("DCIM/Camera/"))
        assertEquals("Screenshots", Integrity.albumFolder("Pictures/Screenshots/"))
        assertEquals("WhatsApp Images", Integrity.albumFolder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals("Pictures", Integrity.albumFolder("Pictures/"))
        assertEquals("Other", Integrity.albumFolder(""))
        assertEquals("Other", Integrity.albumFolder(null))
        assertEquals("Other", Integrity.albumFolder("DCIM/.thumbnails/"))
        assertEquals("a_b", Integrity.albumFolder("x/a:b/"))
    }
    @Test fun datesFromNamesMatchWindowsApp() {
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int) = java.util.Calendar.getInstance().apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis
        assertEquals(at(2024, 5, 12, 18, 30, 5), Integrity.dateFromName("IMG_20240512_183005.jpg"))
        assertEquals(at(2024, 5, 12, 18, 30, 5), Integrity.dateFromName("Screenshot_2024-05-12-18-30-05-123.png"))
        assertEquals(at(2023, 12, 31, 23, 59, 59), Integrity.dateFromName("PXL_20231231_235959999.mp4"))
        assertNull(Integrity.dateFromName("holiday.jpg"))
        assertNull(Integrity.dateFromName("IMG_20241399_999999.jpg"))
    }
}
