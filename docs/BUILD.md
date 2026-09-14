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

Официальный (единственный поддерживаемый апстримом) способ сборки — не голый
`gomobile bind`, а Python-скрипт репозитория:

```bash
git clone https://github.com/XTLS/libXray
cd libXray
export ANDROID_HOME=<путь к Android SDK>
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<версия>"   # см. `ls "$ANDROID_HOME/ndk"`
python3 build/main.py android
```
Нужны `git`, `go` (проект собран и проверен на Go 1.27), `python3`. Скрипт сам
ставит `gomobile`/`gobind` нужной версии (`prepare_gomobile()`), качает geo-данные
(`download_geo()`) и вызывает
`gomobile bind -target android -androidapi 21 -ldflags="-checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384"`
(флаг `max-page-size=16384` — под 16 KB page size Android 15+). Результат —
`libXray.aar` в корне репозитория:
```bash
cp libXray.aar /path/to/Hydra/app/libs/
```

> Xray-core сам tun не обслуживает — но **отдельный tun2socks-мост
> (hev-socks5-tunnel) не нужен**: Xray поднимается headless с локальным
> socks5-inbound (`127.0.0.1:10808`, см. `XrayConfigBuilder`), а роль моста
> берёт на себя уже проверенный на устройстве sing-box —
> `SingBoxConfigBuilder.buildXrayBridge()` строит tun-конфиг с единственным
> `socks`-outbound на этот же порт. Подробности — [PROTOCOLS.md](PROTOCOLS.md),
> раздел «Xray».

**Реальный API (сверено декомпиляцией собранного `.aar`, не догадкой)**:
библиотека НЕ экспортирует отдельные `runXray()`/`stopXray()` — единственная
точка входа `LibXray.invoke(requestJson): String`, где `requestJson` —
`{"apiVersion":3,"method":"runXray","payload":{"xrayJson":"..."}}`, ответ —
`{"success":bool,"data":..,"error":".."}`. Поддерживаемые `method`:
`getFreePorts, convertShareLinksToXrayJson, convertXrayJsonToShareLinks,
generateAgeKeyPair, countGeoData, pingBatch, testXray, runXray, stopXray,
xrayVersion, getXrayState`. Для protect() сокетов — `LibXray.registerDialerController(...)`
с интерфейсом `DialerController { protectFd(fd: Long): Boolean }`
(параметр — `long`, не `int`). Java-пакет сгенерированных классов — `libXray`
(`libXray.LibXray`, `libXray.DialerController`), сверено `javap` по факту сборки.

> **Критично: `libXray.aar` работает в ОТДЕЛЬНОМ процессе И грузится
> изолированным `DexClassLoader` — не как обычная Gradle-зависимость.**
> Оба — независимые `gomobile bind`-сборки (`libbox.aar`/`libXray.aar`),
> каждая тащит свою копию generic-обвязки gobind (`go.Seq`/`go.Universe`/
> `go.error`, пакет `golang.org/x/mobile/bind/java`) — апстрим (README
> `XTLS/libXray`) прямо предупреждает: Go не поддерживает два независимо
> собранных рантайма в одном процессе.
>
> На практике этот путь прошёл через пять живых багов подряд (см.
> docs/HANDOFF.md, «Честные оговорки», за полным разбором) — коротко:
> - Оставить одну копию `go.*` (дедуп) собирается, но в рантайме грузит
>   НЕ ТУ `.so` — `go.Seq.<clinit>` зашивает имя библиотеки на этапе сборки
>   (`System.loadLibrary("box")` у libbox vs `"gojni"` у libXray), копии
>   не взаимозаменяемы.
> - Переименовать пакет (`go`→`xraygo`, через ASM) тоже не работает — у
>   gomobile implicit JNI-linking, `.so` экспортирует символы вида
>   `Java_go_Seq_init` по ИСХОДНОМУ имени класса; переименование ломает
>   связь, а не чинит её.
> - Обычный `DexClassLoader` с parent = classloader процесса тоже не
>   спасает — родитель всё равно РЕЗОЛВИТ `go.Seq` от libbox (та же
>   проблема, но через делегацию классов, а не merge дексов).
>
> Итоговая рабочая схема — два независимых шага:
> 1. **Процесс.** `XrayEngineService` (`app/src/nativeXrayReal/.../vpn/core/xray/`)
>    объявлен в `AndroidManifest.xml` с `android:process=":xray"` — реальный
>    Go-рантайм Xray живёт в отдельном ОС-процессе, полностью изолированном от
>    основного процесса (там же — sing-box/`libbox.so`). Общение — AIDL
>    (`IXrayEngine`/`IXraySocketProtector`, `app/src/main/aidl/`), через
>    границу летают только `String`/`ParcelFileDescriptor`, без Go-типов.
>    `protect()` сокетов идёт в обратную сторону (`:xray` → главный процесс,
>    где живёт `VpnService`) тем же путём: `ParcelFileDescriptor.fromFd()` —
>    Binder дублирует fd на уровне ядра при передаче, поэтому закрытие локальной
>    копии после вызова не трогает исходный fd (Xray продолжает им пользоваться).
> 2. **Изоляция классов.** Даже отдельный процесс не спасает, пока классы
>    Xray попадают в ОБЩИЙ дех приложения (та же проблема — что дедуп, что
>    переименование). Поэтому `libXray.aar` НЕ подключён как обычная
>    Gradle-зависимость вовсе: `app/build.gradle.kts` двумя задачами
>    (`extractLibXrayNativeLibs`/`libXrayToDex`) вынимает из него `.so`
>    (в обычные `jniLibs`, как всегда) и НЕТРОНУТЫЙ `classes.jar` пересобирает
>    через `d8` в отдельный `classes.dex`-ассет. В рантайме `XrayEngineService`
>    грузит его через `DexClassLoader` с **`parent = null`** (только
>    boot-classloader — до classpath приложения, где сидит libbox'овский
>    `go.Seq`, достать неоткуда) и работает с классами через reflection
>    (`Class.forName`/`Method.invoke`/`Proxy` для `DialerController`).
>    ART при этом требует, чтобы dex-файл был **read-only** для самого
>    процесса (`dexFile.setReadOnly()`) — иначе `DexClassLoader` откажет с
>    `Writable dex file ... is not allowed`. Всё автоматически, руками
>    ничего делать не нужно — просто положите `libXray.aar` в `app/libs/`.

