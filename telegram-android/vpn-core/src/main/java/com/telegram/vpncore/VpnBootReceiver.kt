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
        // Восстановление VPN-состояния теперь полностью выполняется в
        // ApplicationLoader.onCreate() (глобальный ConnectionListener подключается
        // там же до старта прокси). Этот receiver нужен только чтобы разбудить
        // процесс на BOOT_COMPLETED — Application.onCreate() вызывается системой
        // раньше, чем onReceive(), и уже делает всю работу; повторный вызов
        // startProxy() отсюда приводил к гонке (двойной запуск).
        Log.d(TAG, "Boot completed, app process started (restore handled in ApplicationLoader)")
    }
}
