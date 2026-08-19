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

    data class PingResult(
        val config: VpnConfig,
        val pingMs: Long       // Long.MAX_VALUE = unreachable
    ) {
        val isReachable: Boolean get() = pingMs != UNREACHABLE
        val displayPing: String get() = if (isReachable) "${pingMs}ms" else "—"
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
                val ping = tcpPing(config.address, config.port)
                val result = PingResult(config, ping)
                onProgress?.let { withContext(Dispatchers.Main) { it(result) } }
                result
            }
        }
        jobs.awaitAll().sortedWith(
            compareBy { if (it.isReachable) it.pingMs else Long.MAX_VALUE }
        )
    }

    /**
     * Pings a single config.
     */
    suspend fun ping(config: VpnConfig): PingResult = withContext(Dispatchers.IO) {
        PingResult(config, tcpPing(config.address, config.port))
    }

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
