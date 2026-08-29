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

## Дорожная карта UI/фич (согласована с пользователем, не в TODO ниже)

Живое тестирование на реальных устройствах регулярно обнаруживало критические
баги (см. «Честные оговорки») — поэтому, помимо инженерных TODO ниже, идёт
отдельный трек UI/UX-доработок по референсу стороннего клиента (показан
пользователем как пример хорошей организации экранов, не как код):

- ✅ **Фаза 0** (0.5.4-0.5.5): live-переключение сервера, утечка core/tun/fd,
  ANSI-мусор в логах, split tunneling верифицирован.
- ✅ **Фаза 1** (0.5.5): Настройки — хаб с подэкранами (Туннель/Split/Логи/
  О приложении), нижняя навигация свёрнута до 3 вкладок.
- ✅ **Фаза 1.5** (редизайн по артбуку «Hydra VPN Design Artbook», не
  версионирован отдельно): новая палитра Hydra Emerald (Abyss/Cyber Slate/
  #00E599) + вторая тема Monochrome Stealth с переключателем в Настройках
  (`ThemeMode`, `ThemeRepository`, `LocalHydraPalette` в `ui/theme/`);
  нижняя навигация расширена до 4 вкладок с иконками (добавлена **Профиль**);
  reactor-кнопка подключения на Canvas (кибер-контур + радар-пульс,
  явное состояние ERROR); карточки серверов — флаг вынесен в чип, пинг
  раскрашен по порогам, протокол-чип вместо точки; новый экран Профиля
  (статистика, sparkline-графики трафика, ссылка на GitHub); упрощённая
  геометрическая иконка приложения. Артбук — AI-моки, использован как
  референс стиля, не скопирован пиксель-в-пиксель (карта мира намеренно
  заменена на стилизованный кибер-радар, не географическую карту).
- ✅ **Фаза 2**: маршрутизация по IP/доменам — второй, независимый от
  по-приложениям, режим split tunneling (`SplitTunnel.netMode`/`netRules`,
  `NetRuleType` — IP/CIDR, домен, поддомены, ключевое слово). UI — вторая
  вкладка в Split-туннелинге («По IP/доменам», те же режимы Весь трафик/
  Только выбранные/Кроме выбранных). Реализовано через `route.rules` в
  `SingBoxConfigBuilder` (правила пользователя приоритетнее служебных
  dns/private-ip; `INCLUDE` меняет `final` на `direct`). **Честная оговорка**:
  работает только при подключении через sing-box-протоколы (VLESS/VMess/
  Trojan/SS/Hysteria2/TUIC/WireGuard) — у `VpnService.Builder` нет доменной
  маршрутизации, а точечное исключение IP (`excludeRoute`) доступно только
  с API 33 при minSdk 26 проекта, поэтому для SSTP/L2TP/AmneziaWG правила
  не действуют (явно показано в UI). `SingBoxCore`/`SingBoxConfigBuilder` —
  часть native-флейвора, живая компиляция недоступна офлайн (нет
  `libbox.aar`) — проверено ревью diff + UI протестирован на устройстве
  (stub-сборка, добавление/удаление/персист правил).
  **Осознанно не расширено** (изучено, но не реализовано — см. TODO №10):
  паритет для AmneziaWG через AllowedIPs не сделан, т.к. `AmneziaWgCore.start()`
  сейчас `throw NotImplementedError` (движок не подключён, ждёт
  `amneziawg-go.aar` из TODO №1), а `HydraVpnService.establishTun()` уже
  ставит OS-уровневый `addRoute("0.0.0.0/0")` до старта движка — сужение
  AllowedIPs дало бы чёрную дыру для несовпадающего трафика вместо bypass;
  настоящий фикс потребовал бы переписать общий `establishTun()` под
  awareness о `netRules`, рискованно менять ради нерабочего движка. SOCKS5-мост
  SSTP/L2TP в sing-box (чтобы и там работали IP/домен-правила) тоже не сделан:
  `TunBridge`/`PppSession` не имеют понятия о TCP-соединениях (только
  `sendIpPacket` целыми IP-пакетами) — потребовался бы собственный userspace
  TCP/IP-стек уровня gVisor netstack, непропорционально дорого ради этой фичи.
- ✅ **Фаза 3**: измерение пинга — `PingMeasurer` (новый, `data/net/`) меряет
  время TCP-connect до `address:port` (ICMP недоступен без root), переиспользуя
  паттерн `Socket()` + `SocketGuard.protect()` уже отработанный в `SstpCore`;
  корректно работает и при активном VPN, и без него (`protect()` — no-op,
  если сервис не подключён). Триггеры в UI: клик по значению/тексту «измерить»
  на карточке сервера (`MainViewModel.measurePing`) и «Обновить пинг» вверху
  экрана для всего списка сразу (`measureAllPings`); `measuringIds`
  показывает «измерение…» на карточке пока идёт замер. Группировка по
  подписке — `ServersScreen` группирует `servers` по `subscriptionId`,
  показывает заголовок с именем подписки и счётчиком только для реальных
  групп (серверы без подписки остаются плоским списком, без лишнего UI).
  Значок протокола был готов ещё в Фазе 1.5 (`ProtocolChip`). Проверено
  живьём на устройстве: реальные значения пинга, персист через
  перезапуск приложения (Room), группировка на импортированной подписке.
- ✅ **Фаза 4**: тест на Xiaomi 12T Pro (модель 22081212UG, Android 15/SDK 35,
  HyperOS). Стенд: только stub-flavor (`assembleStubDebug`) — нативные ядра
  недоступны офлайн (см. TODO №1), поэтому проверка симуляции подключения,
  не реального sing-box/AmneziaWG туннеля. Пройдено живьём: установка
  (HyperOS требует явного подтверждения «Установка через USB» на
  устройстве — по умолчанию блокирует `adb install`), запуск, edge-to-edge/
  отступы под вырез и жестовую навигацию корректны на большом экране
  (1220×2712), добавление сервера, измерение пинга (реальный TCP-connect,
  145мс до 1.1.1.1), системный VPN-consent диалог, полный цикл подключения/
  отключения (`ОТКЛЮЧИТЬ`, статус, трафик-счётчики, elapsed-таймер), Профиль
  (sparkline-графики), Настройки → Тема (живое переключение Emerald ⇄
  Stealth), Логи. Крашей не найдено. **Честная оговорка**: `adb shell input`
  (эмуляция тапов) на HyperOS дополнительно заблокирован отдельным
  тумблером «USB-отладка (настройки безопасности)» — без него ни один тап
  не проходит (`SecurityException: INJECT_EVENTS`); включается в
  Настройках разработчика, применяется не сразу, обычно требует
  переподключения кабеля или перезагрузки устройства.

Полный план с деталями реализации каждой фазы — в
`.claude/plans/modular-kindling-stardust.md` (внутри worktree, не в git).

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
10. **AmneziaWG IP/домен split tunneling паритет** (отложено при Фазе 2,
    см. выше) — делать одним пакетом с TODO №1 (когда появится
    `amneziawg-go.aar` и `AmneziaWgCore` реально заработает), и только вместе
    с правкой `HydraVpnService.establishTun()` под awareness о `netRules`
    (иначе сужение AllowedIPs даёт чёрную дыру, а не bypass). SOCKS5-мост
    SSTP/L2TP в sing-box для той же цели — отдельная, гораздо более крупная
    задача (нужен собственный userspace TCP/IP-стек уровня gVisor netstack);
    заводить как отдельную инициативу, если/когда понадобится.

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
                      EmeraldPalette/StealthPalette), ThemeMode, Theme.kt, Type.kt
  ui/                 экраны (Main/Servers/Profile/SplitTunnel/Settings/Logs),
                      components/Common (BetaBadge, Sparkline)
                      Settings — хаб с подэкранами (см. фазу 1 роадмапа выше,
                      включая под-экран «Тема» из Фазы 1.5),
                      Split/Logs теперь рендерятся ВНУТРИ SettingsScreen, а не
                      как отдельные top-level экраны
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
