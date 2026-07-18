package com.telegram.vpncore

import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates xray-core JSON configuration from a [VpnConfig].
 *
 * Output inbound: SOCKS5 on 127.0.0.1:[localPort]
 * Output outbound: protocol/transport matching the VpnConfig.
 */
object XrayConfigGenerator {

    fun generate(config: VpnConfig, localPort: Int = 10808): String {
        val root = JSONObject()

        // ── log ──────────────────────────────────────────────────
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // ── inbounds ─────────────────────────────────────────────
        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks-in")
                put("protocol", "socks")
                put("listen", "127.0.0.1")
                put("port", localPort)
                put("settings", JSONObject().apply {
                    put("udp", false)
                    put("auth", "noauth")
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply {
                        put("http")
                        put("tls")
                    })
                })
            })
        })

        // ── outbounds ────────────────────────────────────────────
        root.put("outbounds", JSONArray().apply {
            put(buildProxyOutbound(config))
            // Direct for LAN traffic
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
            })
            // Block for DNS leaks etc.
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
            })
        })

        // ── routing ──────────────────────────────────────────────
        // Note: geoip:private is NOT used — no geoip.dat file in APK.
        // Instead, explicit private IP ranges are listed.
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("ip", JSONArray().apply {
                        put("10.0.0.0/8")
                        put("172.16.0.0/12")
                        put("192.168.0.0/16")
                        put("127.0.0.0/8")
                        put("::1/128")
                        put("fc00::/7")
                    })
                    put("outboundTag", "direct")
                })
            })
        })

        // ── dns ──────────────────────────────────────────────────
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("8.8.8.8")
                put("1.1.1.1")
                put("localhost")
            })
        })

        return root.toString(2)
    }

    // ─────────────────────────────────────────────────────────────

    private fun buildProxyOutbound(config: VpnConfig): JSONObject {
        return JSONObject().apply {
            put("tag", "proxy")
            put("protocol", config.protocol.toXrayProtocol())
            put("settings", buildOutboundSettings(config))
            put("streamSettings", buildStreamSettings(config))
        }
    }

    private fun buildOutboundSettings(config: VpnConfig): JSONObject {
        return when (config.protocol) {
            VpnProtocol.VLESS -> JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", config.address)
                        put("port", config.port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", config.uuid)
                                put("encryption", config.encryption.ifBlank { "none" })
                                if (config.flow.isNotBlank()) put("flow", config.flow)
                            })
                        })
                    })
                })
            }

            VpnProtocol.VMESS -> JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", config.address)
                        put("port", config.port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", config.uuid)
                                put("alterId", config.alterId)
                                put("security", config.encryption.ifBlank { "auto" })
                            })
                        })
                    })
                })
            }

            VpnProtocol.SHADOWSOCKS -> JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", config.address)
                        put("port", config.port)
                        put("method", config.ssMethod)
                        put("password", config.password)
                        put("uot", false) // UDP over TCP — enable if needed
                    })
                })
            }

            VpnProtocol.TROJAN -> JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", config.address)
                        put("port", config.port)
                        put("password", config.password)
                    })
                })
            }
        }
    }

    private fun buildStreamSettings(config: VpnConfig): JSONObject {
        return JSONObject().apply {
            put("network", config.network.toXrayNetwork())

            // Transport settings
            when (config.network) {
                NetworkType.WS -> put("wsSettings", JSONObject().apply {
                    put("path", config.path.ifBlank { "/" })
                    if (config.host.isNotBlank()) {
                        put("headers", JSONObject().apply {
                            put("Host", config.host)
                        })
                    }
                })

                NetworkType.GRPC -> put("grpcSettings", JSONObject().apply {
                    put("serviceName", config.serviceName)
                    put("multiMode", false)
                })

                NetworkType.H2 -> put("httpSettings", JSONObject().apply {
                    put("path", config.path.ifBlank { "/" })
                    if (config.host.isNotBlank()) {
                        put("host", JSONArray().apply { put(config.host) })
                    }
                })

                NetworkType.QUIC -> put("quicSettings", JSONObject().apply {
                    put("security", "none")
                    put("key", "")
                    put("header", JSONObject().apply { put("type", "none") })
                })

                else -> { /* TCP — no extra settings */ }
            }

            // Security / TLS
            when (config.security) {
                SecurityType.TLS -> {
                    put("security", "tls")
                    put("tlsSettings", JSONObject().apply {
                        if (config.sni.isNotBlank()) put("serverName", config.sni)
                        if (config.fingerprint.isNotBlank()) put("fingerprint", config.fingerprint)
                        put("allowInsecure", false)
                    })
                }

                SecurityType.REALITY -> {
                    put("security", "reality")
                    put("realitySettings", JSONObject().apply {
                        put("serverName", config.sni)
                        put("fingerprint", config.fingerprint.ifBlank { "chrome" })
                        put("publicKey", config.publicKey)
                        put("shortId", config.shortId)
                        if (config.spiderX.isNotBlank()) put("spiderX", config.spiderX)
                    })
                }

                SecurityType.NONE -> put("security", "none")
            }
        }
    }

    // ─────────────────────────── ext fns ─────────────────────────

    private fun VpnProtocol.toXrayProtocol(): String = when (this) {
        VpnProtocol.VLESS -> "vless"
        VpnProtocol.VMESS -> "vmess"
        VpnProtocol.SHADOWSOCKS -> "shadowsocks"
        VpnProtocol.TROJAN -> "trojan"
    }

    private fun NetworkType.toXrayNetwork(): String = when (this) {
        NetworkType.TCP -> "tcp"
        NetworkType.WS -> "ws"
        NetworkType.GRPC -> "grpc"
        NetworkType.H2 -> "h2"
        NetworkType.QUIC -> "quic"
    }
}
