package app.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import java.io.File
import java.security.MessageDigest
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.VideoFrameDecoder
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.Options
import coil.size.pxOrElse
import kotlinx.coroutines.CancellationException

/**
 * Grid thumbnail request. Phone photos use Android's own thumbnail cache. Drive photos are made once and kept in
 * this app's cache, so a drive opens with its thumbnails instead of re-reading every photo over USB.
 * [stamp] (the file's modified time) makes a changed file get a new thumbnail.
 */
data class Thumb(val uri: String, val stamp: Long = 0)

private class ThumbFetcher(private val data: Thumb, private val options: Options, private val loader: ImageLoader) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val uri = Uri.parse(data.uri)
        val width = options.size.width.pxOrElse { 384 }.coerceIn(96, 720)
        val height = options.size.height.pxOrElse { 384 }.coerceIn(96, 720)
        val resolver = options.context.contentResolver
        val bitmap = try {
            if (uri.authority == MediaStore.AUTHORITY) resolver.loadThumbnail(uri, android.util.Size(width, height), null)
            else DriveThumbs.get(options.context, data)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (bitmap != null) return DrawableResult(BitmapDrawable(options.context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
        // Provider has no thumbnail support (common for USB videos): fall back to Coil's normal decode path.
        val fallback = loader.components.newFetcher(uri, options, loader) ?: return null
        return fallback.first.fetch()
    }

    class Factory : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher = ThumbFetcher(data, options, imageLoader)
    }
}

private class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options) = "thumb:${data.uri}:${data.stamp}"
}

/** One loader for the whole process so the memory cache survives screen changes and rotation. */
object Images {
    @Volatile private var instance: ImageLoader? = null
    fun loader(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components {
                add(ThumbKeyer())
                add(ThumbFetcher.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .also { Thread { DriveThumbs.trim(context.applicationContext) }.apply { priority = Thread.MIN_PRIORITY }.start() }
            .memoryCache { MemoryCache.Builder(context.applicationContext).maxSizePercent(0.25).build() }
            .crossfade(120)
            .build().also { instance = it }
    }
}

/** Thumbnails of drive files, saved as small JPEGs in the app's cache folder. */
object DriveThumbs {
    private const val SIZE = 512
    private const val LIMIT = 300L * 1024 * 1024
    private fun dir(context: Context) = File(context.cacheDir, "drive-thumbs").apply { mkdirs() }
    private fun file(context: Context, thumb: Thumb): File {
        val name = MessageDigest.getInstance("SHA-1").digest("${thumb.uri}|${thumb.stamp}".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(context), "$name.jpg")
    }

    fun get(context: Context, thumb: Thumb): Bitmap? {
        val cached = file(context, thumb)
        if (cached.length() > 0) BitmapFactory.decodeFile(cached.path)?.let { cached.setLastModified(System.currentTimeMillis()); return it }
        // The drive's own thumbnail pack (written by backups or the Windows app): about 40 KB instead of the whole photo.
        DriveCatalogs.thumbFor(thumb.uri, null)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                runCatching { val tmp = File(cached.path + ".tmp"); tmp.writeBytes(bytes); tmp.renameTo(cached) }
                return bitmap
            }
        }
        val bitmap = make(context, Uri.parse(thumb.uri)) ?: return null
        runCatching {
            val tmp = File(cached.path + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(cached)
        }
        return bitmap
    }

    /** Provider thumbnail if the drive offers one, else a single scaled decode of the file (or a video frame). */
    private fun make(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        runCatching { DocumentsContract.getDocumentThumbnail(resolver, uri, Point(SIZE, SIZE), null) }.getOrNull()?.let { return it }
        val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
        if (!mime.startsWith("video/")) runCatching {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > SIZE) decoder.setTargetSampleSize(Integer.highestOneBit(longest / SIZE).coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        return runCatching {
            MediaMetadataRetriever().run { try { setDataSource(context, uri); getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE) } finally { release() } }
        }.getOrNull()
    }

    class Indexed(val hash: String, val jpeg: ByteArray?, val date: Long)
    /**
     * Fingerprint, thumbnail and capture date of a drive file for the drive index. Photos up to 64 MB are read once
     * (all three come from the same bytes); videos are streamed for the fingerprint and sampled for a frame.
     */
    fun index(context: Context, uri: Uri, mime: String, size: Long, knownHash: String?): Indexed {
        val resolver = context.contentResolver
        if (!mime.startsWith("video/") && size in 1..(64L * 1024 * 1024)) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read file")
            val hash = knownHash ?: Integrity.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > SIZE) decoder.setTargetSampleSize(Integer.highestOneBit(longest / SIZE).coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
            val date = runCatching { exifDate(android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))) }.getOrNull() ?: 0L
            return Indexed(hash, bitmap?.let(DriveCatalog::jpeg), date)
        }
        val hash = knownHash ?: resolver.openInputStream(uri)?.use { Integrity.digest(it) } ?: error("Cannot read file")
        var date = 0L
        val frame = if (mime.startsWith("video/")) runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    date = videoDate(extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                    getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE, SIZE)
                } finally { release() }
            }
        }.getOrNull() else make(context, uri)
        return Indexed(hash, frame?.let(DriveCatalog::jpeg), date)
    }
    private fun exifDate(exif: android.media.ExifInterface): Long? {
        val text = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(android.media.ExifInterface.TAG_DATETIME) ?: return null
        return runCatching { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(text.trim())?.time }.getOrNull()?.takeIf { it > 0 }
    }
    /** MediaMetadataRetriever dates look like 20230704T090000.000Z; 1904 means "not set". */
    private fun videoDate(text: String?): Long {
        if (text.isNullOrBlank() || text.startsWith("1904")) return 0
        return runCatching { java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(text)?.time }.getOrNull() ?: 0
    }

    /** Keeps the cache under its limit, removing the least recently used thumbnails first. */
    fun trim(context: Context) {
        runCatching {
            val files = dir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) { if (total <= LIMIT) break; total -= f.length(); f.delete() }
        }
    }
}
