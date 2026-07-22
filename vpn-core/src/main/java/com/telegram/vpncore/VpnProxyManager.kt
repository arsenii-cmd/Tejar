package com.telegram.vpncore

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the embedded xray-core (via AndroidLibXrayLite) SOCKS5 proxy.
 *
 * Usage:
 *   VpnProxyManager.getInstance(context).startProxy(config)
 *   VpnProxyManager.getInstance(context).stopProxy()
 */
class VpnProxyManager private constructor(private val context: Context) {

    /**
     * Java-friendly callback interface for proxy connection state changes.
     * Registered listeners are notified on the Main thread.
     */
    interface ConnectionListener {
        fun onProxyConnected(host: String, port: Int, username: String, password: String)
        fun onProxyDisconnected()
    }

    companion object {
        private const val TAG = "VpnProxyManager"
        const val LOCAL_HOST = "127.0.0.1"
        const val LOCAL_PORT = 10808

        @Volatile
        private var instance: VpnProxyManager? = null

        fun getInstance(context: Context): VpnProxyManager =
            instance ?: synchronized(this) {
                instance ?: VpnProxyManager(context.applicationContext).also { instance = it }
            }
    }

    // ─────────────────────────── State ───────────────────────────

    sealed class ProxyState {
        object Idle : ProxyState()
        object Connecting : ProxyState()
        data class Connected(val config: VpnConfig) : ProxyState()
        data class Error(val message: String) : ProxyState()
    }

    private val _state = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val state: StateFlow<ProxyState> = _state

    // Global listeners — notified on Main thread whenever the proxy connects or disconnects.
    // Use addConnectionListener() from ApplicationLoader to wire up TelegramProxyBridge globally.
    private val connectionListeners = CopyOnWriteArrayList<ConnectionListener>()

