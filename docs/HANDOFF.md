# HANDOFF — состояние проекта Hydra

Документ для продолжения работы (в т.ч. под другим аккаунтом/у другого
разработчика). Главные источники контекста: `CHANGELOG.md` (детальный лог
по версиям 0.1.0 → 0.6.1) и `docs/PROTOCOLS.md`.

## Что это

**Hydra** — мультипротокольный VPN-клиент для Android.
- Пакет / appId: `ru.gidravpn.hydra`
- Стек: Kotlin + Jetpack Compose, minSdk 26, compileSdk 35
- Лицензия: **GPL-3.0** (`LICENSE`), сторонние компоненты — `THIRD_PARTY_NOTICES.md`
- Сайт: https://gidravpn.ru · Telegram: https://t.me/+WWJFBZVhxBs4ZmNi
- Текущая версия: **0.6.22.1** (`app/build.gradle.kts` → `versionName`)
- Флейворы сборки: `stub` (симуляция, без нативных `.aar`, собирается и в CI) и
  `native` (реальные ядра, требует `.aar`/`.so`).

## Быстрый старт

```bash
# stub-сборка (симуляция соединения — для UI/логики/CI):
./gradlew :app:assembleStubDebug
# full-сборка (реальный туннель) — нужен app/libs/libbox.aar, см. docs/BUILD.md:
./gradlew :app:assembleNativeDebug
```
`gradle-wrapper.jar` в репозиторий не кладётся (бинарник) — CI генерирует его
сам (`.github/workflows/android.yml`), локально: `gradle wrapper`.

**Перед тем как решить, что native-сборка невозможна — посмотрите `ls app/libs/`.**
`.aar` не в git, но локально обычно лежит; `assembleNativeDebug` собирается
за ~20 с. Почему это предупреждение здесь — см. «Честные оговорки» (0.6.0).

## Защита от пустых релизов

Релиз без рабочего full-APK (то есть с одной stub-сборкой, где соединение
симулируется) — это неработающее приложение у пользователя. Поставлены три
независимые страховки:

1. **`scripts/release.sh`** — единственный правильный способ выпуска. Собирает
   обе сборки и **проверяет, что внутри full-APK реально есть `lib/*/libbox.so`**;
   без этого до публикации не доходит. Есть `--dry-run`.
2. **Gradle-задача `checkNativeCores`** — при сборке flavor `native` без
   `libbox.aar` даёт внятное объяснение вместо невнятной ошибки резолва
   зависимости (именно её легко принять за «native собрать нельзя»).
3. **CI-страж `.github/workflows/release-guard.yml`** — срабатывает на
   публикацию/редактирование релиза, скачивает full-APK и валит проверку, если
   его нет или внутри нет ядра. Ловит релизы, сделанные в обход скрипта.

## Статус по сервисам (полностью — в `docs/SERVICES.md`)

| Сервис | Движок | Статус |
|---|---|---|
| VLESS/VMess/Trojan/SS/Hysteria2/TUIC/WireGuard | sing-box | нужен `libbox.aar`; конфиг под схему 1.12+; `HydraPlatformInterface` расширен |
| AmneziaWG 1.0/1.5/2.0 | amneziawg-go | нужен `amneziawg-go.aar`; генерация `.conf`/uapi готова |
| Xray (альт. ядро) | Xray-core | нужен `libXray.aar` + tun2socks (`hev-socks5-tunnel`); `XrayCore` — каркас, streamSettings готовы |
| **SSTP** | userspace PPP/TLS | **готово на Kotlin** (LCP/PAP/MS-CHAPv2/IPCP + crypto-binding); нужен on-device тест |
| **L2TP** | userspace PPP/UDP | **готово на Kotlin** (без IPsec/ESP); нужен on-device тест |
| PPTP | — | честно недоступно (данные в GRE → нужен root; стек удалён из Android 12/13) |
| **WDTT** (beta) | нативный `libclient.so` | WG через TURN ВК; нужен нативный клиент + VK-auth |
| **olcRTC** (beta) | gomobile + tun2socks | TCP-over-WebRTC; нужен `olcrtc.aar` + tun2socks |

