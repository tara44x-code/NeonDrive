package com.neondrive.emu.game

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import com.neondrive.emu.CrashReporter
import com.neondrive.emu.R
import com.neondrive.emu.data.MediaSaver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Foreground service that owns the screen capture while a game is being recorded (Android 10+). */
@RequiresApi(29)
class RecordService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_AUDIO = "audio"
        private const val CHANNEL = "recording"

        val recording = MutableStateFlow(false)
        val events = MutableSharedFlow<String>(extraBufferCapacity = 4)

        fun start(context: Context, resultCode: Int, data: Intent, withAudio: Boolean) {
            context.startForegroundService(
                Intent(context, RecordService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data).putExtra(EXTRA_AUDIO, withAudio)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordService::class.java).setAction(ACTION_STOP))
        }
    }

    private var recorder: ScreenRecorder? = null
    private var outFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        // Must be in the foreground (type mediaProjection) before asking for the projection.
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        try {
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(code, data!!)
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (recorder != null) stopRecording() }
            }, Handler(Looper.getMainLooper()))

            val name = "NeonDrive_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(cacheDir, "$name.mp4")
            outFile = file
            val withSound = ScreenRecorder(this, projection, file).let { rec ->
                recorder = rec
                rec.start(intent.getBooleanExtra(EXTRA_AUDIO, true))
            }
            recording.value = true
            CrashReporter.crumb("recording started (sound=$withSound)")
            events.tryEmit(if (withSound) "🔴 بدأ التسجيل" else "🔴 بدأ التسجيل (بدون صوت)")
        } catch (e: Exception) {
            CrashReporter.crumb("recording failed: ${e.message}")
            events.tryEmit("تعذر بدء التسجيل")
            recording.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: run { stopSelf(); return }
        recorder = null
        val file = outFile
        thread(name = "rec-finish") {
            val ok = runCatching { rec.stop() }.getOrDefault(false)
            val saved = ok && file != null && MediaSaver.saveVideo(this, file, file.nameWithoutExtension)
            file?.delete()
            recording.value = false
            events.tryEmit(if (saved) "تم حفظ الفيديو بالمعرض 🎬 (Movies/NeonDrive)" else "تعذر حفظ الفيديو")
            CrashReporter.crumb("recording stopped saved=$saved")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "تسجيل اللعب", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NeonDrive")
            .setContentText("جاري تسجيل اللعب…")
            .setOngoing(true)
            .build()
    }
}
