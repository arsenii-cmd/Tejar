package org.telegram.ui.vpn

import android.util.Log
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.ConnectionsManager

object TelegramProxyBridge {

    private const val TAG = "TelegramProxyBridge"

    fun enableProxy(host: String, port: Int, username: String, password: String) {
        try {
            val proxyInfo = SharedConfig.ProxyInfo(host, port, username, password, "")
            SharedConfig.addProxy(proxyInfo)
            SharedConfig.currentProxy = proxyInfo
            SharedConfig.saveConfig()

            // Persist proxy state to "mainconfig" exactly like ProxyListActivity does.
            // ConnectionsManager.init() (per-account, on activation / DC re-init) reads these
            // keys; without proxy_enabled=true + proxy_ip/proxy_port, Telegram drops the proxy
            // on the next reconnect and silently connects direct while xray still listens
            // locally — the intermittent "proxy stops working" symptom.
            val editor = MessagesController.getGlobalMainSettings().edit()
            editor.putString("proxy_ip", host)
            editor.putString("proxy_user", username)
            editor.putString("proxy_pass", password)
            editor.putString("proxy_secret", "")
            editor.putInt("proxy_port", port)
            editor.putBoolean("proxy_enabled", true)
            editor.commit()

            ConnectionsManager.setProxySettings(true, host, port, username, password, "")

            NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.proxySettingsChanged)

            Log.i(TAG, "Proxy enabled: $host:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable proxy", e)
        }
    }

    fun disableProxy() {
        try {
            // Clear the persisted flag too, so init() doesn't re-apply a stale proxy.
            val editor = MessagesController.getGlobalMainSettings().edit()
            editor.putBoolean("proxy_enabled", false)
            editor.commit()

            ConnectionsManager.setProxySettings(false, "", 1080, "", "", "")
            SharedConfig.saveConfig()

            NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.proxySettingsChanged)

            Log.i(TAG, "Proxy disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable proxy", e)
        }
    }
}
