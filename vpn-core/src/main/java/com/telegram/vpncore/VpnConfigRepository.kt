package com.telegram.vpncore

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists VPN configurations in EncryptedSharedPreferences.
 * Stores a JSON array of serialized VpnConfig objects.
 */
class VpnConfigRepository(context: Context) {

    companion object {
        private const val PREFS_FILE = "vpn_configs"
        private const val KEY_CONFIGS = "configs"
        private const val KEY_ACTIVE_ID = "active_config_id"
        private const val KEY_VPN_RUNNING = "vpn_running"
        private const val KEY_ENERGY_SAVING = "energy_saving"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ─────────────────────────── CRUD ────────────────────────────

    fun getAll(): List<VpnConfig> {
        val raw = prefs.getString(KEY_CONFIGS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { deserialize(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(config: VpnConfig) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        persist(list)
    }

    fun delete(id: String) {
        persist(getAll().filter { it.id != id })
        if (getActiveId() == id) clearActive()
    }

    fun getActive(): VpnConfig? {
        val activeId = getActiveId() ?: return null
        return getAll().firstOrNull { it.id == activeId }
    }

    fun setActive(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE_ID) else putString(KEY_ACTIVE_ID, id)
        }.apply()
    }

    fun clearActive() = setActive(null)

    fun setVpnRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_VPN_RUNNING, running).apply()
    }

    fun isVpnRunning(): Boolean = prefs.getBoolean(KEY_VPN_RUNNING, false)

    fun setEnergySaving(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENERGY_SAVING, enabled).apply()
    }

    fun isEnergySaving(): Boolean = prefs.getBoolean(KEY_ENERGY_SAVING, false)

    // ─────────────────────────── I/O ─────────────────────────────

    private fun persist(configs: List<VpnConfig>) {
        val arr = JSONArray()
        configs.forEach { arr.put(serialize(it)) }
        prefs.edit().putString(KEY_CONFIGS, arr.toString()).apply()
    }

    private fun getActiveId(): String? = prefs.getString(KEY_ACTIVE_ID, null)

    // ────────────────────── Serialization ────────────────────────

    private fun serialize(c: VpnConfig): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("protocol", c.protocol.name)
        put("address", c.address)
        put("port", c.port)
        put("uuid", c.uuid)
        put("password", c.password)
        put("encryption", c.encryption)
        put("flow", c.flow)
        put("alterId", c.alterId)
        put("network", c.network.name)
        put("path", c.path)
        put("host", c.host)
        put("serviceName", c.serviceName)
        put("security", c.security.name)
        put("sni", c.sni)
        put("fingerprint", c.fingerprint)
        put("publicKey", c.publicKey)
        put("shortId", c.shortId)
        put("spiderX", c.spiderX)
        put("ssMethod", c.ssMethod)
        put("rawLink", c.rawLink)
    }

    private fun deserialize(j: JSONObject): VpnConfig = VpnConfig(
        id = j.optString("id", java.util.UUID.randomUUID().toString()),
        name = j.optString("name"),
        protocol = VpnProtocol.valueOf(j.getString("protocol")),
        address = j.getString("address"),
        port = j.getInt("port"),
        uuid = j.optString("uuid"),
        password = j.optString("password"),
        encryption = j.optString("encryption", "none"),
        flow = j.optString("flow"),
        alterId = j.optInt("alterId", 0),
        network = NetworkType.valueOf(j.optString("network", "TCP")),
        path = j.optString("path"),
        host = j.optString("host"),
        serviceName = j.optString("serviceName"),
        security = SecurityType.valueOf(j.optString("security", "NONE")),
        sni = j.optString("sni"),
        fingerprint = j.optString("fingerprint", "chrome"),
        publicKey = j.optString("publicKey"),
        shortId = j.optString("shortId"),
        spiderX = j.optString("spiderX"),
        ssMethod = j.optString("ssMethod", "aes-256-gcm"),
        rawLink = j.optString("rawLink")
    )
}
