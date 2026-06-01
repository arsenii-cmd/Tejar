# Интеграция vpn-core в Telegram Android

## Шаг 1: Клонирование Telegram Android

```bash
git clone https://github.com/DrKLO/Telegram.git telegram-android
cd telegram-android
```

Или Telegram FOSS (без проприетарных зависимостей):
```bash
git clone https://github.com/Telegram-FOSS-Team/Telegram-FOSS.git telegram-android
```

## Шаг 2: Подключение модуля vpn-core

В `settings.gradle` корневого проекта добавь:
```groovy
include ':vpn-core'
project(':vpn-core').projectDir = new File('../vpn-core') // или относительный путь
```

В `TMessagesProj/build.gradle` добавь зависимость:
```groovy
dependencies {
    implementation project(':vpn-core')
    // ... existing deps
}
```

В корневой `build.gradle` добавь JitPack (для AndroidLibXrayLite):
```groovy
allprojects {
    repositories {
        // ... existing
        maven { url 'https://jitpack.io' }
    }
}
```

## Шаг 3: Скопировать патчи UI

Скопируй папку `TMessagesProj-patches/src/` в `TMessagesProj/src/`.
Файлы:
- `org/telegram/ui/vpn/VpnSettingsActivity.kt`
- `org/telegram/ui/vpn/VpnConfigAdapter.kt`
- `org/telegram/ui/vpn/TelegramProxyBridge.kt`

## Шаг 4: Добавить пункт меню в SettingsActivity

В `TMessagesProj/src/.../SettingsActivity.java` найди блок proxy:

```java
// Ищи: "proxy" или "ProxyListActivity"
// Обычно в методе onItemClick или в getItemsCount/getItem
```

Замени или добавь рядом:
```java
// Было:
presentFragment(new ProxyListActivity());

// Стало:
presentFragment(new VpnSettingsActivity());
```

Или добавь новый пункт в список настроек:
```java
// В методе, где формируется список rows (ищи "proxyRow"):
vpnRow = rowCount++;

// В getView / onBindViewHolder:
if (position == vpnRow) {
    cell.setTextAndValueAndIcon(
        "VPN Proxy",
        VpnProxyManager.getInstance(context).isRunning() ? "Connected" : "Off",
        R.drawable.msg_secret // или любая иконка
    );
}

// В onItemClick:
if (position == vpnRow) {
    presentFragment(new VpnSettingsActivity());
}
```

## Шаг 5: Автозапуск при старте приложения

В `ApplicationLoader.java` (или `TelegramApplication.kt`) в методе `onCreate`:

```java
// Автовосстановление активного соединения
VpnConfigRepository repo = new VpnConfigRepository(this);
VpnConfig activeConfig = repo.getActive();
if (activeConfig != null) {
    VpnProxyManager manager = VpnProxyManager.getInstance(this);
    manager.startProxy(activeConfig);
}
```

## Шаг 6: Проверка точки инъекции прокси

Найди в исходниках:
```bash
grep -r "native_setProxySettings" TMessagesProj/src/
grep -r "ProxyInfo" TMessagesProj/src/ --include="*.java" | head -20
grep -r "proxyEnabled" TMessagesProj/src/ --include="*.java" | head -10
```

Убедись, что сигнатура `native_setProxySettings` совпадает:
```java
// В ConnectionsManager.java:
public static native void native_setProxySettings(
    int currentAccount,
    String address, int port,
    String username, String password, String secret
);
```

Если сигнатура отличается — обнови `TelegramProxyBridge.kt`.

## Шаг 7: Сборка

> **Важно:** Gradle требует JDK 17, не JRE. Перед сборкой:
> ```bash
> export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot"
> ```

```bash
./gradlew :TMessagesProj_App:assembleAfatRelease
# или для debug:
./gradlew :TMessagesProj_App:assembleAfatDebug
```

Правильное имя задачи — `assembleAfatRelease` (не `assembleArmRelease` — такого флейвора нет).

ABI split (уменьшает размер APK):
В `TMessagesProj/build.gradle`:
```groovy
android {
    splits {
        abi {
            enable true
            reset()
            include "arm64-v8a", "armeabi-v7a"
            universalApk false
        }
    }
}
```

## Правильный API для TelegramProxyBridge

`SharedConfig.proxyEnabled` не существует. Единственный корректный способ включить/выключить прокси:

```kotlin
// Включить
SharedConfig.currentProxy = SharedConfig.ProxyInfo(host, port, "", "", "")
MessagesController.getGlobalMainSettings().edit()
    .putBoolean("proxy_enabled", true).commit()
ConnectionsManager.setProxySettings(true, host, port, "", "", "")
NotificationCenter.getGlobalInstance()
    .postNotificationName(NotificationCenter.proxySettingsChanged)

// Выключить
MessagesController.getGlobalMainSettings().edit()
    .putBoolean("proxy_enabled", false).commit()
ConnectionsManager.setProxySettings(false, "", 0, "", "", "")
NotificationCenter.getGlobalInstance()
    .postNotificationName(NotificationCenter.proxySettingsChanged)
```

Паттерн подтверждён из исходника `ProxyListActivity.java`. `NotificationCenter.globalInstance` — приватное поле, используй `getGlobalInstance()`.

## VpnSettingsActivity — UI и состояния

### Состояния ProxyState