Ознакомительные/экспериментальные (WDTT, olcRTC) помечены в UI плашкой **BETA-доступ**
(`Protocol.beta = true`, компонент `BetaBadge`).

## Дорожная карта

Вынесена в [`docs/ROADMAP.md`](ROADMAP.md) (19.09.2026): там план версий 0.6.16 → 0.7.0 и фаз 8–12, а также дословная история фаз 0–7. Открытые технические задачи — ниже, в «Открытых задачах (TODO)».

## Открытые задачи (TODO)

1. **Нативные ядра** (`docs/BUILD.md`). `libbox.aar` (sing-box 1.12.9) —
   ✅ собран и лежит в `app/libs/`, full-APK на нём проверен вживую.
   Остальные пока не собирались: `libXray.aar` (Xray), `amneziawg-go.aar` (AWG),
   `olcrtc.aar` (gomobile), `libclient.so` (WDTT) — но само по себе их наличие
   ничего не включит: Kotlin-интеграция этих движков не дописана (пункты 4-6).
2. **On-device проверка SSTP/L2TP**: согласование PPP, MS-CHAPv2, IPCP, SNAT/чек-суммы
   в `TunBridge`, SSTP crypto-binding против реального SoftEther/Mikrotik.
3. ~~sing-box PlatformInterface: доопределить версионно-зависимые методы~~ —
   готово в 0.5.3: `getInterfaces`/`startDefaultInterfaceMonitor`/`systemCertificates`
   проверены живым подключением на реальном устройстве (см. CHANGELOG 0.5.3).
   `readWIFIState` осознанно `null` (policy-based routing не используется).
4. ~~Xray~~ — **сделано и подтверждено живьём (0.6.6/0.6.7, BETA)**.
   `libXray.aar` собран (Go 1.27, официальный `python3 build/main.py android`).
   Xray-core в отдельном процессе (`:xray`, `XrayEngineService`) — классы
   грузятся ИЗОЛИРОВАННЫМ `DexClassLoader` (`parent = null`, свой
   `classes.dex` собран из нетронутого `classes.jar` через `d8`,
   `.so` — обычные `jniLibs`), а не как обычная Gradle-зависимость: libbox
   и libXray несут несовместимую обвязку gobind (`go.Seq` и т.п. — у каждой
   свой `System.loadLibrary`, JNI-символы жёстко привязаны к исходному имени
   класса), ни дедуп, ни переименование пакета не работают — подробный разбор
   всех пяти багов по пути (два Go-рантайма, неверная `.so`, JNI-символы,
   read-only dex, parent-делегация classloader'а, `fdsan`-краш в
   `HydraVpnService`, `geoip:private` без `geoip.dat`) — «Честные оговорки»
   ниже. Xray поднимается headless с локальным socks5-inbound, sing-box в
   главном процессе — tun2socks-мост (`SingBoxConfigBuilder.buildXrayBridge()`).
   Тумблер «Xray Core для VLESS/VMess/Trojan/SS» — Настройки → Туннель.
   **Подтверждено на устройстве** (OnePlus CPH2747): VLESS через Xray Core
   поднимается и передаёт трафик. Реальный API (`LibXray.invoke(requestJson)`,
   `DialerController.protectFd(long)`) сверен декомпиляцией `.aar`. Подробности
   — docs/BUILD.md, раздел 2.2. Осталось по мелочи: полноценная база GeoIP
   для маршрутизации по странам (сейчас только явные CIDR для приватных
   адресов) — отдельная будущая задача, не блокирует базовое подключение.
5. ✅ **(0.6.19: подпроцесс+SOCKS5→sing-box; апстрим архивирован 14.09.2026, см. docs/ECOSYSTEM.md; не проверено на устройстве)** olcRTC: gomobile-биндинг (`cnc` → локальный SOCKS5) + tun2socks в `OlcRtcCore`.
6. ⛔ **(не берём: апстрим заархивирован, автоматически обходит VK-капчу — docs/ECOSYSTEM.md)** WDTT: JNI к `libclient.so` + поток VK-авторизации (WebView) в `WdttCore`;
   проверить лицензию upstream перед включением бинарника.
