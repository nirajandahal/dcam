package com.dualview.camera

import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.dualview.camera.gl.EglCore
import com.dualview.camera.gl.TextureProgram
import com.dualview.camera.rec.DualRecorder

/**
 * The heart of the app. One camera frame arrives, and this thread draws it several times:
 * once to the on-screen preview, and once into each encoder, each with a different crop.
 * Because the crops happen on the GPU while the frame is already in video memory, recording
 * two formats costs barely more than recording one.
 */
class RenderEngine(private val callbackHandler: Handler) {

    interface Listener {
        fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture)
        fun onRenderError(message: String)
    }

    var listener: Listener? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var eglCore: EglCore? = null
    private var previewEglSurface: EGLSurface? = null
    private var program: TextureProgram? = null
    private var textureId = 0
    @Volatile private var surfaceTexture: SurfaceTexture? = null

    private var previewWidth = 0
    private var previewHeight = 0
    private var sourceWidth = 1920
    private var sourceHeight = 1440
    private var baseRotation = 90
    private var mirror = false
    private var look = TextureProgram.LOOK_NATURAL

    private var recorder: DualRecorder? = null
    private val encoderSurfaces = LinkedHashMap<TargetSpec, EGLSurface>()

    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val work = FloatArray(16)
    private val fullFrameCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    /** Dimensions of the frame once rotated upright, which is the space crops work in. */
    val uprightWidth: Int
        get() = if (baseRotation % 180 == 0) sourceWidth else sourceHeight
    val uprightHeight: Int
        get() = if (baseRotation % 180 == 0) sourceHeight else sourceWidth

    fun configureSource(width: Int, height: Int, rotation: Int, mirrored: Boolean) {
        sourceWidth = width
        sourceHeight = height
        baseRotation = ((rotation % 360) + 360) % 360
        mirror = mirrored
    }

    fun start(surface: Surface, width: Int, height: Int) {
        if (thread == null) {
            thread = HandlerThread("DualViewRender").also {
                it.start()
                handler = Handler(it.looper)
            }
        }
        handler?.post { initGl(surface, width, height) }
    }

    fun updatePreviewSize(width: Int, height: Int) {
        handler?.post {
            previewWidth = width
            previewHeight = height
        }
    }

    /** Called when the preview surface goes away, so we stop drawing into a dead surface. */
    fun detachPreview() {
        handler?.post {
            eglCore?.makeNothingCurrent()
            eglCore?.releaseSurface(previewEglSurface)
            previewEglSurface = null
            previewWidth = 0
            previewHeight = 0
        }
    }

    fun setLook(value: Int) {
        handler?.post { look = value }
    }

    fun setMirror(value: Boolean) {
        handler?.post { mirror = value }
    }

    /** Applies a new camera stream size, then reports back once the texture is resized. */
    fun applySource(width: Int, height: Int, rotation: Int, mirrored: Boolean, onReady: () -> Unit) {
        val h = handler
        if (h == null) {
            configureSource(width, height, rotation, mirrored)
            callbackHandler.post(onReady)
            return
        }
        h.post {
            configureSource(width, height, rotation, mirrored)
            surfaceTexture?.setDefaultBufferSize(width, height)
            callbackHandler.post(onReady)
        }
    }

    fun hasSurfaceTexture(): Boolean = surfaceTexture != null

    fun beginRecording(newRecorder: DualRecorder, onResult: (String?) -> Unit) {
        val h = handler
        if (h == null) {
            callbackHandler.post { onResult("Preview is not running.") }
            return
        }
        h.post {
            val core = eglCore
            if (core == null) {
                callbackHandler.post { onResult("Preview is not ready yet.") }
                return@post
            }
            try {
                for ((spec, surface) in newRecorder.surfaces()) {
                    encoderSurfaces[spec] = core.createWindowSurface(surface)
                }
            } catch (t: Throwable) {
                releaseEncoderSurfaces()
                callbackHandler.post { onResult("Could not attach the video encoder.") }
                return@post
            }
            recorder = newRecorder
            callbackHandler.post { onResult(null) }
        }
    }

    fun endRecording(onDone: (List<Uri>) -> Unit) {
        val h = handler
        if (h == null) {
            callbackHandler.post { onDone(emptyList()) }
            return
        }
        h.post {
            val active = recorder
            recorder = null
            releaseEncoderSurfaces()
            val saved = try {
                active?.stop() ?: emptyList()
            } catch (t: Throwable) {
                emptyList()
            }
            callbackHandler.post { onDone(saved) }
        }
    }

    fun release() {
        val h = handler ?: return
        h.post {
            recorder = null
            releaseEncoderSurfaces()
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            program?.release()
            program = null
            eglCore?.releaseSurface(previewEglSurface)
            previewEglSurface = null
            eglCore?.release()
            eglCore = null
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }

    // ---- Render thread ----

    private fun initGl(surface: Surface, width: Int, height: Int) {
        try {
            eglCore?.makeNothingCurrent()
            eglCore?.releaseSurface(previewEglSurface)
            previewEglSurface = null

            val core = eglCore ?: EglCore().also { eglCore = it }
            previewEglSurface = core.createWindowSurface(surface)
            core.makeCurrent(previewEglSurface!!)
            previewWidth = width
            previewHeight = height

            if (program == null) {
                program = TextureProgram()
                textureId = program!!.createExternalTexture()
            }

            if (surfaceTexture == null) {
                val st = SurfaceTexture(textureId)
                st.setDefaultBufferSize(sourceWidth, sourceHeight)
                st.setOnFrameAvailableListener({ drawFrame() }, handler)
                surfaceTexture = st
                callbackHandler.post { listener?.onSurfaceTextureReady(st) }
            } else {
                surfaceTexture?.setDefaultBufferSize(sourceWidth, sourceHeight)
            }
        } catch (t: Throwable) {
            val message = t.message ?: "Graphics setup failed"
            callbackHandler.post { listener?.onRenderError(message) }
        }
    }

    private fun drawFrame() {
        val core = eglCore ?: return
        val st = surfaceTexture ?: return
        val prog = program ?: return
        val previewSurface = previewEglSurface ?: return

        try {
            core.makeCurrent(previewSurface)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        } catch (t: Throwable) {
            return
        }

        // 1. On-screen preview: the whole frame, so the user can see what falls outside
        //    each crop, exactly like the guide frames suggest.
        if (previewWidth > 0 && previewHeight > 0) {
            try {
                GLES20.glViewport(0, 0, previewWidth, previewHeight)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                prog.draw(textureId, buildTexMatrix(0), fullFrameCoords, look)
                core.swapBuffers(previewSurface)
            } catch (t: Throwable) {
                callbackHandler.post { listener?.onRenderError("Preview draw failed: ${t.message}") }
                return
            }
        }

        // 2. Each recording target, cropped.
        val active = recorder ?: return
        if (active.isPaused) return
        val timestampNs = active.presentationTimeNs()

        for ((spec, eglSurface) in encoderSurfaces) {
            try {
                core.makeCurrent(eglSurface)
                GLES20.glViewport(0, 0, spec.encWidth, spec.encHeight)
                prog.draw(textureId, buildTexMatrix(spec.extraRotation), spec.texCoords, look)
                core.setPresentationTime(eglSurface, timestampNs)
                core.swapBuffers(eglSurface)
            } catch (t: Throwable) {
                // Skip this frame for this target rather than killing the whole recording.
            }
        }
        active.drainAll()
    }

    /**
     * Combines the SurfaceTexture's own transform with the rotation that makes the sensor
     * frame upright, optional mirroring for the front camera, and any extra rotation needed
     * to fit a picky encoder.
     */
    private fun buildTexMatrix(extraRotation: Int): FloatArray {
        Matrix.setIdentityM(work, 0)

        Matrix.translateM(work, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(work, 0, baseRotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(work, 0, -0.5f, -0.5f, 0f)

        if (mirror) {
            Matrix.translateM(work, 0, 0.5f, 0.5f, 0f)
            Matrix.scaleM(work, 0, -1f, 1f, 1f)
            Matrix.translateM(work, 0, -0.5f, -0.5f, 0f)
        }

        if (extraRotation != 0) {
            Matrix.translateM(work, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(work, 0, extraRotation.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(work, 0, -0.5f, -0.5f, 0f)
        }

        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, work, 0)
        return texMatrix
    }

    private fun releaseEncoderSurfaces() {
        val core = eglCore
        for (surface in encoderSurfaces.values) core?.releaseSurface(surface)
        encoderSurfaces.clear()
    }

    companion object {
        fun mainHandler(): Handler = Handler(Looper.getMainLooper())
    }
}
