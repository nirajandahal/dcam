package com.dualview.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Saves the stack trace of a crash to a file so the next launch can show it. Camera apps
 * fail in device-specific ways, and reading the reason off the phone beats guessing.
 */
object CrashLogger {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val text = buildString {
                    append("${Build.MANUFACTURER} ${Build.MODEL}\n")
                    append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                    append("Hardware: ${Build.HARDWARE}\n")
                    append("Thread: ${thread.name}\n\n")
                    append(writer.toString())
                }
                File(appContext.filesDir, FILE_NAME).writeText(text)
            } catch (t: Throwable) {
                // Never let the reporter mask the original crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                exitProcess(2)
            }
        }
    }

    fun pendingReport(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            file.readText()
        } catch (t: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        try {
            val manager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            manager.setPrimaryClip(ClipData.newPlainText("DualView crash", text))
        } catch (t: Throwable) {
            // Ignore.
        }
    }
}
