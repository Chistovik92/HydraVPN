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
git checkout v1.12.9          # версия, против которой собран/проверен Hydra
# инструмент gomobile от SagerNet
go install github.com/sagernet/gomobile/cmd/gomobile@latest
go install github.com/sagernet/gomobile/cmd/gobind@latest
export PATH="$PATH:$(go env GOPATH)/bin"
gomobile init

# набор фич (tun/quic/utls/clash-api и т.д.) задаётся build-тегами
TAGS="with_gvisor,with_quic,with_utls,with_clash_api,with_wireguard,with_conntrack"
gomobile bind -v -androidapi 21 -javapkg=io.nekohasekai -libname=box \
  -tags "$TAGS" -trimpath -buildvcs=false \
  -ldflags="-X github.com/sagernet/sing-box/constant.Version=1.12.9 -s -w -buildid= -checklinkname=0" \
  -o libbox.aar ./experimental/libbox

cp libbox.aar /path/to/Hydra/app/libs/
```

> **Проверено на практике (Windows, NDK r29):**
> - Go **1.25.x** — версия CI sing-box; на Go 1.24/1.26 линковка падает с
>   `invalid reference to os.checkPidfdOnce` (workaround sing-box#3233 /
>   golang/go#70508 в `experimental/libbox/pidfd_android.go`);
> - `-checklinkname=0` обязателен (см. ldflags выше);
> - `-libname=box` и `-androidapi 21` — как в официальном `cmd/internal/build_libbox`.
>
> Готовые сборки также публикуют сторонние репозитории (например
> `sing-box-for-android`); проверяйте соответствие версии и build-тегов.

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

cp libXray.aar /path/to/Hydra/app/libs/
```

> Для Xray дополнительно нужен tun2socks-мост (Xray не обслуживает tun напрямую).
> Соберите `hev-socks5-tunnel` под Android или переиспользуйте `libtun2socks`
> из v2rayNG. См. [PROTOCOLS.md](PROTOCOLS.md), раздел «Xray».

### 2.3 amneziawg-go → `amneziawg-go.aar`

```bash
git clone https://github.com/amnezia-extensions/amneziawg-go
cd amneziawg-go
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
export PATH="$PATH:$(go env GOPATH)/bin"
gomobile init
gomobile bind -v -androidapi 26 -target=android \
  -o amneziawg-go.aar ./

cp amneziawg-go.aar /path/to/Hydra/app/libs/
```

Интеграция: `AmneziaWgCore` уже генерирует `.conf`/uapi (`WireGuardConfigBuilder`);
после сборки .aar подключите GoBackend/IpcUapi-вызовы в местах с
`TODO(amneziawg-go.aar)`. Генерация конфигов не зависит от .aar.

### 2.4 WDTT → `libclient.so` (beta)

Нативная библиотека WG-over-TURN (см. docs/SERVICES.md, раздел WDTT).
Соберите `libclient.so` под ABI из `abiFilters` и положите в
`app/src/main/jniLibs/<abi>/`. Перед включением бинарника проверьте
лицензию upstream-проекта. JNI-план — `WdttCore`.

### 2.5 olcRTC → `olcrtc.aar` + tun2socks (beta)

gomobile-биндинг компонента `cnc` (TCP over WebRTC → локальный SOCKS5):

```bash
git clone <olcrtc-upstream>
gomobile bind -v -androidapi 26 -target=android -o olcrtc.aar ./
cp olcrtc.aar /path/to/Hydra/app/libs/
```

Плюс tun2socks (см. 2.2). Мост описан в `OlcRtcCore`.

### 2.6 Сборка приложения с ядрами (flavor `native`)

```bash
./gradlew :app:assembleNativeDebug
```

При обновлении `.aar` компилятор может указать на несоответствие сигнатур
`PlatformInterface` — доопределите недостающие методы под новую версию libbox
(референс: `io.nekohasekai.sfa.bg.PlatformInterfaceWrapper` в SFA).

## 3. Подпись релиза

```properties
# keystore.properties (в .gitignore)
storeFile=Hydra.jks
storePassword=...
keyAlias=Hydra
keyPassword=...
```

Добавьте `signingConfigs` в `app/build.gradle.kts` и подключите к `release`.

## 4. ABI

Ядра собираются под `arm64-v8a`, `armeabi-v7a`, `x86_64` (см. `abiFilters`).
`.aar` от gomobile обычно содержит нужные ABI; при рассинхроне уменьшите список.
