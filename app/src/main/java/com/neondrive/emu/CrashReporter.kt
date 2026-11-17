package com.neondrive.emu

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects a readable report after the app closes unexpectedly:
 *  - Java/Kotlin crashes (full stack trace, via the default exception handler)
 *  - native crashes, out-of-memory kills and freezes (Android 11+ exit reasons)
 *  - "breadcrumbs": the last steps the app took (game, core, ratio, cheats...) before it died.
 */
object CrashReporter {
    private lateinit var dir: File
    private val crumbs = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun install(app: Application) {
        dir = File(app.filesDir, "diagnostics").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter(); error.printStackTrace(PrintWriter(sw))
                File(dir, "java_crash.txt").writeText("Thread: ${thread.name}\n$sw")
                flushCrumbs()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records a step; kept on disk so it survives native crashes. */
    @Synchronized
    fun crumb(text: String) {
        if (!::dir.isInitialized) return
        crumbs.addLast("${time.format(Date())}  $text")
        while (crumbs.size > 40) crumbs.removeFirst()
        flushCrumbs()
    }

    private fun flushCrumbs() = runCatching { File(dir, "crumbs.txt").writeText(crumbs.joinToString("\n")) }

    /** Returns a report if the previous session ended in a crash we haven't shown yet. */
    fun pendingReport(context: Context): String? {
        if (!::dir.isInitialized) return null
        val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val sb = StringBuilder()

        val javaCrash = File(dir, "java_crash.txt")
        if (javaCrash.exists()) {
            sb.appendLine("== Java crash ==").appendLine(javaCrash.readText().take(6000))
            javaCrash.delete()
        }

        if (Build.VERSION.SDK_INT >= 30) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val lastSeen = prefs.getLong("lastExit", 0L)
            val bad = setOf(
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            )
            val exit = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull()
                ?.firstOrNull { it.timestamp > lastSeen }
            if (exit != null) {
                prefs.edit().putLong("lastExit", exit.timestamp).apply()
                if (exit.reason in bad) {
                    sb.appendLine("== Exit ==")
                    sb.appendLine("reason=${reasonName(exit.reason)} status=${exit.status}")
                    sb.appendLine("description=${exit.description}")
                    sb.appendLine("pss=${exit.pss / 1024}MB rss=${exit.rss / 1024}MB")
                    if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                        runCatching { exit.traceInputStream?.bufferedReader()?.use { sb.appendLine(it.readText().take(4000)) } }
                    }
                }
            }
        }

        if (sb.isEmpty()) return null
        val crumbsFile = File(dir, "crumbs.txt")
        sb.appendLine("== Last steps ==")
        sb.appendLine(if (crumbsFile.exists()) crumbsFile.readText() else "-")
        sb.appendLine("== Device ==")
        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("App ${BuildConfig.VERSION_NAME}")
        return sb.toString()
    }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR (freeze)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        else -> r.toString()
    }
}

class NeonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Apply the saved theme before any screen is drawn.
        val settings = com.neondrive.emu.data.Settings(this)
        com.neondrive.emu.ui.Neon.ui = settings.uiPalette
        com.neondrive.emu.ui.Neon.pad = settings.padPalette
        // Unpack the offline cheat database in the background on first launch / after updates.
        Thread { runCatching { com.neondrive.emu.data.OfflineDb.ensureCheats(this) } }.apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
