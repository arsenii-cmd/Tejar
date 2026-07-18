package com.telegram.vpncore

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores VPN subscriptions in plain SharedPreferences (no secrets here — only URLs and names).
 */
class VpnSubscriptionRepository(context: Context) {

    companion object {
        private const val PREFS_FILE = "vpn_subscriptions"
        private const val KEY_SUBS = "subscriptions"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun getAll(): List<VpnSubscription> {
        val raw = prefs.getString(KEY_SUBS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { deserialize(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(sub: VpnSubscription) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == sub.id }
        if (idx >= 0) list[idx] = sub else list.add(sub)
        persist(list)
    }

    fun delete(id: String) {
        persist(getAll().filter { it.id != id })
    }

    fun getById(id: String): VpnSubscription? = getAll().firstOrNull { it.id == id }

    private fun persist(list: List<VpnSubscription>) {
        val arr = JSONArray()
        list.forEach { arr.put(serialize(it)) }
        prefs.edit().putString(KEY_SUBS, arr.toString()).apply()
    }

    private fun serialize(s: VpnSubscription): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("url", s.url)
        put("lastUpdated", s.lastUpdated)
        put("configIds", JSONArray(s.configIds))
    }

    private fun deserialize(j: JSONObject): VpnSubscription {
        val idsArr = j.optJSONArray("configIds") ?: JSONArray()
        val ids = (0 until idsArr.length()).map { idsArr.getString(it) }
        return VpnSubscription(
            id = j.optString("id", java.util.UUID.randomUUID().toString()),
            name = j.optString("name", ""),
            url = j.optString("url", ""),
            lastUpdated = j.optLong("lastUpdated", 0L),
            configIds = ids
        )
    }
}
