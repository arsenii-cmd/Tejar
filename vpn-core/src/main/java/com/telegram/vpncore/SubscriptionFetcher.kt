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
 *  1. sing-box config document — outbounds are taken verbatim (see [SingBoxSubscriptionParser])
 *  2. Base64-encoded text (one VPN link per line after decode) — standard v2ray/Marzban
 *  3. Plain text (one VPN link per line) — some panels
 *  4. HTML page (Marzban web UI) — extracts vless/vmess/ss/trojan links from HTML
 */
object SubscriptionFetcher {

    private const val TAG = "SubscriptionFetcher"
    private const val TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5

    // User-Agents to try in order — the panel picks the response format from the UA.
    // "SFA/" comes first deliberately: it selects the sing-box format, which is the one the
    // panel generates most completely. Its links format silently omits whole protocols
    // (Naive among them), so a v2rayNG UA gets a subscription missing servers that exist.
    // The rest stay as fallbacks for panels that don't speak sing-box.
    private val USER_AGENTS = listOf(
        "SFA/1.11.0",
        "v2rayNG/1.8.0",
        "clash/1.18.0",
        "Mozilla/5.0"   // fallback: get HTML, then extract links
    )

    suspend fun fetch(url: String): List<VpnConfig> = withContext(Dispatchers.IO) {
        require(url.startsWith("https://", ignoreCase = true)) {
            "Subscription URL must use HTTPS: $url"
        }
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
        // Follow redirects manually so we can reject any hop that downgrades to plain HTTP
        // (java.net's instanceFollowRedirects silently follows https->http, opening it up to MITM).
        var currentUrl = url
        repeat(MAX_REDIRECTS + 1) { attempt ->
            require(currentUrl.startsWith("https://", ignoreCase = true)) {
                "Refusing non-HTTPS subscription URL: $currentUrl"
            }
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "*/*")
            conn.instanceFollowRedirects = false
            try {
                val code = conn.responseCode
                when (code) {
                    200 -> return conn.inputStream.bufferedReader().readText()
                    in 300..399 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw Exception("HTTP $code without Location")
                        currentUrl = URL(URL(currentUrl), location).toString()
                        if (attempt == MAX_REDIRECTS) throw Exception("Too many redirects")
                    }
                    else -> throw Exception("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw Exception("Too many redirects")
    }

    // ─────────────────────── Parse ────────────────────────────────

    private fun parseSubscription(raw: String): List<VpnConfig> {
        val trimmed = raw.trim()

        // 0. sing-box config document — use the panel's own outbounds instead of re-deriving them
        if (SingBoxSubscriptionParser.looksLikeSingBoxConfig(trimmed)) {
            val results = SingBoxSubscriptionParser.parse(trimmed)
            if (results.isNotEmpty()) return results
        }

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
            if (!looksLikeVpnLink(trimmed)) {
                // Unrecognised schemes used to vanish without a trace, which is how the
                // subscription could carry servers that simply never appeared in the list.
                // Log the scheme only — everything after "://" is credentials.
                if (trimmed.contains("://")) {
                    Log.w(TAG, "Unsupported scheme in subscription: ${trimmed.substringBefore("://")}")
                }
                continue
            }
            try {
                results.add(LinkParser.parse(trimmed))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid link: ${redactCredentials(trimmed).take(60)} — ${e.message}")
            }
        }
        return results
    }

    /**
     * Strips secrets before a link preview hits logcat: the userinfo portion (uuid/password
     * before '@') for vless/trojan/ss, or the whole opaque payload for vmess (it's a single
     * base64 blob with no '@' separator, so there's no safe prefix to show).
     */
    private fun redactCredentials(link: String): String {
        if (link.startsWith("vmess://", ignoreCase = true)) return "vmess://***"
        return link.replaceFirst(Regex("""^(\w+://)[^@/\s]+@"""), "$1***@")
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
                Log.w(TAG, "Skipping HTML link: ${redactCredentials(link).take(60)} — ${e.message}")
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

    // Must stay in sync with LinkParser.parse — a scheme missing here is dropped before
    // the parser ever sees it, so the server silently never shows up in the list.
    private fun looksLikeVpnLink(s: String): Boolean =
        s.startsWith("vless://", ignoreCase = true) ||
        s.startsWith("vmess://", ignoreCase = true) ||
        s.startsWith("ss://", ignoreCase = true) ||
        s.startsWith("trojan://", ignoreCase = true) ||
        s.startsWith("hysteria2://", ignoreCase = true) ||
        s.startsWith("hy2://", ignoreCase = true) ||
        s.startsWith("naive+https://", ignoreCase = true) ||
        // NaiveProxy as the panel actually emits it: https://user:pass@host:port
        ((s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) &&
            s.substringAfter("://").substringBefore('/').substringBefore('?').let {
                it.contains('@') && it.substringBefore('@').contains(':')
            })
}
