# Tejar — Telegram + VPN integration

![License](https://img.shields.io/badge/license-GPL--2.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Core](https://img.shields.io/badge/proxy-Xray-orange)

A fork of the [Telegram for Android](https://github.com/DrKLO/Telegram) client with a built-in
VPN / proxy module (`vpn-core`) powered by the [Xray](https://github.com/XTLS/Xray-core) core
(via [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)).

> This is a fresh, standalone snapshot — it does **not** carry the upstream Telegram git history.

## What makes this unique

Unlike running a separate VPN app alongside Telegram, **Tejar embeds the tunnel directly into the
messenger**: the `vpn-core` module parses subscription links (VLESS / Trojan / Shadowsocks),
generates the Xray config, and runs the proxy as a foreground service from inside the Telegram
client itself. To our knowledge no other public Telegram fork ships an integrated Xray-based
VPN core like this.

## Repository layout

| Path | Description |
| --- | --- |
| `telegram-android/` | The Telegram Android client (fork of DrKLO/Telegram) with the VPN integration applied. |
| `vpn-core/` | Standalone Android library module (`com.telegram.vpncore`) implementing the VPN logic. |
| `TMessagesProj-patches/` | Patches applied to the Telegram project sources. |
| `INTEGRATION.md` | Step-by-step guide (RU) for wiring `vpn-core` into a clean Telegram checkout. |
| `find-integration-points.sh` | Helper script to locate integration points in the Telegram sources. |

## vpn-core

The `vpn-core` module parses subscription links and runs them through Xray:

- **Protocols:** VLESS, Trojan, Shadowsocks (see `LinkParser.kt`).
- **Config generation:** `XrayConfigGenerator.kt` builds the Xray JSON config.
- **Runtime:** `VpnProxyManager.kt` / `ProxyForegroundService.kt` manage the tunnel as a foreground service.
- **Persistence:** `VpnConfigRepository.kt` stores configurations.

See [INTEGRATION.md](INTEGRATION.md) for how the module is attached to the Telegram build.

## Building

This is a standard Gradle Android project. Open `telegram-android/` in Android Studio or build from the CLI.

### Required secrets (not included in this repo)

The following are intentionally git-ignored and must be provided locally:

1. **Telegram API credentials** — obtain an `api_id` / `api_hash` from
   [my.telegram.org](https://my.telegram.org) and add them to `telegram-android/local.properties`:

   ```properties
   sdk.dir=/path/to/Android/Sdk
   APP_ID=your_app_id
   APP_HASH=your_app_hash
   ```

   The build injects these into `BuildConfig`, and `BuildVars.java` reads them from there.

2. **Firebase config** — place your own `google-services.json` into the relevant app modules
   (`telegram-android/TMessagesProj_App/`, etc.). Get it from the
   [Firebase console](https://console.firebase.google.com/).

3. **Signing keystore** — `*.keystore` files are git-ignored; configure your own for release builds.

### Build commands

```bash
cd telegram-android
./gradlew assembleAfatStandalone   # example flavor; see build.gradle for all variants
```

## Credits & license

- [Telegram for Android](https://github.com/DrKLO/Telegram) — GPL v2+, the base client this fork builds on.
- [Xray-core](https://github.com/XTLS/Xray-core) / [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) — the proxy core.

This project inherits the GNU GPL v2 (or later) license of the upstream Telegram client — see
[telegram-android/LICENSE](telegram-android/LICENSE)
