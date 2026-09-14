package com.dualview.camera

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lists captures the app is holding on to. Only relevant when "Save to gallery
 * automatically" is off — otherwise everything goes straight to the phone's gallery and
 * this screen stays empty.
 */
class CapturesActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyView: View
    private val store by lazy { CaptureStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_captures)
        container = findViewById(R.id.capturesList)
        emptyView = findViewById(R.id.capturesEmpty)
        findViewById<View>(R.id.capturesBack).setOnClickListener { finish() }
        findViewById<View>(R.id.saveAllBtn).setOnClickListener { saveAll() }
        findViewById<View>(R.id.openGalleryBtn).setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    )
                )
            } catch (t: Throwable) {
                Toast.makeText(this, "No gallery app found.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val files = store.privateCaptures()
        container.removeAllViews()
        emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.saveAllBtn).visibility =
            if (files.isEmpty()) View.GONE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for (file in files) {
            val row = inflater.inflate(R.layout.item_capture, container, false)
            bindRow(row, file)
            container.addView(row)
        }
    }

    private fun bindRow(row: View, file: File) {
        val isVideo = file.name.endsWith(".mp4", true)
        row.findViewById<TextView>(R.id.captureName).text = describe(file)
        row.findViewById<TextView>(R.id.captureMeta).text = buildString {
            append(if (isVideo) "Video" else "Photo")
            append("  ·  ")
            append(formatSize(file.length()))
            append("  ·  ")
            append(SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(file.lastModified())))
        }

        val thumb = row.findViewById<ImageView>(R.id.captureThumb)
        worker.execute {
            val bitmap = Thumbnails.load(file)
            mainHandler.post { if (bitmap != null) thumb.setImageBitmap(bitmap) }
        }

        row.findViewById<View>(R.id.captureSave).setOnClickListener {
            worker.execute {
                val ok = store.exportToGallery(file)
                if (ok) file.delete()
                mainHandler.post {
                    Toast.makeText(
                        this,
                        if (ok) "Saved to gallery." else "Could not save that one.",
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh()
                }
            }
        }

        row.findViewById<View>(R.id.captureShare).setOnClickListener { share(file, isVideo) }

        row.findViewById<View>(R.id.captureDelete).setOnClickListener {
            file.delete()
            refresh()
        }
    }

    private fun describe(file: File): String = when {
        file.name.contains("9x16") -> "Vertical  9:16"
        file.name.contains("16x9") -> "Horizontal  16:9"
        else -> file.name
    }

    private fun share(file: File, isVideo: Boolean) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (isVideo) "video/mp4" else "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share capture"))
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not share that file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAll() {
        val files = store.privateCaptures()
        if (files.isEmpty()) return
        Toast.makeText(this, "Saving ${files.size}…", Toast.LENGTH_SHORT).show()
        worker.execute {
            var saved = 0
            for (file in files) {
                if (store.exportToGallery(file)) {
                    file.delete()
                    saved++
                }
            }
            mainHandler.post {
                Toast.makeText(this, "Saved $saved to gallery.", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1000.0)
    }
}
