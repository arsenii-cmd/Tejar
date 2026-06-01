#!/bin/bash
# Run this from the root of the Telegram Android repo
# to find all integration points before patching

TELEGRAM_SRC="${1:-TMessagesProj/src}"

echo "=== ConnectionsManager proxy method ==="
grep -rn "native_setProxySettings" "$TELEGRAM_SRC" --include="*.java" | head -10

echo ""
echo "=== ProxyInfo class ==="
grep -rn "class ProxyInfo" "$TELEGRAM_SRC" --include="*.java"

echo ""
echo "=== SharedConfig proxy fields ==="
grep -rn "proxyEnabled\|currentProxy\|ProxyInfo" "$TELEGRAM_SRC/main/java/org/telegram/messenger/SharedConfig.java" | head -20

echo ""
echo "=== Where proxy is set from UI ==="
grep -rn "setProxySettings\|proxyEnabled = true" "$TELEGRAM_SRC" --include="*.java" | head -20

echo ""
echo "=== ProxyListActivity location ==="
find "$TELEGRAM_SRC" -name "ProxyListActivity*" -o -name "ProxySettingsActivity*" 2>/dev/null

echo ""
echo "=== SettingsActivity proxy row ==="
grep -n "proxy\|Proxy" "$TELEGRAM_SRC/main/java/org/telegram/ui/SettingsActivity.java" 2>/dev/null | grep -i "row\|present\|fragment" | head -20

echo ""
echo "=== ApplicationLoader onCreate ==="
grep -n "onCreate\|proxy\|Proxy" "$TELEGRAM_SRC/main/java/org/telegram/messenger/ApplicationLoader.java" 2>/dev/null | head -30