7. ~~(Опц.) UI-переключатель движка Xray↔sing-box~~ — уже есть: Настройки → Туннель, `EngineRepository.preferXray`, по умолчанию выключен (sing-box).
8. ~~статистика sing-box через `CommandClient`~~ — **сделано в 0.6.1**:
   `SingBoxRuntime` поднимает `CommandServer`, подключает `CommandClient` на
   `COMMAND_STATUS`, конфиг получил `experimental.clash_api`. Счётчики
   подтверждены на живом туннеле (росли `154 Б` → `24 КБ`). Отдельно
   починен формат: `"%.1f MB"` округлял килобайты в «0,0» — теперь
   `humanBytes()` (Б/КБ/МБ/ГБ).
9. Известные open items из 0.5.3 (не блокируют работу, чинить отдельно):
   `no available network interface` всё ещё проскакивает в первую долю
   секунды при старте подключения (до первого колбэка монитора интерфейса).
   ~~ANSI-коды цвета из лога sing-box~~ — **починено в 0.6.1**: регекс
   вырезал `[36m`, но не захватывал сам ESC (0x1B), из-за чего тот оставался
   и рисовался в UI «точками» (`.INFO.`).
10. **AmneziaWG IP/домен split tunneling паритет** (отложено при Фазе 2,
    см. выше) — делать одним пакетом с TODO №1 (когда появится
    `amneziawg-go.aar` и `AmneziaWgCore` реально заработает), и только вместе
    с правкой `HydraVpnService.establishTun()` под awareness о `netRules`
    (иначе сужение AllowedIPs даёт чёрную дыру, а не bypass). SOCKS5-мост
    SSTP/L2TP в sing-box для той же цели — отдельная, гораздо более крупная
    задача (нужен собственный userspace TCP/IP-стек уровня gVisor netstack);
    заводить как отдельную инициативу, если/когда понадобится.
11. ✅ **(сделано в 0.6.17, не проверено на устройстве — см. CHANGELOG)** Наблюдаемость и переподключение туннеля (Фаза 7a-7c в дорожной карте
    выше) — главный найденный на аудите 19.09.2026 пробел: поднятое
    соединение никем не наблюдается, разрыв/смена сети не меняют
    `VpnState.state`, переподключения нет. 7a сделана в 0.6.14 для
    SSTP/L2TP/Xray; **осталось в 7a:** найти в libbox признак смерти
    `BoxService` (сверять декомпиляцией `libbox.aar`, как API Xray в 0.6.6) —
    без него основной движок не покрыт. Дальше по порядку: 7b (бэкофф +
    `RECONNECTING` + Kill Switch на всё время) → 7c (`NetworkCallback` и
    `setUnderlyingNetworks()` в самом `HydraVpnService`).
12. ✅ **(сделано в 0.6.17 — poll в TunBridge, атомики, Restart-таймер и LCP Echo; on-device не проверено)** Потоки и ресурсы userspace-ядер (Фаза 7d): `TunBridge.stop()` не
    разблокирует `tun-ppp-read` и не закрывает потоки ввода-вывода tun;
    счётчики трафика — не атомарные; в `PppSession` нет Restart-таймера
    ConfReq и своего LCP Echo-Request. Чинить вместе с TODO №2 (on-device
    SSTP/L2TP) — без живого сервера половину этого не проверить.
13. ~~**Мелкая страховка от крэшей и деградации** (Фаза 7e)~~ — **сделано в
    0.6.15**: все семь пунктов (голые старты foreground-сервиса,
    `UncaughtExceptionHandler`, неатомарный `VpnState.log()` и
    неограниченная очередь `LogStore`, параллелизм `measureAllPings()`,
    транзакция при обновлении подписки, double-checked locking в
    `AppDatabase.get()`). Живьём не проверено ничего, кроме ограничителя
    лога (6 unit-тестов, прогнаны).

## Карта кода

