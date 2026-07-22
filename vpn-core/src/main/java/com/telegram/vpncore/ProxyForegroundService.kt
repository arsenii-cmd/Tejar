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
                val config = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CONFIG, VpnConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CONFIG)
                }
                startForegroundCompat(config)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // Sticky/redeliver restart with no usable intent: the in-process xray core
                // is NOT owned by this service, so we can't resurrect the tunnel here. Stop
                // cleanly instead of showing a "connected" notification with no proxy running.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // NOT_STICKY: on kill, don't let Android restart us with a null intent (which would
        // show a fake "active" notification while xray is dead). VpnProxyManager owns restart.
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(config: VpnConfig?) {
        // The specialUse FGS type constant is only valid on API 34+. Below that, the manifest
        // type is ignored and a plain startForeground is correct (no FGS timeout exists there).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(config),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(config))
        }
    }

    /**
     * Android 14+ may time out even specialUse FGS in edge cases; keep the notification alive
     * without crashing (default behaviour throws). The tunnel itself keeps running in-process.
     */
    override fun onTimeout(startId: Int) {
        // Do nothing — do not stop the service or the proxy. Swallowing the timeout keeps
        // the process foreground-privileged instead of triggering the default RemoteServiceException.
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only stop xray core, do NOT call stopProxy() — that would send ACTION_STOP
        // back to this service (recursive loop). The manager handles state independently.
    }

    private fun buildNotification(config: VpnConfig?): Notification {
        val stopIntent = Intent(this, ProxyForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Deliberately no server address/port here — this notification is visible on the
        // lockscreen without unlocking the device.
        val title = "VPN Proxy Active"
        val text = if (config != null) config.protocolLabel else "Connected"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock) // Replace with custom icon
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
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
