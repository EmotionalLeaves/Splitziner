package io.github.emotionalleaves.splitziner

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The owner has no computer to attach a debugger, so the app keeps its own evidence:
 * a short log, the last crash, and one button that copies it all as text.
 */
object Diagnostics {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private var crashFile: File? = null

    @Synchronized fun log(message: String) {
        lines.addLast("${System.currentTimeMillis() % 100_000_000} $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    fun logError(where: String, e: Throwable) = log("ERROR in $where: ${stack(e).take(1500)}")

    private fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    fun install(context: Context) {
        val file = File(context.filesDir, "last_crash.txt")
        crashFile = file
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file.writeText("Crash on thread ${thread.name}\n${stack(e)}\n\nLog:\n${snapshot()}") }
            previous?.uncaughtException(thread, e)
        }
    }

    fun lastCrash(): String? = crashFile?.takeIf { it.exists() }?.readText()
    fun clearCrash() { crashFile?.delete() }

    @Synchronized private fun snapshot() = lines.joinToString("\n")

    fun report(extra: String): String = buildString {
        append("Splitziner ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Memory limit: ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB\n\n")
        if (extra.isNotBlank()) append(extra).append("\n\n")
        lastCrash()?.let { append("Last crash:\n").append(it.take(6000)).append("\n\n") }
        append("Log:\n").append(snapshot())
    }
}
