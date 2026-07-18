package com.telegram.vpncore

/**
 * Represents a VPN subscription — a remote URL that returns a list of server configs.
 */
data class VpnSubscription(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,     // epoch ms
    val configIds: List<String> = emptyList()  // IDs of configs fetched from this sub
)
