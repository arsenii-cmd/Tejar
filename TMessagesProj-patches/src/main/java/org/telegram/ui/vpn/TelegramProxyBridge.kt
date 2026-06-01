package org.telegram.ui.vpn

import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.ConnectionsManager

/**
 * Bridge between VpnProxyManager and Telegram's internal proxy system.
 *
 * Uses the same pattern as ProxyListActivity:
 *  1. SharedConfig.currentProxy — Java-side config object
 *  2. MessagesController.getGlobalMainSettings() — persists proxy_enabled flag
 *  3. ConnectionsManager.setProxySettings() — updates C++ native layer for all accounts
 *  4. NotificationCenter.getGlobalInstance().postNotificationName() — refreshes UI
 */
object TelegramProxyBridge {

    /**
     * Activates SOCKS5 proxy pointing to our local xray-core instance.
     */
    fun enableProxy(host: String, port: Int) {
        try {
            // 1. Set current proxy object
            SharedConfig.currentProxy = SharedConfig.ProxyInfo(host, port, "", "", "")

            // 2. Persist proxy_enabled flag (same as ProxyListActivity)
            MessagesController.getGlobalMainSettings().edit()
                .putBoolean("proxy_enabled", true)
                .commit()

            // 3. Push to C++ native layer for all accounts
            ConnectionsManager.setProxySettings(true, host, port, "", "", "")

            // 4. Notify UI (proxy icon in header, etc.)
            NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.proxySettingsChanged)
        } catch (e: Exception) {
            android.util.Log.e("TelegramProxyBridge", "Failed to enable proxy", e)
        }
    }

    /**
     * Disables proxy, restoring direct connectivity.
     */
    fun disableProxy() {
        try {
            MessagesController.getGlobalMainSettings().edit()
                .putBoolean("proxy_enabled", false)
                .commit()

            ConnectionsManager.setProxySettings(false, "", 0, "", "", "")

            NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.proxySettingsChanged)
        } catch (e: Exception) {
            android.util.Log.e("TelegramProxyBridge", "Failed to disable proxy", e)
        }
    }
}