```kotlin
sealed class ProxyState {
    object Idle : ProxyState()
    object Connecting : ProxyState()                       // объект, НЕ data class — нет .config
    data class Connected(val config: VpnConfig) : ProxyState()
    data class Error(val message: String) : ProxyState()
}
```

При обработке `Connecting` — используй `repository.getActive()`, а не `state.config`:
```kotlin
is VpnProxyManager.ProxyState.Connecting -> {
    repository.getActive()?.let { showServerRow(it) }
    ...
}
```

### Hero-карточка: hint / error / ping

Три взаимоисключающих слоя в hero-карточке:

| Состояние   | Отображается                          |
|-------------|---------------------------------------|
| `Idle`      | `heroHintText` ("Выбери сервер…")     |
| `Error`     | `heroErrorRow` с текстом причины      |
| `Connected` | `heroPingContainer` (3 пипа + мс)     |

Анимация карточки: `animateCardFill()` меняет fill через `ArgbEvaluator`, border задаётся напрямую через `heroCardBg.setStroke()`.

### TCP-пинг

```kotlin
private suspend fun tcpPing(host: String, port: Int): Int? = withContext(Dispatchers.IO) {
    try {
        val start = System.currentTimeMillis()
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), 3000)
        }
        (System.currentTimeMillis() - start).toInt()
    } catch (e: Exception) { null }
}
```

- Результаты хранятся в `pingMap: MutableMap<String, Int?>` (ключ — `config.id`)
- `null` = сервер недоступен (OFFLINE), отсутствие ключа = ещё не проверен (`—`)
- Кнопка Connect заблокирована для OFFLINE-серверов
- Пинг запускается для всех конфигов при открытии экрана (`launchPings()`)

### ActionBar subtitle

```kotlin
private fun updateActionBarSubtitle() {
    val subtitle = when (currentState) {
        is Connected -> "${state.config.displayName} · Connected"
        is Connecting -> "Establishing tunnel…"
        is Error, Idle -> "$count saved · Not connected"
    }
    actionBar.setSubtitle(subtitle)
}
```

### Цвета пинга

```kotlin
private fun pingColor(ping: Int): Int =
    if (ping < 60) GREEN else if (ping < 150) GOLD else RED
// GREEN = #5DCAA5, GOLD = #E8B339, RED = #E55E5E
```

## VpnConfigAdapter — dp в Kotlin

`AndroidUtilities.dp()` принимает `Float`, не `Int`. В Kotlin нужны суффиксы:
```kotlin
// Неверно:
AndroidUtilities.dp(12)
// Верно:
AndroidUtilities.dp(12f)
```

## Диагностика

### Xray не запускается
- Проверь логи: `adb logcat -s VpnProxyManager`
- Проверь ABI: `adb shell getprop ro.product.cpu.abi`
- Проверь конфиг: смотри `files/xray/config.json` в приватной директории приложения

### Telegram не использует прокси
- Убедись, что `TelegramProxyBridge.enableProxy()` вызывается после старта xray
- Проверь: `adb logcat -s TelegramProxyBridge`
- Проверь сигнатуру `native_setProxySettings` в твоей версии Telegram

### Порт занят
- Поменяй `LOCAL_PORT` в `VpnProxyManager.kt` на другой (например 10809)

## AxiOm App — исправленные баги

### 1. Пинг после отключения (`home_page.dart` → `_StatRow`)

**Проблема:** `activeProxy` хранит последнее значение delay даже после отключения, поэтому пинг продолжал отображаться.

**Фикс:** Добавить проверку `isConnected` перед формированием строки пинга:
```dart
final connectionStatus = ref.watch(connectionNotifierProvider);
final isConnected = connectionStatus is AsyncData && connectionStatus.value is Connected;
final delay = activeProxy?.urlTestDelay ?? 0;
final pingStr = (isConnected && delay > 0 && delay < 65000) ? '${delay}мс' : '—';
```

### 2. Сброс таймера при повторном тесте пинга (`connection_button.dart`)

**Проблема:** `useEffect` зависел от `fullyConnected` (включавшего `delay`). При рестесте прокси `delay` кратко обнуляется → `fullyConnected = false` → таймер и `connectedAt` сбрасываются.

**Фикс:** Разделить `isConnected` (только для таймера) и `fullyConnected` (только для отображения виджета):
```dart
final isConnected = connectionStatus.valueOrNull is Connected && requiresReconnect != true;
final fullyConnected = isConnected && delay > 0 && delay < 65000;

useEffect(() {
  if (isConnected) {
    connectedAt.value ??= DateTime.now();
    final timer = Timer.periodic(const Duration(seconds: 1), (_) {
      elapsedSeconds.value = DateTime.now().difference(connectedAt.value!).inSeconds;
    });
    return timer.cancel;
  } else {
    connectedAt.value = null;
    elapsedSeconds.value = 0;
    return null;
  }
}, [isConnected]); // зависит только от состояния соединения, не от delay
```

## Архитектура потока данных

```
Telegram UI
    ↓ HTTP/SOCKS request
ConnectionsManager (C++)
    ↓ через SOCKS5 127.0.0.1:10808
VpnProxyManager → xray-core (JNI/gomobile)
    ↓ VLESS/VMess/SS/Trojan
Remote VPN Server
    ↓
Internet
```
