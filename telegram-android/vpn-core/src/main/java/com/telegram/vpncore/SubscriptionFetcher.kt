package com.telegram.vpncore

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a subscription URL and parses it into a list of [VpnConfig].
 *
 * Supported response formats:
 *  1. Base64-encoded text (one VPN link per line after decode) — standard v2ray/Marzban
 *  2. Plain text (one VPN link per line) — some panels
 *  3. HTML page (Marzban web UI) — extracts vless/vmess/ss/trojan links from HTML
 */
object SubscriptionFetcher {

    private const val TAG = "SubscriptionFetcher"
    private const val TIMEOUT_MS = 15_000

    // User-Agents to try in order — Marzban picks the response format based on UA
    private val USER_AGENTS = listOf(
        "v2rayNG/1.8.0",
        "clash/1.18.0",
        "Mozilla/5.0"   // fallback: get HTML, then extract links
    )

    suspend fun fetch(url: String): List<VpnConfig> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (ua in USER_AGENTS) {
            try {
                val raw = downloadText(url, ua)
                if (raw.isBlank()) continue
                val results = parseSubscription(raw)
                if (results.isNotEmpty()) {
                    Log.d(TAG, "Fetched ${results.size} configs with UA=$ua")
                    return@withContext results
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fetch attempt failed with UA=$ua: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: Exception("No servers found in subscription")
    }

    // ─────────────────────── Download ────────────────────────────

    private fun downloadText(url: String, userAgent: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "*/*")
        conn.instanceFollowRedirects = true
        return try {
            val code = conn.responseCode
            if (code != 200) throw Exception("HTTP $code")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    // ─────────────────────── Parse ────────────────────────────────

    private fun parseSubscription(raw: String): List<VpnConfig> {
        val trimmed = raw.trim()

        // 1. Try base64 decode first (standard Marzban/v2ray subscription)
        tryBase64Decode(trimmed)?.let { decoded ->
            val results = extractLinksFromText(decoded)
            if (results.isNotEmpty()) return results
        }

        // 2. Try plain text (one link per line)
        val plainResults = extractLinksFromText(trimmed)
        if (plainResults.isNotEmpty()) return plainResults

        // 3. Try HTML extraction (Marzban web UI or other panels)
        if (trimmed.contains("<html", ignoreCase = true) ||
            trimmed.contains("<!DOCTYPE", ignoreCase = true)) {
            return extractLinksFromHtml(trimmed)
        }

        return emptyList()
    }

    private fun extractLinksFromText(text: String): List<VpnConfig> {
        val results = mutableListOf<VpnConfig>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (!looksLikeVpnLink(trimmed)) continue
            try {
                results.add(LinkParser.parse(trimmed))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid link: ${trimmed.take(60)} — ${e.message}")
            }
        }
        return results
    }

    /**
     * Extracts VPN links embedded in HTML (e.g. Marzban web page).
     * Looks for vless://, vmess://, ss://, trojan:// anywhere in the HTML.
     */
    private fun extractLinksFromHtml(html: String): List<VpnConfig> {
        val pattern = Regex(
            """(vless|vmess|ss|trojan)://[^\s"'<>&]+""",
            RegexOption.IGNORE_CASE
        )
        val found = pattern.findAll(html).map { it.value }.toList()
        Log.d(TAG, "HTML extraction found ${found.size} raw links")

        val results = mutableListOf<VpnConfig>()
        for (link in found) {
            try {
                results.add(LinkParser.parse(link))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping HTML link: ${link.take(60)} — ${e.message}")
            }
        }
        return results
    }

    private fun tryBase64Decode(input: String): String? = try {
        // Remove whitespace — some servers add line breaks inside base64
        val clean = input.replace("\n", "").replace("\r", "").trim()
        val decoded = Base64.decode(clean, Base64.DEFAULT or Base64.NO_WRAP)
        val text = String(decoded, Charsets.UTF_8)
        if (looksLikeVpnLink(text) || text.contains("\n")) text else null
    } catch (_: Exception) {
        null
    }

    private fun looksLikeVpnLink(s: String): Boolean =
        s.startsWith("vless://", ignoreCase = true) ||
        s.startsWith("vmess://", ignoreCase = true) ||
        s.startsWith("ss://", ignoreCase = true) ||
        s.startsWith("trojan://", ignoreCase = true)
}
