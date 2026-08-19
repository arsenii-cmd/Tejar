package com.telegram.vpncore

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class VpnProtocol {
    VLESS, VMESS, SHADOWSOCKS, TROJAN
}

enum class NetworkType {
    TCP, WS, GRPC, H2, QUIC
}

enum class SecurityType {
    NONE, TLS, REALITY
}

@Parcelize
data class VpnConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: VpnProtocol,
    val address: String,
    val port: Int,

    // Auth
    val uuid: String = "",       // VLESS, VMess
    val password: String = "",   // Shadowsocks, Trojan

    // VLESS / VMess specific
    val encryption: String = "none",
    val flow: String = "",       // e.g. "xtls-rprx-vision"
    val alterId: Int = 0,        // VMess

    // Transport
    val network: NetworkType = NetworkType.TCP,
    val path: String = "",       // WS / H2 path
    val host: String = "",       // WS host header
    val serviceName: String = "", // gRPC service name

    // TLS / Reality
    val security: SecurityType = SecurityType.NONE,
    val sni: String = "",
    val fingerprint: String = "chrome",

    // Reality only
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",

    // Shadowsocks specific
    val ssMethod: String = "aes-256-gcm",

    val rawLink: String = ""
) : Parcelable {
    val displayName: String get() = name.ifBlank { "$address:$port" }
    val protocolLabel: String get() = when (protocol) {
        VpnProtocol.VLESS -> "VLESS"
        VpnProtocol.VMESS -> "VMess"
        VpnProtocol.SHADOWSOCKS -> "Shadowsocks"
        VpnProtocol.TROJAN -> "Trojan"
    }
}
