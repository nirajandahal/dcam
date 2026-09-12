package com.dualview.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.dualview.camera.gl.TextureProgram
import com.dualview.camera.rec.DualRecorder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), CameraEngine.Listener, RenderEngine.Listener {

    private lateinit var viewfinder: FrameLayout
    private lateinit var preview: SurfaceView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var shutterBtn: Button
    private lateinit var galleryBtn: ImageButton
    private lateinit var flipBtn: ImageButton
    private lateinit var pauseBtn: ImageButton
    private lateinit var stillBtn: ImageButton
    private lateinit var flashBtn: ImageButton
    private lateinit var gridBtn: ImageButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var recBadge: LinearLayout
    private lateinit var recTime: TextView
    private lateinit var countdownText: TextView
    private lateinit var settingsScrim: FrameLayout
    private lateinit var zoomGroup: LinearLayout

    private lateinit var fmtVertical: TextView
    private lateinit var fmtBoth: TextView
    private lateinit var fmtHorizontal: TextView
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var audioSwitch: SwitchCompat
    private lateinit var mirrorSwitch: SwitchCompat

    private val cameraEngine by lazy { CameraEngine(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderEngine by lazy { RenderEngine(mainHandler) }
    private val worker = Executors.newSingleThreadExecutor()

    private var formatMode = FormatMode.BOTH
    private var quality = Quality.FHD
    private var timerSeconds = 0
    private var look = TextureProgram.LOOK_NATURAL
    private var useFront = false
    private var mirrorFront = true
    private var recordAudio = true
    private var zoom = 1f
    private var torchOn = false
    private var videoMode = false

    private var recorder: DualRecorder? = null
    private var isRecording = false
    private var cameraOpen = false
    private var surfaceReady = false
    private var pendingCountdown: Runnable? = null
    private var lastPlan: List<TargetSpec> = emptyList()
    private var sizedAspect = 0f

    private val timerTick = object : Runnable {
        override fun run() {
            val active = recorder ?: return
            val ms = active.elapsedMs()
            val totalSeconds = ms / 1000
            recTime.text = String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
            recDotBlink(active.isPaused)
            mainHandler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        setContentView(R.layout.activity_main)
        showPendingCrashReport()
        bindViews()
        wireControls()
        renderEngine.listener = this
        cameraEngine.listener = this

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceReady = true
                renderEngine.start(holder.surface, width, height)
                renderEngine.updatePreviewSize(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                renderEngine.detachPreview()
            }
        })

        applyFormatMode(formatMode)
        applyMode(videoMode)
        applyQualitySelection()
        applyTimerSelection()
        applyLookSelection()
        applyZoomSelection()
    }

    private fun bindViews() {
        viewfinder = findViewById(R.id.viewfinder)
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        shutterBtn = findViewById(R.id.shutterBtn)
        galleryBtn = findViewById(R.id.galleryBtn)
        flipBtn = findViewById(R.id.flipBtn)
        pauseBtn = findViewById(R.id.pauseBtn)
        stillBtn = findViewById(R.id.stillBtn)
        flashBtn = findViewById(R.id.flashBtn)
        gridBtn = findViewById(R.id.gridBtn)
        settingsBtn = findViewById(R.id.settingsBtn)
        recBadge = findViewById(R.id.recBadge)
        recTime = findViewById(R.id.recTime)
        countdownText = findViewById(R.id.countdownText)
        settingsScrim = findViewById(R.id.settingsScrim)
        zoomGroup = findViewById(R.id.zoomGroup)
        fmtVertical = findViewById(R.id.fmtVertical)
        fmtBoth = findViewById(R.id.fmtBoth)
        fmtHorizontal = findViewById(R.id.fmtHorizontal)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        audioSwitch = findViewById(R.id.audioSwitch)
        mirrorSwitch = findViewById(R.id.mirrorSwitch)
    }

    private fun wireControls() {
        fmtVertical.setOnClickListener { applyFormatMode(FormatMode.VERTICAL_ONLY) }
        fmtBoth.setOnClickListener { applyFormatMode(FormatMode.BOTH) }
        fmtHorizontal.setOnClickListener { applyFormatMode(FormatMode.HORIZONTAL_ONLY) }

        modePhoto.setOnClickListener { if (!isRecording) applyMode(false) }
        modeVideo.setOnClickListener { if (!isRecording) applyMode(true) }

        shutterBtn.setOnClickListener { onShutter() }
        stillBtn.setOnClickListener { capturePhoto() }
        pauseBtn.setOnClickListener { togglePause() }
        flipBtn.setOnClickListener { flipCamera() }

        gridBtn.setOnClickListener {
            overlay.showGrid = !overlay.showGrid
            gridBtn.alpha = if (overlay.showGrid) 1f else 0.55f
        }
        gridBtn.alpha = 0.55f

        flashBtn.setOnClickListener {
            torchOn = !torchOn
            cameraEngine.setTorch(torchOn)
            flashBtn.setImageResource(
                if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
            )
        }

        galleryBtn.setOnClickListener { openGallery() }

        settingsBtn.setOnClickListener { settingsScrim.visibility = View.VISIBLE }
        findViewById<View>(R.id.settingsClose).setOnClickListener {
            settingsScrim.visibility = View.GONE
        }
        settingsScrim.setOnClickListener { settingsScrim.visibility = View.GONE }
        findViewById<View>(R.id.settingsPanel).setOnClickListener { }

        findViewById<View>(R.id.q720).setOnClickListener { changeQuality(Quality.HD) }
        findViewById<View>(R.id.q1080).setOnClickListener { changeQuality(Quality.FHD) }
        findViewById<View>(R.id.q2160).setOnClickListener { changeQuality(Quality.UHD) }

        findViewById<View>(R.id.t0).setOnClickListener { timerSeconds = 0; applyTimerSelection() }
        findViewById<View>(R.id.t3).setOnClickListener { timerSeconds = 3; applyTimerSelection() }
        findViewById<View>(R.id.t10).setOnClickListener { timerSeconds = 10; applyTimerSelection() }

        findViewById<View>(R.id.lookNatural).setOnClickListener { changeLook(TextureProgram.LOOK_NATURAL) }
        findViewById<View>(R.id.lookMono).setOnClickListener { changeLook(TextureProgram.LOOK_MONO) }
        findViewById<View>(R.id.lookWarm).setOnClickListener { changeLook(TextureProgram.LOOK_WARM) }
        findViewById<View>(R.id.lookVivid).setOnClickListener { changeLook(TextureProgram.LOOK_VIVID) }

        audioSwitch.setOnCheckedChangeListener { _, checked -> recordAudio = checked }
        mirrorSwitch.setOnCheckedChangeListener { _, checked ->
            mirrorFront = checked
            renderEngine.setMirror(useFront && mirrorFront)
        }

        findViewById<View>(R.id.diagnosticsBtn).setOnClickListener { showDiagnostics() }

        findViewById<View>(R.id.zoom1).setOnClickListener { changeZoom(1f) }
        findViewById<View>(R.id.zoom2).setOnClickListener { changeZoom(2f) }
        findViewById<View>(R.id.zoom3).setOnClickListener { changeZoom(3f) }
    }

    // ---- Lifecycle ----

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) {
            startCameraStack()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
        }
    }

    override fun onPause() {
        pendingCountdown?.let { mainHandler.removeCallbacks(it) }
        pendingCountdown = null
        countdownText.visibility = View.GONE
        if (isRecording) stopRecording(showToast = false)
        cameraEngine.close()
        cameraEngine.stopThread()
        cameraOpen = false
        keepScreenOn(false)
        super.onPause()
    }

    override fun onDestroy() {
        renderEngine.release()
        worker.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasPermission(Manifest.permission.CAMERA)) {
            startCameraStack()
        } else {
            statusText.text = "DualView needs camera access to work."
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            recordAudio = false
            audioSwitch.isChecked = false
        }
    }

    private fun hasPermissions(): Boolean = hasPermission(Manifest.permission.CAMERA)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---- Camera bring-up ----

    private fun startCameraStack() {
        cameraEngine.startThread()
        if (!cameraEngine.prepare(useFront, quality)) {
            statusText.text = "No usable camera found on this phone."
            return
        }
        useFront = cameraEngine.isFront
        sizeViewfinder()
        renderEngine.applySource(
            cameraEngine.sourceSize.width,
            cameraEngine.sourceSize.height,
            cameraEngine.sensorRotation,
            useFront && mirrorFront
        ) {
            if (renderEngine.hasSurfaceTexture() && !cameraOpen) openCameraNow()
        }
        updatePlan()
        flashBtn.visibility = if (cameraEngine.hasTorch()) View.VISIBLE else View.GONE
    }

    private fun openCameraNow() {
        val texture = surfaceTexture ?: return
        cameraOpen = true
        cameraEngine.open(texture)
    }

    private var surfaceTexture: SurfaceTexture? = null

    override fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (!cameraOpen && hasPermissions()) openCameraNow()
    }

    override fun onRenderError(message: String) {
        statusText.text = message
    }

    override fun onCameraReady(sourceSize: Size, sensorRotation: Int, isFront: Boolean) {
        cameraEngine.setZoom(zoom)
        updatePlan()
    }

    override fun onSourceDowngraded(newSize: Size) {
        mainHandler.post {
            cameraEngine.close()
            cameraOpen = false
            sizeViewfinder()
            renderEngine.applySource(
                newSize.width,
                newSize.height,
                cameraEngine.sensorRotation,
                useFront && mirrorFront
            ) {
                if (renderEngine.hasSurfaceTexture() && !cameraOpen) openCameraNow()
            }
            updatePlan()
            statusText.text = "Stepped down to ${newSize.width}x${newSize.height}: " +
                    "this phone would not run the larger stream."
        }
    }

    override fun onCameraError(message: String) {
        mainHandler.post {
            cameraOpen = false
            statusText.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Makes the preview exactly the shape of the sensor frame, so nothing is hidden. */
    private fun sizeViewfinder() {
        val source = cameraEngine.sourceSize
        val quarterTurn = cameraEngine.sensorRotation % 180 != 0
        val uprightWidth = if (quarterTurn) source.height else source.width
        val uprightHeight = if (quarterTurn) source.width else source.height
        val aspect = uprightWidth.toFloat() / uprightHeight.toFloat()
        if (aspect == sizedAspect) return
        sizedAspect = aspect

        viewfinder.post {
            val availableWidth = viewfinder.width
            val availableHeight = viewfinder.height
            if (availableWidth == 0 || availableHeight == 0) return@post

            var targetWidth = availableWidth
            var targetHeight = (targetWidth / aspect).toInt()
            if (targetHeight > availableHeight) {
                targetHeight = availableHeight
                targetWidth = (targetHeight * aspect).toInt()
            }

            applySize(preview, targetWidth, targetHeight)
            applySize(overlay, targetWidth, targetHeight)
        }
    }

    private fun applySize(view: View, width: Int, height: Int) {
        val params = view.layoutParams as FrameLayout.LayoutParams
        params.width = width
        params.height = height
        params.gravity = android.view.Gravity.CENTER
        view.layoutParams = params
    }

    // ---- Planning / status ----

    private fun updatePlan() {
        val source = cameraEngine.sourceSize
        val quarterTurn = cameraEngine.sensorRotation % 180 != 0
        val uprightWidth = if (quarterTurn) source.height else source.width
        val uprightHeight = if (quarterTurn) source.width else source.height

        lastPlan = Planner.plan(
            Planner.formatsFor(formatMode), quality, uprightWidth, uprightHeight
        )

        if (lastPlan.isEmpty()) {
            statusText.text = "This phone's encoder refused every size. Try 720p."
            return
        }

        val described = lastPlan.joinToString("   ") {
            "${it.format.label} ${it.displayWidth}x${it.displayHeight}"
        }
        val wanted = Planner.formatsFor(formatMode).size
        val note = if (lastPlan.size < wanted) {
            "\nOnly one format at a time: this chip allows ${EncoderCaps.maxInstances()} video encoder(s)."
        } else {
            ""
        }
        statusText.text = described + note
    }

    private fun changeQuality(value: Quality) {
        if (isRecording) return
        quality = value
        applyQualitySelection()
        restartForNewSource()
    }

    private fun restartForNewSource() {
        cameraEngine.close()
        cameraOpen = false
        startCameraStack()
    }

    private fun flipCamera() {
        if (isRecording) return
        val facings = cameraEngine.availableFacings()
        if (facings.size < 2) {
            Toast.makeText(this, "This phone has only one camera.", Toast.LENGTH_SHORT).show()
            return
        }
        useFront = !useFront
        torchOn = false
        flashBtn.setImageResource(R.drawable.ic_flash_off)
        restartForNewSource()
    }

    private fun changeZoom(value: Float) {
        zoom = value
        cameraEngine.setZoom(value)
        applyZoomSelection()
    }

    private fun changeLook(value: Int) {
        look = value
        renderEngine.setLook(value)
        applyLookSelection()
    }

    // ---- Capture ----

    private fun onShutter() {
        if (videoMode) {
            if (isRecording) stopRecording(showToast = true) else withTimer { startRecording() }
        } else {
            withTimer { capturePhoto() }
        }
    }

    private fun withTimer(action: () -> Unit) {
        if (timerSeconds <= 0) {
            action()
            return
        }
        var remaining = timerSeconds
        countdownText.visibility = View.VISIBLE
        countdownText.text = remaining.toString()

        val tick = object : Runnable {
            override fun run() {
                remaining--
                if (remaining <= 0) {
                    countdownText.visibility = View.GONE
                    pendingCountdown = null
                    action()
                } else {
                    countdownText.text = remaining.toString()
                    mainHandler.postDelayed(this, 1000)
                }
            }
        }
        pendingCountdown = tick
        mainHandler.postDelayed(tick, 1000)
    }

    private fun capturePhoto() {
        if (!cameraOpen) {
            Toast.makeText(this, "Camera is not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        cameraEngine.captureStill()
    }

    override fun onStillCaptured(jpeg: ByteArray, imageWidth: Int, imageHeight: Int) {
        val formats = Planner.formatsFor(formatMode)
        val rotation = cameraEngine.sensorRotation
        val mirror = useFront && mirrorFront
        val currentLook = look

        worker.execute {
            val saved = PhotoProcessor.process(
                this, jpeg, imageWidth, imageHeight, rotation, mirror, formats, currentLook
            )
            mainHandler.post {
                if (saved.isEmpty()) {
                    Toast.makeText(this, "Could not save the photo.", Toast.LENGTH_SHORT).show()
                } else {
                    val label = saved.joinToString(" + ") { it.first.label }
                    Toast.makeText(
                        this, "Saved $label to Pictures/DualView", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun startRecording() {
        if (isRecording || !cameraOpen) return
        if (lastPlan.isEmpty()) {
            Toast.makeText(this, "No recordable size on this phone.", Toast.LENGTH_LONG).show()
            return
        }
        if (recordAudio && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSIONS)
            return
        }

        val newRecorder = DualRecorder(this, lastPlan, recordAudio)
        val failure = newRecorder.start()
        if (failure != null) {
            Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
            statusText.text = failure
            return
        }

        renderEngine.beginRecording(newRecorder) { error ->
            if (error != null) {
                newRecorder.stop()
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                return@beginRecording
            }
            recorder = newRecorder
            isRecording = true
            keepScreenOn(true)
            showRecordingUi(true)
            mainHandler.post(timerTick)
        }
    }

    private fun stopRecording(showToast: Boolean) {
        if (!isRecording) return
        isRecording = false
        mainHandler.removeCallbacks(timerTick)
        showRecordingUi(false)
        keepScreenOn(false)

        renderEngine.endRecording { saved ->
            recorder = null
            if (!showToast) return@endRecording
            if (saved.isEmpty()) {
                Toast.makeText(
                    this, "The recording could not be saved.", Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Saved ${saved.size} video${if (saved.size > 1) "s" else ""} to Movies/DualView",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun togglePause() {
        val active = recorder ?: return
        val nowPaused = !active.isPaused
        active.setPaused(nowPaused)
        pauseBtn.setImageResource(if (nowPaused) R.drawable.ic_resume else R.drawable.ic_pause)
    }

    private fun recDotBlink(paused: Boolean) {
        val dot = findViewById<View>(R.id.recDot)
        dot.alpha = if (paused) 0.35f else 1f
    }

    private fun showRecordingUi(recording: Boolean) {
        recBadge.visibility = if (recording) View.VISIBLE else View.GONE
        pauseBtn.visibility = if (recording) View.VISIBLE else View.GONE
        stillBtn.visibility = if (recording) View.VISIBLE else View.GONE
        galleryBtn.visibility = if (recording) View.GONE else View.VISIBLE
        flipBtn.visibility = if (recording) View.GONE else View.VISIBLE
        findViewById<View>(R.id.modeGroup).visibility = if (recording) View.INVISIBLE else View.VISIBLE
        shutterBtn.setBackgroundResource(
            if (recording) R.drawable.shutter_video_active else R.drawable.shutter_video_idle
        )
        if (!recording) {
            recTime.text = "00:00"
            pauseBtn.setImageResource(R.drawable.ic_pause)
        }
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---- Selection state ----

    private fun applyFormatMode(mode: FormatMode) {
        if (isRecording) return
        formatMode = mode
        overlay.formatMode = mode
        fmtVertical.isSelected = mode == FormatMode.VERTICAL_ONLY
        fmtBoth.isSelected = mode == FormatMode.BOTH
        fmtHorizontal.isSelected = mode == FormatMode.HORIZONTAL_ONLY
        updatePlan()
    }

    private fun applyMode(video: Boolean) {
        videoMode = video
        modePhoto.isSelected = !video
        modeVideo.isSelected = video
        shutterBtn.setBackgroundResource(
            if (video) R.drawable.shutter_video_idle else R.drawable.shutter_photo
        )
    }

    private fun applyQualitySelection() {
        findViewById<View>(R.id.q720).isSelected = quality == Quality.HD
        findViewById<View>(R.id.q1080).isSelected = quality == Quality.FHD
        findViewById<View>(R.id.q2160).isSelected = quality == Quality.UHD
    }

    private fun applyTimerSelection() {
        findViewById<View>(R.id.t0).isSelected = timerSeconds == 0
        findViewById<View>(R.id.t3).isSelected = timerSeconds == 3
        findViewById<View>(R.id.t10).isSelected = timerSeconds == 10
    }

    private fun applyLookSelection() {
        findViewById<View>(R.id.lookNatural).isSelected = look == TextureProgram.LOOK_NATURAL
        findViewById<View>(R.id.lookMono).isSelected = look == TextureProgram.LOOK_MONO
        findViewById<View>(R.id.lookWarm).isSelected = look == TextureProgram.LOOK_WARM
        findViewById<View>(R.id.lookVivid).isSelected = look == TextureProgram.LOOK_VIVID
    }

    private fun applyZoomSelection() {
        findViewById<View>(R.id.zoom1).isSelected = zoom == 1f
        findViewById<View>(R.id.zoom2).isSelected = zoom == 2f
        findViewById<View>(R.id.zoom3).isSelected = zoom == 3f
    }

    // ---- Diagnostics ----

    private fun showDiagnostics() {
        val report = EncoderCaps.report()
        val source = cameraEngine.sourceSize
        val quarterTurn = cameraEngine.sensorRotation % 180 != 0
        val uprightWidth = if (quarterTurn) source.height else source.width
        val uprightHeight = if (quarterTurn) source.width else source.height
        val still = cameraEngine.stillSize()

        val builder = StringBuilder()
        builder.append("Phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        builder.append("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n")

        builder.append("CAMERA\n")
        builder.append("Facing: ${if (useFront) "front" else "back"}\n")
        builder.append("Sensor rotation: ${cameraEngine.sensorRotation}\u00B0\n")
        builder.append("Stream in use: ${source.width}x${source.height}\n")
        builder.append("Upright frame: ${uprightWidth}x${uprightHeight}\n")
        builder.append("Photo size: ${still?.width ?: 0}x${still?.height ?: 0}\n\n")

        builder.append("VIDEO ENCODER\n")
        if (report == null) {
            builder.append("No H.264 encoder reported.\n\n")
        } else {
            builder.append("Codec: ${report.codecName}\n")
            builder.append("Max frame: ${report.maxWidth}x${report.maxHeight}\n")
            builder.append("Alignment: ${report.widthAlignment}x${report.heightAlignment}\n")
            builder.append("Encoders at once: ${report.maxInstances}\n\n")
        }

        builder.append("PLANNED OUTPUT (${quality.label})\n")
        if (lastPlan.isEmpty()) {
            builder.append("Nothing recordable at this quality.\n")
        } else {
            for (spec in lastPlan) {
                builder.append("${spec.format.label}: ${spec.displayWidth}x${spec.displayHeight}")
                builder.append(" @ ${spec.bitRate / 1_000_000} Mbps")
                if (spec.extraRotation != 0) {
                    builder.append(" (encoded ${spec.encWidth}x${spec.encHeight}, rotated on playback)")
                }
                builder.append("\n")
            }
        }

        builder.append("\nSUPPORTED CAMERA SIZES\n")
        val sizes = cameraEngine.supportedOutputSizes()
            .sortedByDescending { it.width.toLong() * it.height }
            .take(12)
        for (size in sizes) builder.append("${size.width}x${size.height}  ")

        AlertDialog.Builder(this)
            .setTitle("Camera diagnostics")
            .setMessage(builder.toString())
            .setPositiveButton("Close", null)
            .show()
    }

    /** If the last run ended badly, show why, so the reason can be reported without a PC. */
    private fun showPendingCrashReport() {
        val report = CrashLogger.pendingReport(this) ?: return
        CrashLogger.clear(this)
        AlertDialog.Builder(this)
            .setTitle("DualView closed unexpectedly")
            .setMessage(report)
            .setPositiveButton("Copy details") { _, _ ->
                CrashLogger.copyToClipboard(this, report)
                Toast.makeText(this, "Crash details copied.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun openGallery() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        } catch (t: Throwable) {
            Toast.makeText(this, "No gallery app found.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 41
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
