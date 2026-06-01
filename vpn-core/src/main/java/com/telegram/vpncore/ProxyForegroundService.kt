package com.telegram.vpncore

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground Service that keeps xray-core alive when the app is backgrounded.
 * Does NOT use VpnService — only holds a wakelock-equivalent via foreground notification.
 */
class ProxyForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.telegram.vpncore.START"
        const val ACTION_STOP = "com.telegram.vpncore.STOP"
        const val EXTRA_CONFIG = "extra_config"

        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "vpn_proxy_channel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_CONFIG, VpnConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CONFIG)
                }
                startForeground(NOTIFICATION_ID, buildNotification(config))
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun buildNotification(config: VpnConfig?): Notification {
        val stopIntent = Intent(this, ProxyForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (config != null) "VPN Proxy: ${config.displayName}" else "VPN Proxy Active"
        val text = if (config != null) "${config.protocolLabel} · ${config.address}:${config.port}" else "Connected"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock) // Replace with custom icon
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Disconnect", stopPi)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Proxy Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when the VPN proxy is active"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
