package com.dualview.camera.rec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.dualview.camera.TargetSpec

/**
 * H.264 encoder fed by an OpenGL surface. Output goes straight into an MP4 muxer, so the
 * file that lands in the gallery is a real MP4 with correct duration — not a WebM stream
 * with an empty duration field.
 */
class VideoEncoder(val spec: TargetSpec, private val muxer: MuxerWrapper) {

    private val codec: MediaCodec
    val inputSurface: Surface
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var running = false
    private var endOfStreamSignalled = false

    init {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, spec.encWidth, spec.encHeight
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        running = true
    }

    fun drain(endOfStream: Boolean) {
        if (!running) return
        if (endOfStream && !endOfStreamSignalled) {
            endOfStreamSignalled = true
            try {
                codec.signalEndOfInputStream()
            } catch (t: Throwable) {
                return
            }
        }

        var guard = 0
        while (true) {
            if (guard++ > 200) break
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 20_000L else 0L)
            } catch (t: Throwable) {
                break
            }

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (trackIndex < 0) trackIndex = muxer.addTrack(codec.outputFormat)
                }

                index >= 0 -> {
                    val encoded = codec.getOutputBuffer(index)
                    if (encoded != null) {
                        val isConfig =
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!isConfig && bufferInfo.size > 0 && trackIndex >= 0) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSample(trackIndex, encoded, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    fun release() {
        running = false
        try {
            codec.stop()
        } catch (t: Throwable) {
            // Ignore.
        }
        try {
            codec.release()
        } catch (t: Throwable) {
            // Ignore.
        }
        try {
            inputSurface.release()
        } catch (t: Throwable) {
            // Ignore.
        }
    }
}
