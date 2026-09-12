package com.dualview.camera.rec

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Wraps one MediaMuxer. The muxer may only start once every track has been added, so the
 * video and audio encoders both report in here and the last one through starts it.
 */
class MuxerWrapper(fd: FileDescriptor, private val expectedTracks: Int, orientationHint: Int) {

    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var addedTracks = 0
    private var started = false
    private var released = false

    init {
        if (orientationHint != 0) {
            try {
                muxer.setOrientationHint(orientationHint)
            } catch (t: Throwable) {
                // Non-fatal: the video still plays, just possibly sideways.
            }
        }
    }

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        if (started || released) return -1
        val index = muxer.addTrack(format)
        addedTracks++
        if (addedTracks >= expectedTracks) {
            muxer.start()
            started = true
        }
        return index
    }

    @Synchronized
    fun isStarted(): Boolean = started

    @Synchronized
    fun writeSample(trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || released || trackIndex < 0) return
        if (info.size <= 0) return
        try {
            muxer.writeSampleData(trackIndex, buffer, info)
        } catch (t: Throwable) {
            // A muxer that has already failed will keep throwing; swallow so the other
            // format still gets written.
        }
    }

    @Synchronized
    fun release(): Boolean {
        if (released) return false
        released = true
        var clean = false
        try {
            if (started) {
                muxer.stop()
                clean = true
            }
        } catch (t: Throwable) {
            clean = false
        }
        try {
            muxer.release()
        } catch (t: Throwable) {
            // Ignore.
        }
        return clean
    }
}
