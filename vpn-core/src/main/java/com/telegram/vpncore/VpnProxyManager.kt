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
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SetupOptions
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the embedded sing-box SOCKS5 proxy (via libbox).
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

    // The core is started with every known server as its own outbound, so the list is read
    // here rather than passed in: a caller that only knows the server it wants would
    // otherwise silently drop the rest and make measurements impossible.
    private val configRepository by lazy { VpnConfigRepository(context) }

    private var commandServer: CommandServer? = null

    /**
     * Latency per server, keyed by [VpnConfig.id], as measured by the core itself.
     * Read the values through [SingBoxLatency]: they are unsigned, so 0 means "no result"
     * rather than "instant". Restored from disk, so it is populated before the first
     * [measureLatency] of a session finishes.
     */
    private val _latency = MutableStateFlow(runCatching { configRepository.getLatency() }.getOrDefault(emptyMap()))
    val latency: StateFlow<Map<String, Int>> = _latency

    private val commandClient by lazy {
        SingBoxCommandClient(scope) { delays ->
            // Keep previously measured servers: a run only reports what it retested, and a
            // number vanishing from the list would look like the measurement was lost.
            val merged = _latency.value + delays
            _latency.value = merged
            runCatching { configRepository.saveLatency(merged) }
        }
    }

    @Volatile
    private var coreSetupDone = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    // Per-install stable SOCKS5 credentials (generated once, persisted). Stable credentials let
    // TelegramProxyBridge.enableProxy() -> SharedConfig.addProxy() deduplicate the VPN proxy by
    // (address, port, username, password). Regenerating them on every startProxy() made that
    // dedup never match and appended a fresh 127.0.0.1:10808 entry on every reconnect,
    // growing the proxy list without bound.
    private val socksPrefs = context.getSharedPreferences("vpn_socks", Context.MODE_PRIVATE)

    @Volatile
    private var socksUsername: String = loadOrCreateToken("socks_user")
    @Volatile
    private var socksPassword: String = loadOrCreateToken("socks_pass")

    private fun loadOrCreateToken(key: String): String {
        val existing = socksPrefs.getString(key, null)
        if (!existing.isNullOrEmpty()) {
            return existing
        }
        val token = randomToken()
        socksPrefs.edit().putString(key, token).apply()
        return token
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
                    val servers = allServersIncluding(config)
                    val jsonConfig = SingBoxConfigGenerator.generate(
                        servers, config.id, LOCAL_PORT, socksUsername, socksPassword
                    )
                    startCore(jsonConfig, config)
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
            commandServer?.let { server ->
                try { server.closeService() } catch (_: Exception) {}
                try { server.close() } catch (_: Exception) {}
            }
            commandServer = null
            commandClient.disconnect()
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

    /**
     * Every stored server, with [selected] guaranteed present — a config can be started
     * before it has been saved (a freshly parsed link), and leaving it out would produce a
     * selector whose default does not exist.
     */
    private fun allServersIncluding(selected: VpnConfig): List<VpnConfig> {
        val stored = runCatching { configRepository.getAll() }.getOrDefault(emptyList())
        return if (stored.any { it.id == selected.id }) stored else stored + selected
    }

    /**
     * Measures every server through its own protocol. Results land in [latency]; the call
     * returns immediately because the core reports them asynchronously.
     */
    fun measureLatency() {
        if (!isRunning()) return
        commandClient.urlTest(SingBoxConfigGenerator.GROUP_TAG)
    }

    /**
     * Switches to [config]. While the core is running this is a selector change, which keeps
     * the tunnel up; otherwise it falls back to starting the core on that server.
     */
    fun selectServer(config: VpnConfig) {
        if (isRunning() && commandClient.selectOutbound(SingBoxConfigGenerator.GROUP_TAG, config.id)) {
            _state.value = ProxyState.Connected(config)
            lastConnectedConfig = config
            return
        }
        startProxy(config)
    }

    fun isRunning(): Boolean = _state.value is ProxyState.Connected

    fun getProxyHost(): String = LOCAL_HOST

    fun getProxyPort(): Int = LOCAL_PORT

    /** The per-session SOCKS5 credentials generated for the currently running proxy, if any. */
    fun getSocksCredentials(): Pair<String, String> = socksUsername to socksPassword

    fun getCurrentConfig(): VpnConfig? =
        (_state.value as? ProxyState.Connected)?.config

    // ───────────────────────── sing-box ──────────────────────────

    /** One-time libbox setup; safe to call repeatedly, only the first call does work. */
    private fun ensureCoreSetup() {
        if (coreSetupDone) return
        val base = context.filesDir.resolve("singbox").apply { mkdirs() }
        val work = base.resolve("work").apply { mkdirs() }
        val temp = context.cacheDir.resolve("singbox").apply { mkdirs() }
        Libbox.setup(SetupOptions().apply {
            basePath = base.absolutePath
            workingPath = work.absolutePath
            tempPath = temp.absolutePath
        })
        coreSetupDone = true
    }

    private fun startCore(jsonConfig: String, config: VpnConfig) {
        ensureCoreSetup()

        // Fail before touching the core if the config can't be parsed — the error message
        // from checkConfig names the offending field, which a start failure does not.
        Libbox.checkConfig(jsonConfig)

        val server = CommandServer(
            TejarCommandServerHandler(onServiceStop = { stopProxy() }),
            TejarPlatformInterface(context)
        )
        server.start()
        try {
            // Must not be null: StartOrReloadService dereferences options.AutoRedirect
            // without a nil check and the core dies with SIGSEGV.
            server.startOrReloadService(jsonConfig, OverrideOptions())
        } catch (e: Exception) {
            try { server.close() } catch (_: Exception) {}
            throw e
        }
        commandServer = server
        // Only useful once the core is up; the client retries until its socket answers.
        commandClient.connect()

        if (!waitForPort(LOCAL_HOST, LOCAL_PORT, timeoutMs = 3000)) {
            // The core came up but never opened its SOCKS port — don't report Connected for a
            // proxy nothing can actually reach. Tear it down so callers get an Error state and
            // can retry, instead of a UI that says "Connected" while every request fails.
            try { server.closeService() } catch (_: Exception) {}
            try { server.close() } catch (_: Exception) {}
            commandServer = null
            throw Exception("sing-box did not open SOCKS port $LOCAL_PORT within timeout")
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
