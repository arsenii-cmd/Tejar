package com.telegram.vpncore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restores the active VPN proxy connection after device reboot.
 * On Android 12+ the foreground service may not start immediately,
 * but the proxy will run and the service will start once the app is foregrounded.
 */
class VpnBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "VpnBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val repo = VpnConfigRepository(context)
        val active = repo.getActive() ?: return
        try {
            val manager = VpnProxyManager.getInstance(context)
            // Restore auto-reconnect preference
            val prefs = context.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)
            manager.autoReconnect = prefs.getBoolean("auto_reconnect", false)
            manager.startProxy(active)
            Log.d(TAG, "Boot restore initiated for ${active.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore proxy on boot", e)
        }
    }
}
