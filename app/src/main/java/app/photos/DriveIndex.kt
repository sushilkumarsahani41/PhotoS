package app.photos

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/**
 * Last known listing of each drive, kept on the phone so a drive opens instantly while a fresh scan runs in the background.
 * Only names, sizes and dates are stored; no photo content.
 */
class DriveIndex(context: Context) {
    private val dir = File(context.filesDir, "drive-index").apply { mkdirs() }
    private fun file(drive: String) = AtomicFile(File(dir, MessageDigest.getInstance("SHA-1").digest(drive.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"))

    fun load(drive: String): List<MediaEntry>? = runCatching {
        val rows = JSONArray(file(drive).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        List(rows.length()) { i -> rows.getJSONArray(i).let { r -> MediaEntry(r.getString(0), r.getString(1), r.getString(2), r.getLong(3), r.getLong(4), r.getString(5)) } }
    }.getOrNull()

    fun save(drive: String, items: List<MediaEntry>) {
        runCatching {
            val rows = JSONArray()
            items.forEach { rows.put(JSONArray().put(it.uri).put(it.name).put(it.mime).put(it.size).put(it.date).put(it.folder)) }
            val target = file(drive)
            val out = target.startWrite()
            try { out.write(rows.toString().toByteArray()); target.finishWrite(out) } catch (e: Exception) { target.failWrite(out); throw e }
        }
    }
}
