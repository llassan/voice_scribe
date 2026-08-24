package com.vikash.voicescribe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.vikash.voicescribe.App
import com.vikash.voicescribe.MainActivity
import com.vikash.voicescribe.R
import com.vikash.voicescribe.audio.WavRecorder
import com.vikash.voicescribe.data.Recording
import com.vikash.voicescribe.data.TranscriptStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordingSession(
    val recordingId: String,
    val elapsedMs: Long = 0L,
    val amplitude: Float = 0f,
)

/**
 * Foreground microphone service so lecture-length recordings survive
 * the screen locking or the app being backgrounded.
 */
class RecorderService : Service() {

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        const val ACTION_START = "com.vikash.voicescribe.START"
        const val ACTION_STOP = "com.vikash.voicescribe.STOP"

        private val _session = MutableStateFlow<RecordingSession?>(null)
        val session: StateFlow<RecordingSession?> = _session

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RecorderService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecorderService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: WavRecorder? = null
    private var recordingId: String? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (recorder == null) startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        val app = application as App
        val id = app.store.newId()
        val wav = app.store.wavFileFor(id)
        recordingId = id
        startedAt = SystemClock.elapsedRealtime()

        startForegroundCompat()

        val rec = WavRecorder()
        recorder = rec
        try {
            rec.start(wav) { amp ->
                _session.value = _session.value?.copy(amplitude = amp)
                    ?: RecordingSession(id, 0, amp)
            }
        } catch (t: Throwable) {
            recorder = null
            stopSelf()
            return
        }
        _session.value = RecordingSession(id)

        serviceScope.launch {
            while (isActive) {
                _session.value = _session.value?.copy(
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt
                )
                delay(500)
            }
        }
    }

    private fun stopRecording() {
        val app = application as App
        val rec = recorder
        val id = recordingId
        recorder = null
        recordingId = null
        if (rec != null && id != null) {
            val durationMs = rec.stop()
            val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            // Discard sub-second accidental taps
            if (durationMs >= 1000) {
                app.store.upsert(
                    Recording(
                        id = id,
                        title = fmt.format(Date()),
                        createdAt = System.currentTimeMillis(),
                        durationMs = durationMs,
                        wavPath = app.store.wavFileFor(id).absolutePath,
                        status = TranscriptStatus.NONE,
                    )
                )
                app.engine.enqueue(id)
            } else {
                app.store.wavFileFor(id).delete()
            }
        }
        _session.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Recording…")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        // Safety net: finalize the file if the service dies unexpectedly.
        recorder?.stop()
        recorder = null
        _session.value = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