    fun addConnectionListener(listener: ConnectionListener) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: ConnectionListener) {
        connectionListeners.remove(listener)
    }

    private var coreController: CoreController? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    // Regenerated on every startProxy() — random per-session SOCKS5 credentials so other apps
    // on the device can't use the local proxy just by knowing the well-known loopback port.
    @Volatile
    private var socksUsername: String = ""
    @Volatile
    private var socksPassword: String = ""

    private fun generateSocksCredentials() {
        socksUsername = randomToken()
        socksPassword = randomToken()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ───────────────────── Auto-reconnect ────────────────────────

    var autoReconnect: Boolean = false
        set(value) {
            field = value
            if (value) registerNetworkMonitor() else unregisterNetworkMonitor()
        }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // Read from the network-callback thread and the watchdog (IO), written from startProxy (IO).
    @Volatile
    private var lastConnectedConfig: VpnConfig? = null

    private fun registerNetworkMonitor() {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val config = lastConnectedConfig ?: return
                val current = _state.value
                if (current is ProxyState.Connected) return
                Log.d(TAG, "Network available, auto-reconnecting...")
                startProxy(config)
            }
            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost, proxy will reconnect when network returns")
            }
        }
        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun unregisterNetworkMonitor() {
        val cb = networkCallback ?: return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        networkCallback = null
    }

    // ────────────────────────── Public API ───────────────────────

    fun parseLink(uri: String): Result<VpnConfig> = runCatching {
        LinkParser.parse(uri)
    }

    fun startProxy(config: VpnConfig) {
        scope.launch {
            mutex.withLock {
                if (_state.value is ProxyState.Connected) {
                    stopProxyInternal()
                }
                _state.emit(ProxyState.Connecting)
                try {
                    generateSocksCredentials()
                    val jsonConfig = XrayConfigGenerator.generate(config, LOCAL_PORT, socksUsername, socksPassword)
                    startXray(jsonConfig, config)
                    lastConnectedConfig = config
                    _state.emit(ProxyState.Connected(config))
                    Log.d(TAG, "Proxy started on $LOCAL_HOST:$LOCAL_PORT")
                    withContext(Dispatchers.Main) {
                        connectionListeners.forEach { it.onProxyConnected(LOCAL_HOST, LOCAL_PORT, socksUsername, socksPassword) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start proxy", e)
                    _state.emit(ProxyState.Error(e.message ?: "Unknown error"))
                    if (autoReconnect) {
                        lastConnectedConfig = config
                    }
                }
            }
        }
    }

    fun stopProxy() {
        scope.launch {
            mutex.withLock {
                lastConnectedConfig = null
                stopProxyInternal()
            }
        }
    }

    /**
     * Pauses the proxy without clearing lastConnectedConfig.
     * Used by Energy Saving mode — the proxy will be resumed when the app returns to foreground.
     */
    fun pauseProxy() {
        scope.launch {
            mutex.withLock {
                stopProxyInternal()
            }
        }
    }

    private suspend fun stopProxyInternal() {
        try {
            stopWatchdog()
            coreController?.stopLoop()
            coreController = null
            stopForegroundService()
            _state.emit(ProxyState.Idle)
            Log.d(TAG, "Proxy stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping proxy", e)
            _state.emit(ProxyState.Idle)
        }
        withContext(Dispatchers.Main) {
            connectionListeners.forEach { it.onProxyDisconnected() }
        }
    }

    fun isRunning(): Boolean = _state.value is ProxyState.Connected

    fun getProxyHost(): String = LOCAL_HOST

    fun getProxyPort(): Int = LOCAL_PORT

    /** The per-session SOCKS5 credentials generated for the currently running proxy, if any. */
    fun getSocksCredentials(): Pair<String, String> = socksUsername to socksPassword

    fun getCurrentConfig(): VpnConfig? =
        (_state.value as? ProxyState.Connected)?.config

    // ─────────────────────────── xray ────────────────────────────

    private fun startXray(jsonConfig: String, config: VpnConfig) {
        val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun onEmitStatus(level: Long, msg: String): Long {
                Log.d(TAG, "xray[$level]: $msg")
                return 0
            }

            override fun startup(): Long {
                return 0
            }

            override fun shutdown(): Long {
                stopProxy()
                return 0
            }
        })

        controller.startLoop(jsonConfig, LOCAL_PORT)
        coreController = controller

        if (!waitForPort(LOCAL_HOST, LOCAL_PORT, timeoutMs = 3000)) {
            // The core came up but never opened its SOCKS port — don't report Connected for a
            // proxy nothing can actually reach. Tear it down so callers get an Error state and
            // can retry, instead of a UI that says "Connected" while every request fails.
            try { controller.stopLoop() } catch (_: Exception) {}
            coreController = null
            throw Exception("xray core did not open SOCKS port $LOCAL_PORT within timeout")
        }

        tryStartForegroundService(config)
        startWatchdog()
    }

    private fun waitForPort(host: String, port: Int, timeoutMs: Int): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), 500)
                }
                Log.d(TAG, "Port $port is ready")
                return true
            } catch (_: Exception) {
                Thread.sleep(200)
            }
        }
        Log.w(TAG, "Port $port not ready after ${timeoutMs}ms")
        return false
    }

    private var watchdogJob: Job? = null

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(15000)
                if (_state.value !is ProxyState.Connected) continue
                val alive = try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(LOCAL_HOST, LOCAL_PORT), 1000)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
                if (!alive && _state.value is ProxyState.Connected) {
                    // A dead local SOCKS port means the in-process xray core crashed. Recover
                    // whenever lastConnectedConfig is set (user hasn't pressed Disconnect) —
                    // NOT only when autoReconnect is on (that flag is about network changes).
                    // Previously a single transient failure with autoReconnect off left the
                    // proxy permanently down. startProxy() stops the stale core under the mutex
                    // and restarts, so we don't stop/emit here (avoids an unlocked-state race).
                    val config = lastConnectedConfig ?: continue
                    Log.w(TAG, "Watchdog: proxy port dead, restarting...")
                    startProxy(config)
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // ─────────────────────── Service lifecycle ───────────────────

    private fun tryStartForegroundService(config: VpnConfig) {
        val intent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_START
            putExtra(ProxyForegroundService.EXTRA_CONFIG, config)
        }
        // Always attempt to start the FGS, even from background. Skipping it (as before) meant
        // background-initiated starts (boot restore, auto-reconnect on network change, energy
        // resume) ran the in-process xray with NO foreground service, so the OS could kill the
        // process at any time and the proxy died silently. Boot / brief-foreground starts are
        // exempt and will succeed; if the platform genuinely blocks it we catch below.
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.d(TAG, "ForegroundServiceStartNotAllowed, proxy runs without notification")
            } else {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ProxyForegroundService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to stop foreground service", e)
        }
    }

    fun destroy() {
        unregisterNetworkMonitor()
        scope.launch {
            mutex.withLock {
                stopProxyInternal()
            }
            scope.cancel()
        }
    }
}
