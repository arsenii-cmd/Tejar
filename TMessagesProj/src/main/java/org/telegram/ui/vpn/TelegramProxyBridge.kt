package org.telegram.ui.vpn

import android.util.Log
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.ConnectionsManager

object TelegramProxyBridge {

    private const val TAG = "TelegramProxyBridge"

    fun enableProxy(host: String, port: Int) {
        try {
            val proxyInfo = SharedConfig.ProxyInfo(host, port, "", "", "")
            SharedConfig.addProxy(proxyInfo)
            SharedConfig.currentProxy = proxyInfo
            SharedConfig.saveConfig()

            ConnectionsManager.setProxySettings(true, host, port, "", "", "")

            NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.proxySettingsChanged)

            Log.i(TAG, "Proxy enabled: $host:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable proxy", e)
        }
    }

    fun disableProxy() {
        try {
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
