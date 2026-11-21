package com.raza.vid2doc

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

data class Progress(val stage: String, val fraction: Float? = null)
data class ConvertResult(val uri: Uri, val pages: Int)

/**
 * Same idea as youtube_to_pdf.py:
 *  1. download the video (up to 1080p)
 *  2. sample a frame every INTERVAL_SEC seconds
 *  3. keep a frame only if >= THRESHOLD_PCT % of pixels changed vs the last kept one
 *  4. put the kept frames in a PDF, one per page, at full video resolution
 */
object Converter {
    private const val INTERVAL_SEC = 2
    private const val THRESHOLD_PCT = 8.0
    private const val PIXEL_DELTA = 25        // brightness change that counts as "different"
    private const val JPEG_QUALITY = 95
    private const val CMP_W = 160
    private const val CMP_H = 90

    // Prefer H.264 (decodes on every phone), up to 1080p. Video-only: audio isn't needed.
    private const val FORMAT =
        "bv*[height<=1080][vcodec^=avc1]/bv*[height<=1080][ext=mp4]/bv*[height<=1080]/b[height<=1080]/b"

    private class Kept(val file: File, val w: Int, val h: Int)

    suspend fun convert(
        ctx: Context,
        url: String,
        processId: String,
        report: (Progress) -> Unit
    ): ConvertResult = withContext(Dispatchers.IO) {
        report(Progress("Starting engine…"))
        Engine.ready(ctx)

        val work = File(ctx.cacheDir, "work-$processId").apply { deleteRecursively(); mkdirs() }
        var pdfUri: Uri? = null
        try {
            report(Progress("Reading video info…"))
            val info = YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
            })
            val title = info.title?.takeIf { it.isNotBlank() } ?: "video"

            // ---- 1. download ----
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("-f", FORMAT)
                addOption("-o", File(work, "video.%(ext)s").absolutePath)
            }
            YoutubeDL.getInstance().execute(req, processId) { p, _, _ ->
                if (p >= 0f) report(Progress("Downloading… ${p.toInt()}%", (p / 100f).coerceIn(0f, 1f)))
            }
            val video = work.listFiles { f ->
                f.name.startsWith("video.") && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl")
            }?.maxByOrNull { it.length() } ?: error("Download failed")

            // ---- 2 + 3. find slides ----
            val frames = ArrayList<Kept>()
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(video.absolutePath)
                val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: error("Can't read video length")
                var prev: IntArray? = null
                var t = 0L
                while (t <= durMs) {
                    ensureActive()
                    val small = mmr.getScaledFrameAtTime(
                        t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, CMP_W, CMP_H
                    )
                    if (small != null) {
                        val g = gray(small)
                        small.recycle()
                        if (prev == null || changedPct(prev, g) >= THRESHOLD_PCT) {
                            val full = mmr.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                            if (full != null) {
                                val f = File(work, "f%05d.jpg".format(frames.size))
                                f.outputStream().use { full.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                                frames.add(Kept(f, full.width, full.height))
                                full.recycle()
                                prev = g
                            }
                        }
                    }
                    t += INTERVAL_SEC * 1000L
                    report(
                        Progress(
                            "Finding slides… ${frames.size} pages",
                            (t.toFloat() / durMs).coerceIn(0f, 1f)
                        )
                    )
                }
            } finally {
                mmr.release()
            }
            if (frames.isEmpty()) error("No frames could be read from this video")

            // ---- 4. write PDF into Downloads/Vid2Doc ----
            report(Progress("Saving PDF…"))
            val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifBlank { "video" }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$safe.pdf")
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Vid2Doc")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Can't create the PDF file")
            pdfUri = uri
            resolver.openOutputStream(uri)!!.buffered().use { os ->
                val pdf = PdfWriter(os)
                frames.forEach { pdf.addJpeg(it.file.readBytes(), it.w, it.h) }
                pdf.finish()
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            val done = ConvertResult(uri, frames.size)
            pdfUri = null // success: don't delete in finally-catch below
            done
        } catch (e: Throwable) {
            pdfUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            throw e
        } finally {
            work.deleteRecursively()
        }
    }

    private fun gray(b: Bitmap): IntArray {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
        }
        return px
    }

    private fun changedPct(a: IntArray, b: IntArray): Double {
        if (a.size != b.size) return 100.0
        var n = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > PIXEL_DELTA) n++
        return n * 100.0 / a.size
    }
}
