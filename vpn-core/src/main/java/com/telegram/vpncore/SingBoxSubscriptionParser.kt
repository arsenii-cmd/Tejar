package com.telegram.vpncore

import android.util.Log
import org.json.JSONObject

/**
 * Reads a sing-box-format subscription (a full sing-box config document) and turns its
 * outbounds into [VpnConfig] entries.
 *
 * This is the format AxiOm v2 uses. The panel emits proxies the core can consume directly,
 * so each outbound is kept verbatim in [VpnConfig.rawOutbound] rather than being taken apart
 * and rebuilt. The parsed fields alongside it exist only for the UI (name, address, port).
 *
 * It also carries protocols the link format drops — most importantly Naive, which the panel's
 * base64-links generator omits entirely.
 */
object SingBoxSubscriptionParser {

    private const val TAG = "SingBoxSubParser"

    /** Outbound types that are routing constructs, not servers the user can pick. */
    private val NON_SERVER_TYPES = setOf("selector", "urltest", "direct", "block", "dns")

    /** True when [text] looks like a sing-box config document rather than a list of links. */
    fun looksLikeSingBoxConfig(text: String): Boolean {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return false
        return try {
            JSONObject(trimmed).has("outbounds")
        } catch (_: Exception) {
            false
        }
    }

    fun parse(text: String): List<VpnConfig> {
        val outbounds = JSONObject(text).optJSONArray("outbounds") ?: return emptyList()
        val results = mutableListOf<VpnConfig>()
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val type = outbound.optString("type")
            if (type.isBlank() || type in NON_SERVER_TYPES) continue
            // A server outbound must say where to dial; anything else is a construct we
            // don't recognise and would only show as a dead entry in the list.
            val server = outbound.optString("server")
            val serverPort = outbound.optInt("server_port")
            if (server.isBlank() || serverPort == 0) {
                Log.w(TAG, "Skipping outbound '$type' without server/server_port")
                continue
            }
            results.add(
                VpnConfig(
                    name = outbound.optString("tag").ifBlank { "$server:$serverPort" },
                    protocol = type.toVpnProtocol(),
                    address = server,
                    port = serverPort,
                    rawOutbound = outbound.toString(),
                    rawType = type
                )
            )
        }
        Log.d(TAG, "Parsed ${results.size} outbounds from sing-box subscription")
        return results
    }

    private fun String.toVpnProtocol(): VpnProtocol = when (this) {
        "vless" -> VpnProtocol.VLESS
        "vmess" -> VpnProtocol.VMESS
        "shadowsocks" -> VpnProtocol.SHADOWSOCKS
        "trojan" -> VpnProtocol.TROJAN
        "hysteria2" -> VpnProtocol.HYSTERIA2
        "http" -> VpnProtocol.NAIVE
        // Unknown types still work — the core gets the outbound verbatim — so they are kept
        // rather than dropped; only the label falls back to the raw type name.
        else -> VpnProtocol.OTHER
    }
}
