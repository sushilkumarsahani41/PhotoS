package app.photos

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object Integrity {
    fun digest(input: InputStream, check: () -> Unit = {}): String = transfer(input, null, check)
    fun transfer(input: InputStream, output: OutputStream?, check: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            check()
            val read = input.read(buffer)
            if (read == -1) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            output?.write(buffer, 0, read)
        }
        output?.flush()
        return hex(digest.digest())
    }
    private val HEX = "0123456789abcdef".toCharArray()
    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b -> val v = b.toInt() and 0xff; out[i * 2] = HEX[v ushr 4]; out[i * 2 + 1] = HEX[v and 0x0f] }
        return String(out)
    }
    /**
     * Drive folder for a phone file, named after the folder it lives in on the phone:
     * "DCIM/Camera/" → "Camera", "Pictures/Screenshots/" → "Screenshots", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/" → "WhatsApp Images".
     */
    fun albumFolder(relativePath: String?): String {
        val last = relativePath.orEmpty().trim('/').substringAfterLast('/').trim()
        return if (last.isEmpty() || last.startsWith(".")) "Other" else safeName(last).take(80)
    }
    /** Capture time from names like IMG_20240512_183005.jpg or Screenshot_2024-05-12-18-30-05 (same rule as the Windows app). */
    fun dateFromName(name: String): Long? {
        val m = Regex("(20\\d\\d|19\\d\\d)[-_.]?(\\d\\d)[-_.]?(\\d\\d)[-_ T.]?(\\d\\d)[-_.:]?(\\d\\d)[-_.:]?(\\d\\d)").find(name) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        if (mo.toInt() !in 1..12 || d.toInt() !in 1..31 || h.toInt() > 23 || mi.toInt() > 59 || s.toInt() > 59) return null
        return java.util.Calendar.getInstance().apply { clear(); set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt()) }.timeInMillis
    }
    fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(140).ifBlank { "Media" }
}
