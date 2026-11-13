package com.gikoko.gilocalai

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Copies a selected soundtrack into the rendered MP4 without decoding or loading it into RAM. */
object AudioMuxer {
    fun attach(context: Context, videoFile: File, audioUri: Uri, destination: File) {
        val video = MediaExtractor()
        val audio = MediaExtractor()
        val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            video.setDataSource(videoFile.absolutePath)
            audio.setDataSource(context, audioUri, null)
            val videoTrack = (0 until video.trackCount).firstOrNull {
                video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("Rendered video track not found")
            val audioTrack = (0 until audio.trackCount).firstOrNull {
                audio.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Selected file has no supported audio track")
            val outVideo = muxer.addTrack(video.getTrackFormat(videoTrack))
            val outAudio = muxer.addTrack(audio.getTrackFormat(audioTrack))
            muxer.start(); started = true
            val videoDuration = video.getTrackFormat(videoTrack).let {
                if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else Long.MAX_VALUE
            }
            copyTrack(video, videoTrack, muxer, outVideo, Long.MAX_VALUE)
            copyTrack(audio, audioTrack, muxer, outAudio, videoDuration)
        } finally {
            if (started) try { muxer.stop() } catch (_: Throwable) {}
            muxer.release()
            video.release()
            audio.release()
        }
    }

    private fun copyTrack(extractor: MediaExtractor, sourceTrack: Int, muxer: MediaMuxer, targetTrack: Int, maxDurationUs: Long) {
        extractor.selectTrack(sourceTrack)
        val buffer = ByteBuffer.allocate(1 shl 20)
        val info = MediaMuxer.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val pts = extractor.sampleTime
            if (pts < 0 || pts > maxDurationUs) break
            info.set(0, size, pts, extractor.sampleFlags)
            buffer.position(0); buffer.limit(size)
            muxer.writeSampleData(targetTrack, buffer, info)
            extractor.advance()
        }
    }
}
