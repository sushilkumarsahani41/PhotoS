package app.photos

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Random access to a vault file without writing decrypted data anywhere.
 * The file is a 4-byte header followed by 64 KiB frames, each sealed separately and bound to its index,
 * so any frame can be decrypted on its own. Plain size is derived from the file length.
 */
class VaultReader(file: File, key: ByteArray, private val id: String) : java.io.Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val key = key.copyOf()
    private var cachedIndex = -1L
    private var cached = ByteArray(0)
    val length: Long

    init {
        require(raf.readInt() == 0x44563101) { "Invalid vault file" }
        // Every data frame is 4 + 12 + data + 16 bytes; the trailing empty frame is 4 + 28.
        val rest = raf.length() - 4 - (4 + 28)
        require(rest >= 0) { "Invalid vault file" }
        val full = rest / FRAME_ON_DISK
        val partial = rest % FRAME_ON_DISK
        length = full * FRAME + if (partial > 0) partial - 32 else 0
    }

    private fun frame(index: Long): ByteArray {
        if (index == cachedIndex) return cached
        raf.seek(4 + index * FRAME_ON_DISK)
        val size = raf.readInt()
        require(size in 28..FRAME + 28) { "Invalid encrypted frame" }
        val sealed = ByteArray(size).also(raf::readFully)
        cached.fill(0)
        cached = VaultCrypto.unseal(key, sealed, "$id:$index"); cachedIndex = index
        return cached
    }

    @Synchronized fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        var done = 0
        var pos = position
        while (done < size && pos < length) {
            val plain = frame(pos / FRAME)
            val inFrame = (pos % FRAME).toInt()
            val n = minOf(size - done, plain.size - inFrame)
            if (n <= 0) break
            System.arraycopy(plain, inFrame, buffer, offset + done, n)
            done += n; pos += n
        }
        return done
    }
    fun readAll(): ByteArray {
        require(length <= Int.MAX_VALUE - 8) { "File too large" }
        val out = ByteArray(length.toInt()); var pos = 0
        while (pos < out.size) { val n = readAt(pos.toLong(), out, pos, out.size - pos); if (n <= 0) break; pos += n }
        return out
    }
    /** For Android's media framework (video thumbnails). */
    fun asMediaSource() = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = this@VaultReader.readAt(position, buffer, offset, size)
        override fun getSize() = length
        override fun close() {}
    }
    @Synchronized override fun close() { cached.fill(0); key.fill(0); runCatching { raf.close() } }

    companion object { const val FRAME = 65536; const val FRAME_ON_DISK = 4 + 12 + FRAME + 16L }
}

/** Decodes a photo held only in memory, honouring EXIF rotation, scaled so its longest side is at most [max]. */
fun decodeImage(bytes: ByteArray, max: Int): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > max) decoder.setTargetSampleSize(Integer.highestOneBit((longest / max).coerceAtLeast(1)))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

fun videoFrame(source: MediaDataSource): Bitmap? = runCatching {
    MediaMetadataRetriever().run {
        try { setDataSource(source); getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512) } finally { release() }
    }
}.getOrNull()

/** Feeds an encrypted vault video to ExoPlayer, decrypting only the frames being played. */
@OptIn(UnstableApi::class)
class VaultDataSource(private val open: () -> VaultReader) : BaseDataSource(false) {
    private var reader: VaultReader? = null
    private var position = 0L
    private var remaining = 0L
    private var uri: Uri? = null
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val r = open().also { reader = it }
        uri = dataSpec.uri; position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.length - position
        transferStarted(dataSpec)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val n = reader!!.readAt(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n; remaining -= n; bytesTransferred(n)
        return n
    }
    override fun getUri() = uri
    override fun close() { reader?.close(); reader = null; if (uri != null) { uri = null; transferEnded() } }
    class Factory(private val open: () -> VaultReader) : DataSource.Factory { override fun createDataSource() = VaultDataSource(open) }
}
