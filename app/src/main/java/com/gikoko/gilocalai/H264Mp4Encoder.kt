package com.gikoko.gilocalai

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Incremental silent H.264 MP4 encoder. Frames are streamed; they are never accumulated in RAM. */
class H264Mp4Encoder(private val width: Int, private val height: Int, private val fps: Int, output: File) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var track = -1
    private var started = false
    private var pts = 0L
    private var count = 0
    private var closed = false

    init {
        require(width % 2 == 0 && height % 2 == 0)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * 0.08).toInt().coerceIn(350_000, 6_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codec.start()
    }

    @Synchronized fun writeFrame(source: Bitmap) {
        check(!closed) { "Encoder already closed" }
        val input = codec.dequeueInputBuffer(10_000)
        check(input >= 0) { "Encoder busy; retry frame" }
        val image = codec.getInputImage(input) ?: error("YUV input image unavailable")
        val scaled = if (source.width != width || source.height != height) Bitmap.createScaledBitmap(source, width, height, true) else source
        try {
            val px = IntArray(width * height)
            scaled.getPixels(px, 0, width, 0, 0, width, height)
            val yp = image.planes[0]; val up = image.planes[1]; val vp = image.planes[2]
            val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
            for (y in 0 until height) for (x in 0 until width) {
                val c = px[y * width + x]
                val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                val yy = (((66*r + 129*g + 25*b + 128) shr 8) + 16).coerceIn(0,255)
                yb.put(y * yp.rowStride + x * yp.pixelStride, yy.toByte())
                if (y % 2 == 0 && x % 2 == 0) {
                    val u = (((-38*r - 74*g + 112*b + 128) shr 8) + 128).coerceIn(0,255)
                    val v = (((112*r - 94*g - 18*b + 128) shr 8) + 128).coerceIn(0,255)
                    ub.put((y/2) * up.rowStride + (x/2) * up.pixelStride, u.toByte())
                    vb.put((y/2) * vp.rowStride + (x/2) * vp.pixelStride, v.toByte())
                }
            }
        } finally {
            image.close()
            if (scaled !== source) scaled.recycle()
        }
        codec.queueInputBuffer(input, 0, width * height * 3 / 2, pts, 0)
        pts += 1_000_000L / fps
        count++
        drain(false)
    }

    private fun drain(end: Boolean) {
        val info = MediaCodec.BufferInfo()
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end || ++waits > 30) return }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started) { "Unexpected second output format" }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); started = true
                }
                index >= 0 -> {
                    val data = codec.getOutputBuffer(index)
                    if (data != null && info.size > 0) {
                        check(started) { "Muxer not started" }
                        data.position(info.offset); data.limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    @Synchronized fun finish() {
        if (closed) return
        val input = codec.dequeueInputBuffer(10_000)
        check(input >= 0) { "Could not signal end of stream" }
        codec.queueInputBuffer(input, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        closed = true
        try { codec.stop() } finally { codec.release() }
        if (started) muxer.stop()
        muxer.release()
        check(count > 0) { "No video frames supplied" }
    }

    @Synchronized fun abort() {
        if (closed) return
        closed = true
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        try { muxer.release() } catch (_: Throwable) {}
    }
}
