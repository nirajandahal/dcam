package com.dualview.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Camera2 plumbing: pick a camera, pick the stream size, keep a repeating preview request
 * running, and take full-resolution stills.
 */
class CameraEngine(private val context: Context) {

    interface Listener {
        fun onCameraReady(sourceSize: Size, sensorRotation: Int, isFront: Boolean)
        fun onSourceDowngraded(newSize: Size)
        fun onStillCaptured(jpeg: ByteArray, imageWidth: Int, imageHeight: Int)
        fun onCameraError(message: String)
    }

    var listener: Listener? = null

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val executor = Executor { command -> handler?.post(command) ?: command.run() }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null

    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    var sourceSize: Size = Size(1920, 1440)
        private set
    var sensorRotation: Int = 90
        private set
    var isFront: Boolean = false
        private set

    private var sourceCandidates: List<Size> = emptyList()
    private var sourceIndex = 0
    private var torchOn = false
    private var zoomRatio = 1f
    private var closing = false

    fun startThread() {
        if (thread == null) {
            thread = HandlerThread("DualViewCamera").also {
                it.start()
                handler = Handler(it.looper)
            }
        }
    }

    fun stopThread() {
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun availableFacings(): List<Boolean> {
        val result = ArrayList<Boolean>()
        try {
            for (id in manager.cameraIdList) {
                val facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) result.add(true)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) result.add(false)
            }
        } catch (t: Throwable) {
            // Ignore.
        }
        return result.distinct()
    }

    /** Resolves the camera and the stream size without opening anything yet. */
    fun prepare(front: Boolean, quality: Quality): Boolean {
        val id = findCameraId(front) ?: findCameraId(!front) ?: return false
        val chars = try {
            manager.getCameraCharacteristics(id)
        } catch (t: Throwable) {
            return false
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: return false

        cameraId = id
        characteristics = chars
        isFront = chars.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        // A huge stream is useless if the sensor can only deliver it at eight frames a
        // second, so drop anything that cannot sustain roughly 24fps.
        val smooth = sizes.filter { size ->
            val minDuration = try {
                map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
            } catch (t: Throwable) {
                0L
            }
            minDuration <= 0L || minDuration <= 41_700_000L
        }.ifEmpty { sizes }

        sourceSize = Planner.pickSourceSize(smooth, quality)
        sourceCandidates = smooth.sortedByDescending { it.width.toLong() * it.height }
            .filter { it.width.toLong() * it.height <= sourceSize.width.toLong() * sourceSize.height }
        sourceIndex = 0
        return true
    }

    fun stillSize(): Size? {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: return null
        // Cap at roughly 20 megapixels: beyond that the crops cost more memory than they
        // are worth on a mid-range phone.
        val usable = sizes.filter { it.width.toLong() * it.height <= 20_000_000L }.ifEmpty { sizes }
        return usable.maxByOrNull { it.width.toLong() * it.height }
    }

    fun supportedOutputSizes(): List<Size> {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        return map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun open(surfaceTexture: SurfaceTexture) {
        val id = cameraId ?: return
        closing = false
        surfaceTexture.setDefaultBufferSize(sourceSize.width, sourceSize.height)
        val previewSurface = Surface(surfaceTexture)

        val still = stillSize() ?: Size(sourceSize.width, sourceSize.height)
        imageReader?.close()
        imageReader = ImageReader.newInstance(still.width, still.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireLatestImage()
                } catch (t: Throwable) {
                    null
                } ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val width = image.width
                    val height = image.height
                    mainHandler.post { listener?.onStillCaptured(bytes, width, height) }
                } catch (t: Throwable) {
                    // Ignore.
                } finally {
                    try {
                        image.close()
                    } catch (t: Throwable) {
                        // Ignore.
                    }
                }
            }, handler)
        }

        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, previewSurface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    if (!closing) { val m = describeError(error); mainHandler.post { listener?.onCameraError(m) } }
                }
            }, handler)
        } catch (t: Throwable) {
            val m = "Could not open the camera: ${t.message}"
            mainHandler.post { listener?.onCameraError(m) }
        }
    }

    private fun createSession(camera: CameraDevice, previewSurface: Surface) {
        val reader = imageReader ?: return
        val outputs = listOf(
            OutputConfiguration(previewSurface),
            OutputConfiguration(reader.surface)
        )

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                session = configured
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                requestBuilder = builder
                applyZoom(builder)
                applyTorch(builder)
                startRepeating()
                val size = sourceSize
                val rotation = sensorRotation
                val front = isFront
                mainHandler.post { listener?.onCameraReady(size, rotation, front) }
            }

            override fun onConfigureFailed(configured: CameraCaptureSession) {
                // Some phones advertise stream sizes they will not actually combine with a
                // full-resolution still. Step down instead of failing outright.
                if (advanceToSmallerSource()) {
                    val size = sourceSize
                    mainHandler.post { listener?.onSourceDowngraded(size) }
                } else {
                    val message = "This phone would not accept a " +
                            "${sourceSize.width}x${sourceSize.height} camera stream." +
                            " Try a lower quality."
                    mainHandler.post { listener?.onCameraError(message) }
                }
            }
        }

        try {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, executor, callback
            )
            camera.createCaptureSession(config)
        } catch (t: Throwable) {
            val m = "Could not start the camera session: ${t.message}"
            mainHandler.post { listener?.onCameraError(m) }
        }
    }

    private fun startRepeating() {
        val builder = requestBuilder ?: return
        val active = session ?: return
        try {
            active.setRepeatingRequest(builder.build(), null, handler)
        } catch (t: Throwable) {
            // Ignore: the session may be closing.
        }
    }

    fun setZoom(ratio: Float) {
        zoomRatio = ratio
        val builder = requestBuilder ?: return
        applyZoom(builder)
        startRepeating()
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val chars = characteristics ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                val clamped = zoomRatio.coerceIn(range.lower, range.upper)
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
                return
            }
        }
        // Older path: crop the sensor's active area by hand.
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val clamped = zoomRatio.coerceIn(1f, maxZoom)
        val cropWidth = (active.width() / clamped).toInt()
        val cropHeight = (active.height() / clamped).toInt()
        val left = (active.width() - cropWidth) / 2
        val top = (active.height() - cropHeight) / 2
        builder.set(
            CaptureRequest.SCALER_CROP_REGION,
            Rect(left, top, left + cropWidth, top + cropHeight)
        )
    }

    fun hasTorch(): Boolean =
        characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    fun setTorch(on: Boolean) {
        torchOn = on
        val builder = requestBuilder ?: return
        applyTorch(builder)
        startRepeating()
    }

    private fun applyTorch(builder: CaptureRequest.Builder) {
        if (!hasTorch()) return
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
    }

    fun captureStill() {
        val camera = device ?: return
        val active = session ?: return
        val reader = imageReader ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_QUALITY, 96.toByte())
                // Leave orientation at zero: cropping happens in sensor space and the
                // finished bitmap is rotated before saving.
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }
            applyZoom(builder)
            applyTorch(builder)
            active.capture(builder.build(), null, handler)
        } catch (t: Throwable) {
            val m = "Could not take the photo: ${t.message}"
            mainHandler.post { listener?.onCameraError(m) }
        }
    }

    private fun advanceToSmallerSource(): Boolean {
        val currentArea = sourceSize.width.toLong() * sourceSize.height
        val next = sourceCandidates.firstOrNull { it.width.toLong() * it.height < currentArea }
            ?: return false
        sourceSize = next
        sourceIndex++
        return sourceIndex <= 3
    }

    fun close() {
        closing = true
        try {
            session?.close()
        } catch (t: Throwable) {
            // Ignore.
        }
        session = null
        requestBuilder = null
        try {
            device?.close()
        } catch (t: Throwable) {
            // Ignore.
        }
        device = null
        try {
            imageReader?.close()
        } catch (t: Throwable) {
            // Ignore.
        }
        imageReader = null
    }

    private fun findCameraId(front: Boolean): String? {
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        return try {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == wanted
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun describeError(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ->
            "Another app is using the camera. Close it and try again."
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
            "Too many cameras are open on this phone."
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
            "The camera is disabled, possibly by a device policy."
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ->
            "The camera hit a hardware error. Restarting the app usually clears it."
        else -> "The camera service reported an error."
    }
}