```
app/src/main/java/ru/gidravpn/hydra/
  data/model/         Protocol.kt (все протоколы + флаг beta), ServerProfile,
                      SplitTunnel (+ NetRuleType/NetworkRule — Фаза 2, IP/домены)
  data/subscription/  LinkParser, WireGuardParser/ConfigBuilder, SingBoxConfigBuilder
  data/net/           PingMeasurer (TCP-connect замер, Фаза 3)
  data/repository/    ServerRepository, SplitTunnelRepository, ThemeRepository (DataStore)
  vpn/                HydraVpnService, SocketGuard, VpnState
  vpn/ppp/            Md4, MsChapV2, Ppp, PppSession, TunBridge  (общий userspace-PPP)
  vpn/core/           VpnCore (интерфейс)
  ui/theme/           Color.kt (HydraPalette + LocalHydraPalette + два инстанса
                      AmbientPalette/StealthPalette), ThemeMode, Theme.kt, Type.kt
  ui/LauncherIcon.kt  смена ярлыка через activity-alias (по тумблеру, off по умолч.)
  ui/                 экраны (Main/Servers/Profile/SplitTunnel/Settings/Logs),
                      components/Common (BetaBadge, Sparkline, humanBytes)
  res/drawable/       ic_hydra_ambient / ic_hydra_stealth — гербы тем (в UI),
                      ic_launcher_* / ic_launcher_stealth_* — адаптивные иконки
                      Settings — хаб с подэкранами (см. фазу 1 роадмапа выше,
                      включая под-экран «Тема» из Фазы 1.5),
                      Split/Logs теперь рендерятся ВНУТРИ SettingsScreen, а не
                      как отдельные top-level экраны
app/src/native/.../vpn/core/   SingBoxCore(+Runtime), XrayCore(+ConfigBuilder),
                               AmneziaWgCore, SstpCore, L2tpCore(+Transport), PptpCore,
                               WdttCore, OlcRtcCore, NativeCoreFactory
app/src/stub/.../vpn/core/     StubCore (NoopCore — симуляция для stub/CI)
docs/   PROTOCOLS · SECURITY · BUILD · ARCHITECTURE · SERVICES · PANELS · CONTRIBUTING
CHANGELOG.md   — детальный лог по версиям 0.1.0 → 0.6.1 (главный источник контекста)
```

## Честные оговорки

