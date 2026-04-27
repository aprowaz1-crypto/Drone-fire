package com.example.dronefire

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AudioMonitorService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var processor: AudioProcessor? = null

    override fun onBind(intent: Intent?): IBinder? = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            createNotificationChannel()
            when (intent?.action) {
                ACTION_START_MONITORING -> {
                    try {
                        startForeground(NOTIFICATION_ID, buildNotification("Моніторинг активний"))
                        startAudioMonitoring()
                        sendUpdate("Ініціалізовано", "", 0f, false, "Сервіс запущено успішно")
                    } catch (e: Exception) {
                        val errorMsg = "Помилка запуску: ${e.message}\\n${e.javaClass.simpleName}"
                        sendUpdate("ПОМИЛКА", "", 0f, false, errorMsg)
                    }
                }
                ACTION_STOP_MONITORING -> stopSelf()
            }
        } catch (e: Exception) {
            sendUpdate("КРИТИЧНА", "", 0f, false, "onStartCommand: ${e.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        processor?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startAudioMonitoring() {
        try {
            processor = AudioProcessor(this) { status, spectrum, azimuth, alarm, logMessage ->
                try {
                    sendUpdate(status, spectrum, azimuth, alarm, logMessage)
                    if (alarm) {
                        sendAlarmNotification(azimuth)
                        vibrateAlert()
                    }
                } catch (e: Exception) {
                    sendUpdate("Помилка Update", "", 0f, false, "updateCallback: ${e.message}")
                }
            }
            processor?.start()
        } catch (e: Exception) {
            val details = "AudioProcessor init: ${e.message}\\n${e.javaClass.simpleName}"
            sendUpdate("Помилка запуску", "", 0f, false, details)
        }
    }

    private fun sendUpdate(status: String, spectrum: String, azimuth: Float, alarm: Boolean, logMessage: String) {
        val intent = Intent(ACTION_AUDIO_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_SPECTRUM, spectrum)
            putExtra(EXTRA_AZIMUTH, azimuth)
            putExtra(EXTRA_ALARM, alarm)
            putExtra(EXTRA_LOG, logMessage)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Drone-fire мониторинг")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun sendAlarmNotification(azimuth: Float) {
        val fullScreenIntent = Intent(this, MainActivity::class.java)
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            1,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("ТРЕВОГА")
            .setContentText("Азимут: ${azimuth.toInt()}°")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ALARM_NOTIFICATION_ID, notification)
    }

    private fun vibrateAlert() {
        val vibrator = getSystemService<Vibrator>()
        vibrator?.vibrate(500)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Drone-fire моніторинг",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioMonitorService = this@AudioMonitorService
    }

    companion object {
        const val ACTION_START_MONITORING = "com.example.dronefire.action.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.example.dronefire.action.STOP_MONITORING"
        const val ACTION_AUDIO_UPDATE = "com.example.dronefire.action.AUDIO_UPDATE"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_SPECTRUM = "extra_spectrum"
        const val EXTRA_AZIMUTH = "extra_azimuth"
        const val EXTRA_ALARM = "extra_alarm"
        const val EXTRA_LOG = "extra_log"
        private const val CHANNEL_ID = "drone_fire_monitor_channel"
        private const val NOTIFICATION_ID = 101
        private const val ALARM_NOTIFICATION_ID = 102
    }
}
