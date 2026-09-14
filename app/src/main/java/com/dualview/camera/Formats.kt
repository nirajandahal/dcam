package com.dualview.camera

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size
import kotlin.math.roundToInt

enum class OutputFormat(val label: String, val longSide: Int, val shortSide: Int) {
    VERTICAL("9:16", 16, 9),
    HORIZONTAL("16:9", 16, 9)
}

enum class FormatMode { VERTICAL_ONLY, BOTH, HORIZONTAL_ONLY }

enum class Quality(val label: String, val targetLong: Int) {
    HD("720p", 1280),
    FHD("1080p", 1920),
    UHD("4K", 3840)
}

/** Frame rates offered. What a given phone can actually sustain is checked at runtime. */
enum class FrameRate(val label: String, val value: Int) {
    FPS24("24", 24),
    FPS30("30", 30),
    FPS60("60", 60)
}

/** Still-photo size. MAX uses everything the sensor gives; the rest cap the pixel count. */
enum class PhotoRes(val label: String, val maxPixels: Long) {
    STANDARD("8 MP", 8_400_000L),
    HIGH("12 MP", 12_600_000L),
    MAX("Max", 24_000_000L)
}

/**
 * One video file we are about to record.
 *
 * [encWidth]/[encHeight] are the dimensions of the buffer handed to the encoder. When a
 * phone's encoder refuses tall (portrait) frames — common on mid-range chips, which often
 * cap out at 1920x1088 — we encode a landscape buffer instead and set [orientationHint] so
 * players rotate it back. That is the difference between a working vertical video and a
 * silent failure.
 */
data class TargetSpec(
    val format: OutputFormat,
    val encWidth: Int,
    val encHeight: Int,
    val extraRotation: Int,
    val orientationHint: Int,
    val bitRate: Int,
    val fps: Int,
    val texCoords: FloatArray
) {
    /** What the file looks like once a player has applied the rotation metadata. */
    val displayWidth: Int get() = if (extraRotation == 0) encWidth else encHeight
    val displayHeight: Int get() = if (extraRotation == 0) encHeight else encWidth

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

object EncoderCaps {

    data class Report(
        val codecName: String,
        val maxWidth: Int,
        val maxHeight: Int,
        val widthAlignment: Int,
        val heightAlignment: Int,
        val maxInstances: Int
    )

    private var cachedCaps: MediaCodecInfo.VideoCapabilities? = null
    private var cachedReport: Report? = null
    private var probed = false

    @Synchronized
    private fun probe() {
        if (probed) return
        probed = true
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                val match = info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)
                }
                if (!match) continue
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val video = caps.videoCapabilities ?: continue
                // Prefer a hardware codec; software encoders are far too slow for 4K.
                val isSoftware = info.name.startsWith("OMX.google.", true) ||
                        info.name.startsWith("c2.android.", true)
                if (isSoftware && cachedCaps != null) continue
                cachedCaps = video
                cachedReport = Report(
                    codecName = info.name,
                    maxWidth = video.supportedWidths.upper,
                    maxHeight = video.supportedHeights.upper,
                    widthAlignment = video.widthAlignment,
                    heightAlignment = video.heightAlignment,
                    maxInstances = caps.maxSupportedInstances
                )
                if (!isSoftware) break
            }
        } catch (t: Throwable) {
            cachedCaps = null
            cachedReport = null
        }
    }

    fun report(): Report? {
        probe()
        return cachedReport
    }

    fun supports(width: Int, height: Int, fps: Int): Boolean {
        probe()
        val caps = cachedCaps ?: return true
        return try {
            caps.areSizeAndRateSupported(width, height, fps.toDouble())
        } catch (t: Throwable) {
            false
        }
    }

    /** How many AVC encoders this phone is willing to run at once. */
    fun maxInstances(): Int = report()?.maxInstances ?: 2
}

object Planner {

    /** Standard output sizes per quality tier, largest first so we can step down. */
    private fun ladder(format: OutputFormat): List<Size> = when (format) {
        OutputFormat.VERTICAL -> listOf(Size(2160, 3840), Size(1440, 2560), Size(1080, 1920), Size(720, 1280))
        OutputFormat.HORIZONTAL -> listOf(Size(3840, 2160), Size(2560, 1440), Size(1920, 1080), Size(1280, 720))
    }

    fun bitRateFor(width: Int, height: Int, fps: Int): Int {
        val raw = (width.toLong() * height.toLong() * fps * 0.08).toLong()
        return raw.coerceIn(3_000_000L, 90_000_000L).toInt()
    }

    /**
     * Chooses the camera stream we feed the GPU. A 4:3 stream is ideal: it is the sensor's
     * native shape, so it gives the most height for the 9:16 crop and the most width for the
     * 16:9 crop at the same time.
     */
    fun pickSourceSize(available: List<Size>, quality: Quality): Size {
        if (available.isEmpty()) return Size(1920, 1440)
        val maxLong = when (quality) {
            Quality.HD -> 1600
            Quality.FHD -> 2700
            Quality.UHD -> 4128
        }
        val usable = available.filter { maxOf(it.width, it.height) <= maxLong }
            .ifEmpty { listOf(available.minByOrNull { it.width.toLong() * it.height }!!) }

        val fourThree = usable.filter { aspectOf(it) in 1.30f..1.36f }
        val pool = fourThree.ifEmpty { usable }
        return pool.maxByOrNull { it.width.toLong() * it.height }!!
    }

