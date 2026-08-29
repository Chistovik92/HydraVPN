# HANDOFF — состояние проекта Hydra

Документ для продолжения работы (в т.ч. под другим аккаунтом/у другого
разработчика). Главные источники контекста: `CHANGELOG.md` (детальный лог
по версиям 0.1.0 → 0.5.5) и `docs/PROTOCOLS.md`.

## Что это

**Hydra** — мультипротокольный VPN-клиент для Android.
- Пакет / appId: `ru.gidravpn.hydra`
- Стек: Kotlin + Jetpack Compose, minSdk 26, compileSdk 35
- Лицензия: **GPL-3.0** (`LICENSE`), сторонние компоненты — `THIRD_PARTY_NOTICES.md`
- Сайт: https://gidravpn.ru · Telegram: https://t.me/+WWJFBZVhxBs4ZmNi
- Текущая версия: **0.5.5** (`app/build.gradle.kts` → `versionName`)
- Флейворы сборки: `stub` (симуляция, без нативных `.aar`, собирается и в CI) и
  `native` (реальные ядра, требует `.aar`/`.so`).

## Быстрый старт

```bash
# stub-сборка (без нативных ядер, для UI/логики/CI):
./gradlew :app:assembleStubDebug
# native-сборка — сначала положите .aar/.so (см. docs/BUILD.md), затем:
./gradlew :app:assembleNativeRelease
```
`gradle-wrapper.jar` в репозиторий не кладётся (бинарник) — CI генерирует его
сам (`.github/workflows/android.yml`), локально: `gradle wrapper`.

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

## Открытые задачи (TODO)

1. **Собрать нативные ядра** и положить как `.aar`/`.so` — `docs/BUILD.md`:
   `libbox.aar` (sing-box), `libXray.aar` (Xray), `amneziawg-go.aar` (AWG),
   `olcrtc.aar` (gomobile), `libclient.so` (WDTT). В оффлайн-среде это не делалось.
2. **On-device проверка SSTP/L2TP**: согласование PPP, MS-CHAPv2, IPCP, SNAT/чек-суммы
   в `TunBridge`, SSTP crypto-binding против реального SoftEther/Mikrotik.
3. ~~sing-box PlatformInterface: доопределить версионно-зависимые методы~~ —
   готово в 0.5.3: `getInterfaces`/`startDefaultInterfaceMonitor`/`systemCertificates`
   проверены живым подключением на реальном устройстве (см. CHANGELOG 0.5.3).
   `readWIFIState` осознанно `null` (policy-based routing не используется).
4. **Xray**: мост tun2socks (`hev-socks5-tunnel`) + запуск ядра в `XrayCore`
   (streamSettings уже готовы в `XrayConfigBuilder`).
5. **olcRTC**: gomobile-биндинг (`cnc` → локальный SOCKS5) + tun2socks в `OlcRtcCore`.
6. **WDTT**: JNI к `libclient.so` + поток VK-авторизации (WebView) в `WdttCore`;
   проверить лицензию upstream перед включением бинарника.
7. (Опц.) UI-переключатель движка Xray↔sing-box для vless/vmess.
8. (Опц.) статистика sing-box через `CommandClient` (`SingBoxRuntime`, сейчас каркас) —
   в 0.5.3 подтверждено, что счётчики трафика в UI всегда показывают 0,0 MB
   именно поэтому (реальный трафик при этом идёт нормально).
9. Известные open items из 0.5.3 (не блокируют работу, чинить отдельно):
   `no available network interface` всё ещё проскакивает в первую долю
   секунды при старте подключения (до первого колбэка монитора интерфейса);
   ANSI-коды цвета из лога sing-box (`\x1b[36mINFO...`) выводятся в UI как
   есть, не отфильтрованы.

## Карта кода

```
app/src/main/java/ru/gidravpn/hydra/
  data/model/         Protocol.kt (все протоколы + флаг beta), ServerProfile, SplitTunnel
  data/subscription/  LinkParser, WireGuardParser/ConfigBuilder, SingBoxConfigBuilder
  data/repository/    ServerRepository, SplitTunnelRepository (DataStore)
  vpn/                HydraVpnService, SocketGuard, VpnState
  vpn/ppp/            Md4, MsChapV2, Ppp, PppSession, TunBridge  (общий userspace-PPP)
  vpn/core/           VpnCore (интерфейс)
  ui/                 экраны (Main/Servers/SplitTunnel/Settings/Logs), components/Common (BetaBadge)
app/src/native/.../vpn/core/   SingBoxCore(+Runtime), XrayCore(+ConfigBuilder),
                               AmneziaWgCore, SstpCore, L2tpCore(+Transport), PptpCore,
                               WdttCore, OlcRtcCore, NativeCoreFactory
app/src/stub/.../vpn/core/     StubCore (NoopCore — симуляция для stub/CI)
docs/   PROTOCOLS · SECURITY · BUILD · ARCHITECTURE · SERVICES · PANELS · CONTRIBUTING
CHANGELOG.md   — детальный лог по версиям 0.1.0 → 0.5.5 (главный источник контекста)
```

## Честные оговорки

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
- **Проверено сборкой:** `:app:assembleStubDebug` — BUILD SUCCESSFUL
  (main sourceSet: PPP-стек, split tunneling, UI, Room/KSP). Native-flavor
  компилируется только при наличии `.aar` — это ожидаемо (см. docs/BUILD.md).
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
