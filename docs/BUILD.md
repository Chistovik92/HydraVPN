# Сборка

## Требования

- Android Studio (Giraffe или новее) / Android Gradle Plugin 8.5+
- JDK 17
- Android SDK: `compileSdk 35`, `minSdk 26`
- Для нативных ядер: Go 1.22+, `gomobile`, Android NDK

## 1. Сборка без ядер (flavor `stub`)

Собирается сразу, без каких-либо `.aar`. Соединение симулируется (UI полностью
рабочий) — используйте для разработки интерфейса и в CI.

```bash
gradle wrapper --gradle-version 8.9   # один раз, если нет gradlew.jar
./gradlew :app:assembleStubDebug
# APK: app/build/outputs/apk/stub/debug/app-stub-debug.apk
```

## 2. Сборка нативных ядер

### 2.1 sing-box → `libbox.aar`

```bash
git clone https://github.com/SagerNet/sing-box
cd sing-box
# инструмент gomobile от SagerNet
go install github.com/sagernet/gomobile/cmd/gomobile@latest
go install github.com/sagernet/gomobile/cmd/gobind@latest
export PATH="$PATH:$(go env GOPATH)/bin"
gomobile init

# набор фич (tun/quic/utls/clash-api и т.д.) задаётся build-тегами
TAGS="with_gvisor,with_quic,with_utls,with_clash_api,with_wireguard"
gomobile bind -v -androidapi 26 -javapkg=io.nekohasekai \
  -tags "$TAGS" -trimpath -ldflags="-s -w" \
  -o libbox.aar ./experimental/libbox

cp libbox.aar /path/to/ShadowLink/app/libs/
```

Готовые сборки также публикуют сторонние репозитории (например
`sing-box-for-android`); проверяйте соответствие версии и build-тегов.

### 2.2 Xray-core → `libXray.aar`

```bash
git clone https://github.com/XTLS/libXray
cd libXray
go install github.com/golang/mobile/cmd/gomobile@latest
go install github.com/golang/mobile/cmd/gobind@latest
export PATH="$PATH:$(go env GOPATH)/bin"
gomobile init
gomobile bind -v -androidapi 26 -target=android \
  -o libXray.aar ./

cp libXray.aar /path/to/ShadowLink/app/libs/
```

> Для Xray дополнительно нужен tun2socks-мост (Xray не обслуживает tun напрямую).
> Соберите `hev-socks5-tunnel` под Android или переиспользуйте `libtun2socks`
> из v2rayNG. См. [PROTOCOLS.md](PROTOCOLS.md), раздел «Xray».

### 2.3 Сборка приложения с ядрами (flavor `native`)

```bash
./gradlew :app:assembleNativeDebug
```

При обновлении `.aar` компилятор может указать на несоответствие сигнатур
`PlatformInterface` — доопределите недостающие методы под новую версию libbox
(референс: `io.nekohasekai.sfa.bg.PlatformInterfaceWrapper` в SFA).

## 3. Подпись релиза

```properties
# keystore.properties (в .gitignore)
storeFile=shadowlink.jks
storePassword=...
keyAlias=shadowlink
keyPassword=...
```

Добавьте `signingConfigs` в `app/build.gradle.kts` и подключите к `release`.

## 4. ABI

Ядра собираются под `arm64-v8a`, `armeabi-v7a`, `x86_64` (см. `abiFilters`).
`.aar` от gomobile обычно содержит нужные ABI; при рассинхроне уменьшите список.