**Подтверждено на реальном устройстве** (OnePlus CPH2747, 2026-09): VLESS
через Xray Core поднимается и передаёт трафик. `libXray.aar` собран
официальным скриптом (Go 1.27.0), `:app:assembleNativeDebug`/`assembleNativeRelease`
с обоими `.aar` — `BUILD SUCCESSFUL`. Дополнительно по пути нашлись и
починены: `fdsan`-краш в `HydraVpnService` (двойное закрытие tun-fd при
ранней ошибке `XrayCore.start()`) и `geoip:private` в маршрутизации без
файла `geoip.dat` (заменено на явные CIDR приватных диапазонов,
`XrayConfigBuilder.PRIVATE_IP_RANGES`) — см. docs/HANDOFF.md, «Честные
оговорки», за полным разбором.

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

Плюс tun2socks (`hev-socks5-tunnel`, тот, от которого отказались для Xray в
2.2) — либо переиспользовать тот же приём «sing-box как мост», что и для
`XrayCore`: `SingBoxConfigBuilder.buildXrayBridge()`/`SingBoxCore.runConfig()`
уже общие, `olcrtc`-клиенту достаточно поднять свой локальный socks5-порт
и передать его туда же. Мост описан в `OlcRtcCore` (не реализовано —
см. HANDOFF.md).

### 2.6 Сборка приложения с ядрами (flavor `native`)

```bash
./gradlew :app:assembleNativeDebug
```

При обновлении `.aar` компилятор может указать на несоответствие сигнатур
`PlatformInterface` — доопределите недостающие методы под новую версию libbox
(референс: `io.nekohasekai.sfa.bg.PlatformInterfaceWrapper` в SFA).

## 3. Выпуск релиза

Только через скрипт — он не даст выпустить нерабочий релиз:

```bash
scripts/release.sh --dry-run   # собрать и проверить, ничего не публикуя
scripts/release.sh             # собрать, проверить и опубликовать
```

Что делает скрипт:

1. читает версию из `app/build.gradle.kts`;
2. убеждается, что `app/libs/libbox.aar` на месте;
3. собирает **обе** сборки — `stub` и `native`;
4. **проверяет, что внутри full-APK реально лежит `lib/*/libbox.so`** — без
   этого публикация отменяется;
5. проверяет, что артефакты не перепутаны (в stub нет ядра) и что размер
   full-APK правдоподобен;
6. ставит тег `vX.Y.Z` и публикует релиз с обоими APK.

> **Почему так.** Релиз 0.6.0 сначала уехал с одной stub-сборкой, в которой
> соединение симулируется, — то есть с неработающим приложением. Разбор — в
> `docs/HANDOFF.md`, «Честные оговорки». Кроме скрипта есть ещё две страховки:
> Gradle-проверка (`checkNativeCores`) и CI-страж
> (`.github/workflows/release-guard.yml`), который валит проверку, если у
> опубликованного релиза нет full-APK или внутри него нет ядра.

**Никогда не публикуйте релиз только со stub-APK** — это сборка для разработки
интерфейса и CI, туннель в ней не поднимается.

## 4. Подпись релиза

Подключение к Gradle **уже сделано** (`app/build.gradle.kts`): если в корне
проекта лежит `keystore.properties`, release-сборка подписывается им; если файла
нет — собирается как раньше, чтобы сборка не ломалась у того, у кого ключа нет.

От вас нужен только сам ключ — он намеренно не хранится в репозитории и не
создаётся автоматически:

```bash
keytool -genkey -v -keystore Hydra.jks -alias Hydra \
  -keyalg RSA -keysize 4096 -validity 10000
```

```properties
# keystore.properties (в .gitignore, рядом с settings.gradle.kts)
storeFile=Hydra.jks
storePassword=...
keyAlias=Hydra
keyPassword=...
```

> Потеряете ключ — обновления уже установленного приложения станут невозможны
> (Android не примет APK с другой подписью). Храните `.jks` и пароли вне репозитория.

## 5. ABI

Ядра собираются под `arm64-v8a`, `armeabi-v7a`, `x86_64` (см. `abiFilters`).
`.aar` от gomobile обычно содержит нужные ABI; при рассинхроне уменьшите список.
