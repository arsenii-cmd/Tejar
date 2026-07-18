package com.telegram.vpncore

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parses VPN share links into VpnConfig objects.
 *
 * Supported formats:
 *  - VLESS:  vless://UUID@host:port?type=...&security=...#name
 *  - VMess:  vmess://BASE64(json) — v2rayN format
 *  - SS:     ss://BASE64(method:password)@host:port#name
 *             or ss://BASE64(method:password@host:port)#name (legacy)
 *  - Trojan: trojan://password@host:port?sni=...#name
 */
object LinkParser {

    /**
     * Parse any supported VPN link. Throws [IllegalArgumentException] on failure.
     */
    fun parse(rawLink: String): VpnConfig {
        val trimmed = rawLink.trim()
        return when {
            trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed)
            trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
            trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
            trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed)
            else -> throw IllegalArgumentException("Unsupported protocol scheme: ${trimmed.substringBefore("://")}")
        }
    }

    // ─────────────────────────── VLESS ───────────────────────────

    private fun parseVless(link: String): VpnConfig {
        // vless://UUID@host:port?params#name
        val uri = Uri.parse(link)
        val uuid = uri.userInfo ?: throw IllegalArgumentException("VLESS: missing UUID")
        val host = uri.host ?: throw IllegalArgumentException("VLESS: missing host")
        val port = uri.port.takeIf { it > 0 } ?: throw IllegalArgumentException("VLESS: missing port")
        val name = uri.fragment?.urlDecode() ?: ""

        val networkStr = uri.getQueryParameter("type") ?: "tcp"
        val securityStr = uri.getQueryParameter("security") ?: "none"
        val flow = uri.getQueryParameter("flow") ?: ""
        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: ""
        val fingerprint = uri.getQueryParameter("fp") ?: "chrome"
        val publicKey = uri.getQueryParameter("pbk") ?: ""
        val shortId = uri.getQueryParameter("sid") ?: ""
        val spiderX = uri.getQueryParameter("spx")?.urlDecode() ?: ""
        val path = uri.getQueryParameter("path")?.urlDecode() ?: ""
        val wsHost = uri.getQueryParameter("host") ?: sni
        val serviceName = uri.getQueryParameter("serviceName") ?: ""

        return VpnConfig(
            name = name,
            protocol = VpnProtocol.VLESS,
            address = host,
            port = port,
            uuid = uuid,
            flow = flow,
            encryption = "none",
            network = networkStr.toNetworkType(),
            path = path,
            host = wsHost,
            serviceName = serviceName,
            security = securityStr.toSecurityType(),
            sni = sni,
            fingerprint = fingerprint,
            publicKey = publicKey,
            shortId = shortId,
            spiderX = spiderX,
            rawLink = link
        )
    }

    // ─────────────────────────── VMess ───────────────────────────

    private fun parseVmess(link: String): VpnConfig {
        // vmess://BASE64(json)
        val encoded = link.removePrefix("vmess://")
            .substringBefore("#") // fragment is inside json usually
        val json = try {
            JSONObject(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8))
        }

        val host = json.optString("add").ifBlank { throw IllegalArgumentException("VMess: missing add") }
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port")
        val uuid = json.optString("id").ifBlank { throw IllegalArgumentException("VMess: missing id") }
        val name = json.optString("ps").ifBlank { "$host:$port" }
        val alterId = json.optString("aid").toIntOrNull() ?: json.optInt("aid", 0)
        val networkStr = json.optString("net", "tcp")
        val tls = json.optString("tls", "none")
        val sni = json.optString("sni").ifBlank { json.optString("host") }
        val path = json.optString("path", "")
        val wsHost = json.optString("host", sni)
        val fingerprint = json.optString("fp", "chrome")
        // gRPC serviceName uses "path" field; for other transports it's empty
        val serviceName = if (networkStr == "grpc") json.optString("path", "") else ""

        return VpnConfig(
            name = name,
            protocol = VpnProtocol.VMESS,
            address = host,
            port = port,
            uuid = uuid,
            alterId = alterId,
            encryption = json.optString("scy", "auto"),
            network = networkStr.toNetworkType(),
            path = path,
            host = wsHost,
            serviceName = serviceName,
            security = if (tls == "tls") SecurityType.TLS else SecurityType.NONE,
            sni = sni,
            fingerprint = fingerprint,
            rawLink = link
        )
    }

    // ──────────────────────── Shadowsocks ────────────────────────

    private fun parseShadowsocks(link: String): VpnConfig {
        // Modern: ss://BASE64(method:password)@host:port#name
        // Legacy: ss://BASE64(method:password@host:port)#name
        val withoutScheme = link.removePrefix("ss://")
        val name = Uri.parse(link).fragment?.urlDecode() ?: ""

        return if (withoutScheme.contains("@")) {
            // Modern SIP002 format
            val uri = Uri.parse(link)
            val userInfoDecoded = String(Base64.decode(uri.userInfo ?: "", Base64.URL_SAFE or Base64.NO_PADDING))
            val (method, password) = userInfoDecoded.split(":", limit = 2)
                .let { it[0] to it.getOrElse(1) { "" } }
            val host = uri.host ?: throw IllegalArgumentException("SS: missing host")
            val port = uri.port.takeIf { it > 0 } ?: throw IllegalArgumentException("SS: missing port")

            VpnConfig(
                name = name,
                protocol = VpnProtocol.SHADOWSOCKS,
                address = host,
                port = port,
                password = password,
                ssMethod = method,
                rawLink = link
            )
        } else {
            // Legacy base64 format
            val encoded = withoutScheme.substringBefore("#")
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
            // format: method:password@host:port
            val atIdx = decoded.lastIndexOf("@")
            if (atIdx < 0) throw IllegalArgumentException("SS: invalid legacy format")
            val methodPassword = decoded.substring(0, atIdx)
            val hostPort = decoded.substring(atIdx + 1)
            val (method, password) = methodPassword.split(":", limit = 2)
                .let { it[0] to it.getOrElse(1) { "" } }
            val host = hostPort.substringBeforeLast(":")
            val port = hostPort.substringAfterLast(":").toIntOrNull()
                ?: throw IllegalArgumentException("SS: invalid port")

            VpnConfig(
                name = name,
                protocol = VpnProtocol.SHADOWSOCKS,
                address = host,
                port = port,
                password = password,
                ssMethod = method,
                rawLink = link
            )
        }
    }

    // ─────────────────────────── Trojan ───────────────────────────

    private fun parseTrojan(link: String): VpnConfig {
        // trojan://password@host:port?sni=...&security=tls&fp=...#name
        val uri = Uri.parse(link)
        val password = uri.userInfo ?: throw IllegalArgumentException("Trojan: missing password")
        val host = uri.host ?: throw IllegalArgumentException("Trojan: missing host")
        val port = uri.port.takeIf { it > 0 } ?: throw IllegalArgumentException("Trojan: missing port")
        val name = uri.fragment?.urlDecode() ?: ""

        val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: host
        val fingerprint = uri.getQueryParameter("fp") ?: "chrome"
        val secStr = uri.getQueryParameter("security") ?: "tls"
        val networkStr = uri.getQueryParameter("type") ?: "tcp"
        val path = uri.getQueryParameter("path")?.urlDecode() ?: ""
        val wsHost = uri.getQueryParameter("host") ?: sni

        return VpnConfig(
            name = name,
            protocol = VpnProtocol.TROJAN,
            address = host,
            port = port,
            password = password,
            network = networkStr.toNetworkType(),
            path = path,
            host = wsHost,
            security = secStr.toSecurityType(),
            sni = sni,
            fingerprint = fingerprint,
            rawLink = link
        )
    }

    // ───────────────────────── Helpers ────────────────────────────

    private fun String.urlDecode(): String =
        URLDecoder.decode(this, "UTF-8")

    private fun String.toNetworkType(): NetworkType = when (lowercase()) {
        "ws" -> NetworkType.WS
        "grpc" -> NetworkType.GRPC
        "h2", "http" -> NetworkType.H2
        "quic" -> NetworkType.QUIC
        else -> NetworkType.TCP
    }

    private fun String.toSecurityType(): SecurityType = when (lowercase()) {
        "tls" -> SecurityType.TLS
        "reality" -> SecurityType.REALITY
        else -> SecurityType.NONE
    }
}
