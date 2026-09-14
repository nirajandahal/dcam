package com.dualview.camera.rec

import android.content.Context
import android.view.Surface
import com.dualview.camera.CaptureStore
import com.dualview.camera.TargetSpec

/**
 * Owns one recording session: one encoder and one MP4 file per output format, plus a single
 * shared audio track. The render thread calls [surfaceFor] to get somewhere to draw, and
 * [drainAll] after each frame.
 */
class DualRecorder(
    context: Context,
    private val specs: List<TargetSpec>,
    private val audioEnabled: Boolean,
    private val noiseSuppression: Boolean,
    private val saveToGallery: Boolean
) {

    class Track(
        val spec: TargetSpec,
        val encoder: VideoEncoder,
        val muxer: MuxerWrapper,
        val pending: CaptureStore.PendingVideo
    )

    private val store = CaptureStore(context)
    private val tracks = ArrayList<Track>(specs.size)
    private var audio: AudioEncoder? = null

    private var startNs = 0L
    private var pausedTotalNs = 0L
    private var pauseStartedNs = 0L
    private var firstFrameSeen = false

    @Volatile var isPaused = false
        private set

    val activeTracks: List<Track> get() = tracks

    /** @return null on success, or a human-readable reason the recording could not start. */
    fun start(): String? {
        val stamp = CaptureStore.timestamp()
        val created = ArrayList<Track>(specs.size)

        for (spec in specs) {
            val name = CaptureStore.nameFor("DualView", spec.format, stamp, "mp4")
            val pending = store.beginVideo(name, saveToGallery)
            if (pending == null) {
                cleanupPartial(created)
                return "Could not create a file in your gallery."
            }

            val expectedTracks = if (audioEnabled) 2 else 1
            val muxer = try {
                MuxerWrapper(pending.descriptor.fileDescriptor, expectedTracks, spec.orientationHint)
            } catch (t: Throwable) {
                closeQuietly(pending)
                store.discardVideo(pending)
                cleanupPartial(created)
                return "This phone refused to create an MP4 file."
            }

            val encoder = try {
                VideoEncoder(spec, muxer)
            } catch (t: Throwable) {
                muxer.release()
                closeQuietly(pending)
                store.discardVideo(pending)
                cleanupPartial(created)
                return "Your phone's video encoder refused ${spec.displayWidth}x${spec.displayHeight}." +
                        " Try a lower quality."
            }

            created.add(Track(spec, encoder, muxer, pending))
        }

        tracks.addAll(created)

        if (audioEnabled) {
            val encoder = AudioEncoder(tracks.map { it.muxer }, noiseSuppression)
            audio = if (encoder.start()) encoder else null
            if (audio == null) {
                // Audio failed but video is fine. Muxers expect two tracks each, so tear
                // down and rebuild without sound rather than producing an unplayable file.
                stopInternal(discard = true)
                return "Microphone unavailable. Turn off \"Record sound\" and try again."
            }
        }

        startNs = System.nanoTime()
        pausedTotalNs = 0L
        firstFrameSeen = false
        isPaused = false
        return null
    }

    fun surfaces(): List<Pair<TargetSpec, Surface>> =
        tracks.map { it.spec to it.encoder.inputSurface }

    fun surfaceFor(spec: TargetSpec): Surface? =
        tracks.firstOrNull { it.spec === spec }?.encoder?.inputSurface

    /** Presentation timestamp for the frame arriving now, with paused stretches removed. */
    fun presentationTimeNs(): Long {
        val now = System.nanoTime()
        if (!firstFrameSeen) {
            firstFrameSeen = true
            startNs = now
        }
        return (now - startNs - pausedTotalNs).coerceAtLeast(0L)
    }

    fun elapsedMs(): Long {
        if (startNs == 0L) return 0L
        val extra = if (isPaused) System.nanoTime() - pauseStartedNs else 0L
        return (System.nanoTime() - startNs - pausedTotalNs - extra).coerceAtLeast(0L) / 1_000_000L
    }

    fun drainAll() {
        for (track in tracks) track.encoder.drain(false)
    }

    fun setPaused(value: Boolean) {
        if (value == isPaused) return
        isPaused = value
        if (value) {
            pauseStartedNs = System.nanoTime()
        } else {
            pausedTotalNs += System.nanoTime() - pauseStartedNs
        }
        audio?.setPaused(value)
    }

    /** @return the names of the saved files, empty if nothing usable was produced. */
    fun stop(): List<String> = stopInternal(discard = false)

    private fun stopInternal(discard: Boolean): List<String> {
        audio?.stop()
        audio = null

        val saved = ArrayList<String>(tracks.size)
        for (track in tracks) {
            try {
                track.encoder.drain(true)
            } catch (t: Throwable) {
                // Ignore.
            }
            track.encoder.release()
            val clean = track.muxer.release()
            closeQuietly(track.pending)

            if (clean && !discard) {
                store.publishVideo(track.pending)
                saved.add(track.pending.displayName)
            } else {
                store.discardVideo(track.pending)
            }
        }
        tracks.clear()
        return saved
    }

    private fun cleanupPartial(created: List<Track>) {
        for (track in created) {
            track.encoder.release()
            track.muxer.release()
            closeQuietly(track.pending)
            store.discardVideo(track.pending)
        }
    }

    private fun closeQuietly(pending: CaptureStore.PendingVideo) {
        try {
            pending.descriptor.close()
        } catch (t: Throwable) {
            // Ignore.
        }
    }
}
