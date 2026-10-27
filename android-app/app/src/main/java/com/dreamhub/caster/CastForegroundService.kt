package com.dreamhub.caster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class CastForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "dreamhub_cast_channel"
        const val NOTIFICATION_ID = 8801
        const val ACTION_START = "com.dreamhub.caster.ACTION_START"
        const val ACTION_TOGGLE_PLAY = "com.dreamhub.caster.ACTION_TOGGLE_PLAY"
        const val ACTION_STOP = "com.dreamhub.caster.ACTION_STOP"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY -> {
                Thread {
                    val nextState = !DlnaCaster.isPlaying
                    DlnaCaster.playOrPause(nextState)
                    updateNotification()
                }.start()
            }
            ACTION_STOP -> {
                Thread {
                    DlnaCaster.stop()
                }.start()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_STICKY
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, CastForegroundService::class.java).apply {
            action = ACTION_TOGGLE_PLAY
        }
        val togglePending = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CastForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val tvName = DlnaCaster.activeDevice?.name ?: "Smart TV"
        val title = DlnaCaster.currentStreamTitle.ifBlank { "กำลังส่งสตรีมวิดีโอขึ้นทีวี" }
        val playPauseLabel = if (DlnaCaster.isPlaying) "พักการเล่น" else "เล่นต่อ"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("กำลังแคสต์: $tvName")
            .setContentText("$title • ล็อกหน้าจอมือถือได้")
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, playPauseLabel, togglePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "หยุดแคสต์", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DreamHubCaster::ProxyWakeLock").apply {
                acquire(6 * 60 * 60 * 1000L) // Up to 6 hours
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DreamHubCaster::ProxyWifiLock").apply {
                acquire()
            }
        } catch (e: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DreamHub TV Casting Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "รักษาระบบส่งวิดีโอขึ้นทีวีขณะปิดหน้าจอมือถือ"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