    private fun aspectOf(size: Size): Float {
        val w = maxOf(size.width, size.height).toFloat()
        val h = minOf(size.width, size.height).toFloat()
        return w / h
    }

    /**
     * Works out what this phone can genuinely record, given the camera stream we have and
     * what the encoder admits to supporting. Never invents detail: an output is capped at
     * the size of the crop it comes from, so a "4K" label always means real 4K pixels.
     */
    fun plan(
        formats: List<OutputFormat>,
        quality: Quality,
        sourceUprightWidth: Int,
        sourceUprightHeight: Int,
        fps: Int
    ): List<TargetSpec> {
        val specs = ArrayList<TargetSpec>(formats.size)
        for (format in formats) {
            val crop = cropSize(format, sourceUprightWidth, sourceUprightHeight)
            val cropLong = maxOf(crop.width, crop.height)

            val candidates = ladder(format).filter {
                maxOf(it.width, it.height) <= quality.targetLong &&
                        maxOf(it.width, it.height) <= cropLong + 8
            }.ifEmpty {
                listOf(ladder(format).last())
            }

            var chosen: TargetSpec? = null
            for (size in candidates) {
                val direct = EncoderCaps.supports(size.width, size.height, fps)
                if (direct) {
                    chosen = TargetSpec(
                        format = format,
                        encWidth = size.width,
                        encHeight = size.height,
                        extraRotation = 0,
                        orientationHint = 0,
                        bitRate = bitRateFor(size.width, size.height, fps),
                        fps = fps,
                        texCoords = cropTexCoords(format, sourceUprightWidth, sourceUprightHeight, 0)
                    )
                    break
                }
                // Encoder rejected this shape. Try the same frame rotated into a landscape
                // buffer, with rotation metadata so it still plays upright.
                if (EncoderCaps.supports(size.height, size.width, fps)) {
                    // The frame is rotated a quarter turn into the landscape buffer the
                    // encoder will accept, so playback must rotate it back the other way.
                    chosen = TargetSpec(
                        format = format,
                        encWidth = size.height,
                        encHeight = size.width,
                        extraRotation = 90,
                        orientationHint = 270,
                        bitRate = bitRateFor(size.width, size.height, fps),
                        fps = fps,
                        texCoords = cropTexCoords(format, sourceUprightWidth, sourceUprightHeight, 90)
                    )
                    break
                }
            }
            if (chosen != null) specs.add(chosen)
        }
        return capToInstances(specs)
    }

    /** If the chip can only run one encoder at a time we cannot record both formats. */
    private fun capToInstances(specs: List<TargetSpec>): List<TargetSpec> {
        val max = EncoderCaps.maxInstances()
        return if (max >= specs.size || specs.size <= 1) specs else specs.take(maxOf(1, max))
    }

    fun cropSize(format: OutputFormat, uprightWidth: Int, uprightHeight: Int): Size {
        return when (format) {
            OutputFormat.VERTICAL -> {
                var h = uprightHeight
                var w = (h * 9f / 16f).roundToInt()
                if (w > uprightWidth) {
                    w = uprightWidth
                    h = (w * 16f / 9f).roundToInt().coerceAtMost(uprightHeight)
                }
                Size(w, h)
            }
            OutputFormat.HORIZONTAL -> {
                var w = uprightWidth
                var h = (w * 9f / 16f).roundToInt()
                if (h > uprightHeight) {
                    h = uprightHeight
                    w = (h * 16f / 9f).roundToInt().coerceAtMost(uprightWidth)
                }
                Size(w, h)
            }
        }
    }

    /**
     * Texture coordinates for a centred crop, in the upright image's 0..1 space, ordered
     * bottom-left, bottom-right, top-left, top-right to match the quad in [gl.TextureProgram].
     * Crops are centred, so this is unaffected by whether the texture is y-up or y-down.
     */
    fun cropTexCoords(
        format: OutputFormat,
        uprightWidth: Int,
        uprightHeight: Int,
        extraRotation: Int
    ): FloatArray {
        val crop = cropSize(format, uprightWidth, uprightHeight)
        var halfU = crop.width.toFloat() / uprightWidth.toFloat() / 2f
        var halfV = crop.height.toFloat() / uprightHeight.toFloat() / 2f
        if (extraRotation == 90 || extraRotation == 270) {
            val swap = halfU
            halfU = halfV
            halfV = swap
        }
        val u0 = 0.5f - halfU
        val u1 = 0.5f + halfU
        val v0 = 0.5f - halfV
        val v1 = 0.5f + halfV
        return floatArrayOf(u0, v0, u1, v0, u0, v1, u1, v1)
    }

    fun formatsFor(mode: FormatMode): List<OutputFormat> = when (mode) {
        FormatMode.VERTICAL_ONLY -> listOf(OutputFormat.VERTICAL)
        FormatMode.HORIZONTAL_ONLY -> listOf(OutputFormat.HORIZONTAL)
        FormatMode.BOTH -> listOf(OutputFormat.VERTICAL, OutputFormat.HORIZONTAL)
    }
}
