package com.telegram.vpncore

import android.util.Log
import kotlinx.coroutines.*

/**
 * Measures TCP connect latency to VPN servers.
 */
object PingManager {

    private const val TAG = "PingManager"
    private const val TIMEOUT_MS = 3000
    private const val UNREACHABLE = Long.MAX_VALUE
    private const val NOT_MEASURABLE = -1L

    data class PingResult(
        val config: VpnConfig,
        val pingMs: Long       // Long.MAX_VALUE = unreachable, -1 = not measurable
    ) {
        /** True only for an actual measurement — drives auto-connect, so it must not guess. */
        val isReachable: Boolean get() = pingMs != UNREACHABLE && pingMs != NOT_MEASURABLE
        val displayPing: String get() = when (pingMs) {
            NOT_MEASURABLE -> "—"
            UNREACHABLE -> "—"
            else -> "${pingMs}ms"
        }
    }

    /**
     * Pings all configs in parallel and returns results sorted by latency (fastest first).
     * Unreachable servers go to the end.
     */
    suspend fun pingAll(
        configs: List<VpnConfig>,
        onProgress: ((PingResult) -> Unit)? = null
    ): List<PingResult> = coroutineScope {
        val jobs = configs.map { config ->
            async(Dispatchers.IO) {
                val result = PingResult(config, measure(config))
                onProgress?.let { withContext(Dispatchers.Main) { it(result) } }
                result
            }
        }
        // Measured servers first, then the unmeasured ones, then the failures: an entry we
        // could not measure is not known to be down, but it must not outrank a server that
        // actually answered.
        jobs.awaitAll().sortedWith(
            compareBy {
                when (it.pingMs) {
                    NOT_MEASURABLE -> UNREACHABLE - 1
                    else -> it.pingMs
                }
            }
        )
    }

    /**
     * Pings a single config.
     */
    suspend fun ping(config: VpnConfig): PingResult = withContext(Dispatchers.IO) {
        PingResult(config, measure(config))
    }

    /**
     * Hysteria2 runs over QUIC, so a TCP connect to its port says nothing about it — and on
     * port 443 it succeeds against whatever unrelated TCP listener is there, reporting a
     * healthy latency for a server that cannot be reached at all. Report "no measurement"
     * instead of a number that means nothing.
     */
    private fun measure(config: VpnConfig): Long =
        if (config.protocol == VpnProtocol.HYSTERIA2) NOT_MEASURABLE
        else tcpPing(config.address, config.port)

    // ─────────────────── TCP connect timing ──────────────────────

    private fun tcpPing(host: String, port: Int): Long {
        return try {
            val start = System.currentTimeMillis()
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), TIMEOUT_MS)
            }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            Log.d(TAG, "Unreachable $host:$port — ${e.message}")
            UNREACHABLE
        }
    }
}
