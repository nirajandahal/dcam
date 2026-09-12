package com.dualview.camera.rec

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import java.nio.ByteBuffer

/**
 * Records one audio stream and encodes it once, then writes the same AAC packets into every
 * muxer. Encoding twice would waste a codec instance, and instances are exactly what
 * mid-range phones run short of.
 */
class AudioEncoder(private val muxers: List<MuxerWrapper>) {

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var thread: Thread? = null

    private val trackIndices = IntArray(muxers.size) { -1 }
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile private var running = false
    @Volatile private var paused = false
    private var totalSamples = 0L

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) return false
        val bufferSize = maxOf(minBuffer * 2, READ_SIZE * 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
                )
        } catch (t: Throwable) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = try {
                NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            } catch (t: Throwable) {
                null
            }
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
            .apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, READ_SIZE * 2)
            }

        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (t: Throwable) {
            noiseSuppressor?.release()
            record.release()
            return false
        }

        audioRecord = record
        codec = encoder
        running = true
        totalSamples = 0L

        record.startRecording()
        thread = Thread({ loop() }, "DualViewAudio").apply { start() }
        return true
    }

    fun setPaused(value: Boolean) {
        paused = value
    }

    fun stop() {
        running = false
        try {
            thread?.join(1500)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
    }

    private fun loop() {
        val record = audioRecord ?: return
        val encoder = codec ?: return
        val buffer = ByteArray(READ_SIZE)

        while (running) {
            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                break
            }
            if (read <= 0) continue
            if (paused) {
                // Keep draining the microphone so it does not back up, but do not advance
                // the clock: the paused stretch simply never enters the file.
                drain(false)
                continue
            }
            feed(encoder, buffer, read)
            drain(false)
        }

        // Flush with an end-of-stream marker so the AAC track is properly terminated.
        try {
            val index = encoder.dequeueInputBuffer(10_000L)
            if (index >= 0) {
                encoder.queueInputBuffer(
                    index, 0, 0, presentationTimeUs(),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
        } catch (t: Throwable) {
            // Ignore.
        }
        drain(true)
        releaseResources()
    }

    private fun feed(encoder: MediaCodec, buffer: ByteArray, read: Int) {
        try {
            val index = encoder.dequeueInputBuffer(10_000L)
            if (index < 0) return
            val input = encoder.getInputBuffer(index) ?: return
            input.clear()
            input.put(buffer, 0, read)
            encoder.queueInputBuffer(index, 0, read, presentationTimeUs(), 0)
            totalSamples += read / 2L
        } catch (t: Throwable) {
            // Ignore this chunk.
        }
    }

    private fun presentationTimeUs(): Long = totalSamples * 1_000_000L / SAMPLE_RATE

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        var guard = 0
        while (true) {
            if (guard++ > 200) return
            val index = try {
                encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 20_000L else 0L)
            } catch (t: Throwable) {
                return
            }

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = encoder.outputFormat
                    for (i in muxers.indices) {
                        if (trackIndices[i] < 0) trackIndices[i] = muxers[i].addTrack(outFormat)
                    }
                }

                index >= 0 -> {
                    val encoded = encoder.getOutputBuffer(index)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (encoded != null && !isConfig && bufferInfo.size > 0) {
                        for (i in muxers.indices) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxers[i].writeSample(trackIndices[i], encoded, bufferInfo)
                        }
                    }
                    try {
                        encoder.releaseOutputBuffer(index, false)
                    } catch (t: Throwable) {
                        return
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun releaseResources() {
        try {
            audioRecord?.stop()
        } catch (t: Throwable) {
            // Ignore.
        }
        try {
            audioRecord?.release()
        } catch (t: Throwable) {
            // Ignore.
        }
        audioRecord = null

        try {
            noiseSuppressor?.release()
        } catch (t: Throwable) {
            // Ignore.
        }
        noiseSuppressor = null

        try {
            codec?.stop()
        } catch (t: Throwable) {
            // Ignore.
        }
        try {
            codec?.release()
        } catch (t: Throwable) {
            // Ignore.
        }
        codec = null
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val READ_SIZE = 4096
    }
}