- **0.6.6/0.6.7 — Xray Core: от «собралось» до реально подтверждённого
  подключения на устройстве потребовалось пройти пять отдельных живых
  багов подряд** (классический урок 0.5.3/0.5.4: "собирается" ≠ "работает
  на телефоне", здесь — в разы длиннее обычного). Пользователь прислал
  скриншоты настроек стороннего Xray-based клиента (INCY) и попросил
  довести Hydra до паритета; `XrayCore` был чистой заглушкой
  (`throw NotImplementedError`). Go 1.27/gomobile поставлены на машину
  разработки, `libXray.aar` собран официальным скриптом `XTLS/libXray`.
  1. **Два Go-рантайма в одном процессе.** Первая идея — Xray (headless
     socks5) + sing-box-мост в ОДНОМ процессе — не собралась:
     `checkNativeDebugDuplicateClasses` (`libbox.aar`/`libXray.aar` —
     независимые `gomobile bind`, каждый со своей копией generic-обвязки
     gobind `go.Seq`/`go.Universe`/`go.error`; апстрим `XTLS/libXray`
     прямо предупреждает — Go не поддерживает два рантайма в процессе).
  2. **Наивный дедуп классов — грузит не ту `.so`.** Попытка оставить одну
     копию `go.*` (от libbox) прошла сборку, но в проце `:xray` тянула
     `libbox.so` вместо `libgojni.so` — `UnsatisfiedLinkError` на
     `LibXray._init()`. `go.Seq.<clinit>` зашивает имя библиотеки
     (`System.loadLibrary("box")` vs `"gojni"`) на этапе сборки — копии не
     взаимозаменяемы.
  3. **Переименование пакета тоже не работает.** Попытка `go`→`xraygo`
     через ASM чинит п.2, но JNI у gomobile — implicit linking:
     `libgojni.so` экспортирует `Java_go_Seq_init` (по ИСХОДНОМУ имени
     класса), переименование класса ломает эту привязку, а не чинит.
  4. **Единственный рабочий вариант — изолированный `DexClassLoader`.**
     Классы Xray грузятся в процессе `:xray` из отдельного `classes.dex`
     (нетронутый `classes.jar` → `d8`, задача `libXrayToDex`), `.so` — из
     обычных `jniLibs` (`extractLibXrayNativeLibs`). Дальше ещё три живых
     бага: (a) ART отказывался грузить dex, пока файл был доступен на
     запись самому процессу — `dexFile.setReadOnly()`; (b) обычный
     `parent`-classloader у `DexClassLoader` всё равно ДЕЛЕГИРУЕТ поиск
     классов родителю первым — а родитель (classloader процесса) видит
     `go.Seq` от libbox (та же проблема п.2, но через делегацию, а не
     dex-merge) — фикс: `parent = null` (только boot-classloader, без
     classpath приложения); (c) `fdsan`-краш в `HydraVpnService`: код
     отката при ошибке подключения всегда пытался закрыть и
     `ParcelFileDescriptor`, и raw fd через `adoptFd()` — безопасно, пока
     ядро детачит fd СРАЗУ (как sing-box), но `XrayCore` мог упасть РАНЬШЕ
     detach — двойное закрытие одного номера fd валило процесс SIGABRT.
  5. **`geoip:private` без `geoip.dat`.** Последняя ошибка была уже внутри
     самого Xray — `routing.rules` ссылались на `geoip:private`, а файла
     базы GeoIP в embedded-сборке нет; заменено на явные CIDR приватных
     диапазонов (RFC 1918 + loopback/link-local), без внешних файлов.
  **Подтверждено на реальном устройстве** (OnePlus CPH2747, live-тест
  09-2026): VLESS-соединение через Xray Core поднимается, лог показывает
  реальный туннель. Полноценная база GeoIP для гео-маршрутизации по
  странам — отдельная будущая фича (не нужна для базового подключения),
  см. спавненную задачу про `geoip.dat`.
- **0.6.5 — все релизы 0.5.1–0.6.1 были подписаны Android debug-ключом,
  не релизным.** `scripts/release.sh` собирал `assembleXxxDebug`, а не
  `assembleXxxRelease` — `keystore.properties`/`signingConfigs` в
  `app/build.gradle.kts` существовали с 0.6.1, но реально ни разу не
  использовались до этого релиза. Debug-ключ уникален на каждой сборочной
  машине, поэтому любой следующий релиз с настоящей подписью в любом
  случае стал бы несовместимым обновлением — обнаружено раньше, чем это
  случилось само по себе (`apksigner verify` на APK из v0.6.1 → `CN=Android
  Debug`). Заодно всплыло: из-за `applicationIdSuffix` debug build type
  у всех прошлых релизов был `applicationId=ru.gidravpn.hydra.debug`, а не
  `ru.gidravpn.hydra`. Исправлено: сгенерирован `Hydra.jks` (RSA 4096,
  хранится вне репозитория), `scripts/release.sh` переключён на
  `assembleStubRelease`/`assembleNativeRelease`, добавлена проверка
  `apksigner verify` после сборки (публикация блокируется, если сертификат
  снова `Android Debug`). **Следствие**: 0.6.5 для тех, кто уже поставил
  0.5.1–0.6.1, — не апдейт, а отдельное приложение (другой `applicationId`
  и подпись); нужно ставить рядом и вручную удалять старую версию.
  Начиная с 0.6.5 все релизы на одном ключе — обновления дальше штатные.
- **0.6.0 — главный урок: не доверяй утверждению «native собрать нельзя»,
  проверь `ls app/libs/`.** Весь цикл разработки 0.6.0 шёл на stub-сборке,
  и релиз 0.6.0 был сначала опубликован **только со stub-APK** (то есть
  фактически неработающим приложением) — потому что в этом файле стояла
  фраза «нативные ядра недоступны офлайн», унаследованная с прошлых версий,
  и она принималась на веру без проверки. На деле `libbox.aar` (48.9 МБ,
  sing-box 1.12.9) лежал в `app/libs/` всё это время, и
  `assembleNativeDebug` собирается за ~20 с. Ошибка вскрыта только тогда,
  когда пользователь прямо потребовал full-APK. Последствия исправлены:
  full-APK собран, проверен вживую (реальный туннель, чистый teardown) и
  приложен к релизу; поставлены три защиты от повторения (см. «Защита от
  пустых релизов» ниже). Паритет IP/домен-маршрутизации для
  AmneziaWG/SSTP-L2TP по-прежнему сознательно не сделан — см. TODO №10.
- **0.5.5**: пользователь сам нашёл и сообщил баг «Отключено» в UI при
  реально ещё активной VPN-сети системы (интернет полностью пропадал) —
  не был найден предыдущим раундом живого тестирования, поскольку тесты
  проверяли `dumpsys connectivity` вскоре после первого же цикла
  подключение/отключение, а не десятки секунд спустя, и не переустанавливали
  APK между проверками (что маскировало утечку — сырой fd tun-интерфейса,
  переданный sing-box через `detachFd()`, не гарантированно закрывался
  библиотекой на её стороне). Урок: `dumpsys connectivity` сразу после
  отключения — недостаточная проверка, если владение fd передано сторонней
  нативной библиотеке.
- **0.5.4**: второе устройство в матрице живой проверки — Realme X2 Pro
  (Android 11). Живой тест переключения серверов на реальном устройстве
  сразу вскрыл утечку core/tun при повторном `connect()` и полностью
  нерабочее живое переключение сервера (см. CHANGELOG.md) — оба бага
  невозможно было заметить без реального многократного подключения/
  переключения на устройстве. Split tunneling по приложениям проверен и
  подтверждён рабочим (`dumpsys connectivity`, точный UID-диапазон).
- **0.5.3**: первая версия, реально проверенная живым подключением на
  физическом устройстве (OnePlus 15, Android 16) — до этого весь native-flavor
  был проверен только статически/сборкой. Живая проверка сразу же вскрыла
  два краша (порядок инициализации `VpnState`, JNI-краш монитора интерфейса)
  и полное отсутствие интернета после подключения (см. CHANGELOG.md), которые
  не могли быть найдены без реального устройства. Урок: "собирается" и
  "проходит статический анализ" — не то же самое, что "работает на телефоне".
- **0.5.2**: исправлен краш при подключении VPN на реальных устройствах
  Android 14+ (`foregroundServiceType="systemExempted"` требовал
  signature|privileged-разрешения, которого у стороннего приложения нет —
  см. CHANGELOG.md) и краш userspace-PPP на битых TCP-пакетах
  (`TunBridge.fixChecksums`, отсутствовала проверка границ буфера).
- **Проверено сборкой:** `:app:assembleStubDebug` и `:app:assembleNativeDebug` —
  обе BUILD SUCCESSFUL (`libbox.aar` лежит в `app/libs/`). Full-APK — 119 МБ,
  внутри `libbox.so` под все три ABI, проверен вживую на устройстве.
- **Криптография сверена с RFC:** тест-векторы RFC 1320 (MD4), RFC 2759 §9.2
  (NtPasswordHash/ChallengeHash/NtResponse/AuthenticatorResponse) и
  RFC 3079 §3.5.1 (GetMasterKey) совпадают побайтово (jshell-проверка).
  L2TP — сверен с RFC 2661 (номера AVP, обязательные поля сообщений).
- SSTP/L2TP **не проверялись на устройстве** против реального сервера
  (SoftEther/Mikrotik) — согласование PPP, crypto-binding, SNAT в `TunBridge`
  требуют on-device теста.
- Бинарники/код сторонних проектов не распространяются — поддержаны форматы
  ссылок и точки интеграции. Лицензии: см. `THIRD_PARTY_NOTICES.md`.
- Самый подробный контекст «что и почему» — в `CHANGELOG.md` и `docs/PROTOCOLS.md`.
