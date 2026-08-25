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
import com.vikash.voicescribe.MainActivity
import com.vikash.voicescribe.R

/**
 * Foreground service that holds process priority while the TranscriptionEngine
 * works, so a lecture-length transcription survives the user backgrounding the
 * app. The engine owns the queue and the work; this service is only the
 * lifecycle anchor — started when work begins, stopped when the queue drains.
 */
class TranscribeService : Service() {

    companion object {
        private const val CHANNEL_ID = "processing"
        private const val NOTIF_ID = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TranscribeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TranscribeService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transcription", NotificationManager.IMPORTANCE_LOW)
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Transcribing on this device…")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
        val type = when {
            // MEDIA_PROCESSING (API 35+) is the intended type for on-device transcription
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        if (type != 0) startForeground(NOTIF_ID, notification, type)
        else startForeground(NOTIF_ID, notification)
        return START_NOT_STICKY
    }
}
