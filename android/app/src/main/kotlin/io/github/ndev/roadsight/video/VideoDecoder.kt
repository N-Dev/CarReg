package io.github.ndev.roadsight.video

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import io.github.ndev.roadsight.core.image.Yuv
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** What a video is: length, upright size, frame rate and when it was recorded (if the file says). */
class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val fps: Double?,
    val recorded: Long?,
    val mime: String,
) {
    val aspect: Double get() = height.toDouble() / width
}

/**
 * Reads a video's frames with the phone's own decoder (MediaCodec), as upright bitmaps no bigger than
 * asked, for the same AI as the camera. Nothing is written anywhere.
 */
class VideoDecoder(private val context: Context, private val uri: Uri) {
    companion object {
        // "20260929T071000.000Z"
        private fun parseDate(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            for (p in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
                val t = runCatching {
                    SimpleDateFormat(p, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time
                }.getOrNull()
                // Some phones write 1904-01-01 (no date).
                if (t != null && t > 946_684_800_000L) return t
            }
            return null
        }
    }

    /** Length, size and date, from the file's metadata and its video track. */
    fun info(): VideoInfo {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val date = parseDate(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            val (fps, mime) = track().let { (ex, fmt) ->
                ex.release()
                (if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull() else null) to
                    (fmt.getString(MediaFormat.KEY_MIME) ?: "video/?")
            }
            val upright = rot == 90 || rot == 270
            require(w > 0 && h > 0) { "That file has no video in it" }
            return VideoInfo(dur, if (upright) h else w, if (upright) w else h, rot, fps, date, mime)
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun track(): Pair<MediaExtractor, MediaFormat> {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                ex.selectTrack(i)
                return ex to f
            }
        }
        ex.release()
        throw IllegalArgumentException("That file has no video in it")
    }

    /**
     * Decodes the video from the start and calls `onFrame` with frames at least `stepMs` apart (by the
     * video's own clock, from 0), as upright bitmaps whose longer side is at most about `maxSide`. The
     * bitmap is reused from frame to frame: copy what needs keeping. `onFrame` returns false to stop.
     */
    fun frames(maxSide: Int, stepMs: Double, onFrame: (Bitmap, Double) -> Boolean) {
        val (ex, fmt) = track()
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else info().rotation
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            // Flexible YUV, so every decoder's frames can be read the same way (getOutputImage).
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val conv = Converter()
            var inputDone = false
            var first = -1L
            var next = 0.0
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    idle = 0
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var keepGoing = true
                    if (info.size > 0 || !end) {
                        if (first < 0) first = info.presentationTimeUs
                        val t = (info.presentationTimeUs - first) / 1000.0
                        if (t >= next) {
                            val img = codec.getOutputImage(o)
                            if (img != null) {
                                val bmp = try {
                                    conv.convert(img, rotation, maxSide)
                                } finally {
                                    img.close()
                                }
                                keepGoing = onFrame(bmp, t)
                                next = t + stepMs - 1
                            }
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (end || !keepGoing) break
                } else if (o == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // Some decoders never flag the end: give up after a few seconds of nothing.
                    if (++idle > 300) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { ex.release() }
        }
    }

    /** One upright frame near the start (for setting the lines). */
    fun still(maxSide: Int, atMs: Double = 500.0): Bitmap? {
        var out: Bitmap? = null
        frames(maxSide, 1.0) { b, t ->
            out = b.copy(Bitmap.Config.ARGB_8888, false)
            t < atMs
        }
        return out
    }

    /** Turns the decoder's YUV frames into one reused ARGB bitmap. */
    private class Converter {
        private var y = ByteArray(0)
        private var u = ByteArray(0)
        private var v = ByteArray(0)
        private var px = IntArray(0)
        private var bitmap: Bitmap? = null

        private fun copy(b: java.nio.ByteBuffer, into: ByteArray): ByteArray {
            val n = b.remaining()
            val a = if (into.size >= n) into else ByteArray(n)
            b.get(a, 0, n)
            return a
        }

        fun convert(img: Image, rotation: Int, maxSide: Int): Bitmap {
            val crop = img.cropRect
            // Even sizes: chroma comes in 2 x 2 blocks.
            val w = crop.width() and 1.inv()
            val h = crop.height() and 1.inv()
            val f = Yuv.factorFor(w, h, maxSide)
            val p = img.planes
            y = copy(p[0].buffer, y)
            u = copy(p[1].buffer, u)
            v = copy(p[2].buffer, v)
            val (ow, oh) = Yuv.outSize(w, h, f, rotation)
            if (px.size < ow * oh) px = IntArray(ow * oh)
            Yuv.toArgb(
                Yuv.Plane(y, p[0].rowStride, p[0].pixelStride),
                Yuv.Plane(u, p[1].rowStride, p[1].pixelStride),
                Yuv.Plane(v, p[2].rowStride, p[2].pixelStride),
                crop.left, crop.top, w, h, f, rotation, hd = minOf(w, h) >= 720, out = px,
            )
            var b = bitmap
            if (b == null || b.width != ow || b.height != oh) {
                b = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                bitmap = b
            }
            b!!.setPixels(px, 0, ow, 0, 0, ow, oh)
            return b
        }
    }
}
