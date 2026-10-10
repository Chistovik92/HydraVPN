# Мультиплатформенность

Статус на 0.7.2: **Android, Windows, Linux, macOS — рабочие клиенты; iOS — собирается в CI
без подписи.** Каждый релиз публикуется только со всеми платформами (`.github/workflows/release.yml`).

| Платформа | Клиент | Ядро | Сборка / проверка |
|---|---|---|---|
| Android | `app/` (Jetpack Compose) | libbox (sing-box 1.12.25), Xray, AWG, olcRTC, OpenFlux, SSTP/L2TP | локально `scripts/release.sh` (подпись) + `android.yml` |
| Windows 10/11 x64 | `desktop/` (Compose Desktop) | sing-box 1.12.25 + Xray-core 26.9.30 процессами | `desktop.yml` на `windows-latest`: MSI, EXE, portable ZIP |
| Linux x64 / arm64 | `desktop/` | sing-box 1.12.25 + Xray-core 26.9.30 процессами | `ubuntu-24.04`, `ubuntu-24.04-arm`: DEB, RPM, AppImage, tar.gz |
| macOS 12+ arm64 / x64 | `desktop/` | sing-box 1.12.25 + Xray-core 26.9.30 процессами | `macos-15`, `macos-15-intel`: DMG (ad-hoc подпись) |
| iOS | `ios/` (SwiftUI + Network Extension) | Libbox.xcframework, WireGuardKit (AWG) | `ios.yml`: неподписанный `.ipa` |

## Архитектура

```
HydraVPN/
├── app/        Android. Свои копии data-классов (см. ниже «Android и :shared»)
├── shared/     KMP: commonMain — модели, LinkParser/UriParser, SingBoxConfigBuilder,
│               PPP-протокол; androidMain — Room-сущности (пока не используются)
├── desktop/    Compose Desktop: src/main/kotlin (UI, AppController, core/*),
│               src/test/kotlin (конфиги, парсеры, хранилище, рендер экранов)
└── ios/        SwiftUI-приложение, HydraTunnel (Libbox), HydraAWG, виджеты
```

### Desktop изнутри

- **Движки** — как на Android: sing-box (все протоколы ПК) и Xray-core (VLESS/VMess/Trojan/SS).
  Xray поднимается отдельным процессом с socks-inbound на 127.0.0.1 **с паролем**, sing-box —
  мост к нему (TUN/прокси, DNS, все правила маршрутизации). Выбор — `EngineToggles` из `:shared`,
  тумблеры в Настройки → Движки. В TUN трафик самого Hydra и процесса Xray идёт мимо туннеля
  (`process_path` → direct), адрес сервера для Xray разрешается до подъёма туннеля.
- **Маршрутизация** — раздельное туннелирование по программам (`process_name`/`process_path`,
  `find_process`; INCLUDE — логическое правило с `invert`, ограниченное локальным inbound, чтобы
  не задеть раздачу в LAN), по сайтам/IP (те же `netRules`, что на Android), по странам, профили.
  Все значения проходят `core/Rules.kt` (валидация) — и из UI, и из `hydra.json`/резервной копии.
- **Ядра** — официальные бинарники sing-box (той же версии, что libbox в Android/iOS) и Xray-core.
  Gradle-задача `downloadSingBox` берёт архив с GitHub SagerNet, **сверяет SHA-256**
  (закреплены в `desktop/build.gradle.kts`) и кладёт в ресурсы пакета своей ОС/архитектуры.
- **Конфиг** — `DesktopConfig` поверх общего `SingBoxConfigBuilder`: outbound, DNS,
  sniff/hijack-dns, geo-правила — те же, что в Android; платформенная часть заменяется:
  tun с `auto_route`+`strict_route` (маршруты ставит sing-box) или mixed-inbound
  127.0.0.1; WireGuard — endpoint (схема 1.12); clash_api на 127.0.0.1 с секретом.
- **Режимы.** «Системный прокси» (по умолчанию) — без прав администратора; прокси ОС
  ставит/возвращает Hydra сама (`SystemProxy`: реестр+WinINet, networksetup, gsettings/KDE),
  с резервной копией на диске — после сбоя восстанавливается при следующем запуске.
  «TUN» — весь трафик: Windows — перезапуск Hydra от администратора (UAC);
  Linux — копия ядра с `cap_net_admin` (однократно `pkexec setcap`); macOS — ядро от root
  через системный запрос пароля.
- **Обход DPI и Telegram (0.7.13–0.7.15).** ByeDPI (`ciadpi`) — подпроцесс-`Sidecar` (бинарь релиза), порт 10880; TG WS — внутри процесса Hydra (`TgWsProxy`,
  порт 10881; процесс Hydra в TUN исключён, как и ядра); план маршрутизации (`AppController.buildPlan`) поднимает нужное до sing-box, `CoreRunner` останавливает
  при отключении. Вкладки «Маршрутов» — `RoutingScreen`/`DpiRoutesScreen`, выпадающие списки — `Dropdowns.kt`; Classic — Swing-панели. Значок — `Appearance.kt` + `core/LauncherIcon.kt`
  (окно, трей, ярлыки Windows/Linux, Dock macOS). Подробно — `docs/ROUTING.md`.
- **Проверка соединения** — после старта HTTP-запрос через outbound proxy (clash_api
  `/proxies/proxy/delay`). «Подключено» показывается только если он прошёл.
- **Данные** — один JSON: `%APPDATA%\Hydra`, `~/Library/Application Support/Hydra`,
  `~/.config/hydra`.

### Что проверяется в CI на каждой ОС (`desktop.yml`)

1. `:desktop:test` — конфиги для 9 протоколов × 3 ОС × 2 режима, разбор ссылок (IPv6,
   `+` в паролях, base64 с переносами), хранилище, рендер всех экранов в PNG.
2. `sing-box check` каждого сгенерированного конфига настоящим ядром.
3. `scripts/desktop-e2e.sh` — **реальный трафик**: локальный сервер Shadowsocks 2022 +
   клиент из конфига Hydra; PROXY (HTTP, SOCKS5, clash_api delay) и TUN (curl без прокси
   должен пройти через tun-in → proxy).
4. `scripts/package-desktop.sh` — пакеты, проверка, что ядро и geo-базы внутри,
   дымовой запуск собранного приложения (20 с).

## Android и `:shared`

В `app/` лежат свои копии классов с теми же именами, что в `:shared`
(`ru.gidravpn.hydra.data.model.*`, `data.subscription.*`, `vpn.ppp.*`), но с другой
реализацией (android.net.Uri, Room-аннотации, IPv6-поддержка в SingBoxConfigBuilder).
В 1936f1d `app` подключал `:shared` — в APK попадала одна копия из двух, какая —
решал порядок слияния dex. В 0.6.25 зависимость снята. Перевод Android на `:shared` —
отдельная задача: удалить дубли из `app`, догнать `:shared` до версий `app`
(ipv6 в SingBoxConfigBuilder и т.п.), прогнать `app`-тесты.

## Ограничения (честно)

- На ПК только протоколы ядер sing-box и Xray: VLESS/REALITY, VMess, Trojan, Shadowsocks,
  Hysteria2, TUIC, WireGuard. AmneziaWG, SSTP, L2TP, olcRTC, OpenFlux, WDTT — только Android.
- Kill switch на ПК — только в режиме «Системный прокси» (прокси ОС остаётся на мёртвый порт).
  В TUN блокировки на уровне брандмауэра нет: при обрыве до переподключения трафик идёт напрямую.
- Раздельное туннелирование по программам в режиме «Системный прокси» касается только программ,
  которые ходят через системный прокси; для всех программ — режим TUN.
- Нет блокировки приложения паролем, тем оформления и языков кроме русского (есть на Android).
- Пакеты не подписаны сертификатами издателя (Windows — SmartScreen, macOS — не
  нотаризовано, нужно «Всё равно открыть»).
- Интерфейс ПК — только русский.
- iOS не запускался на iPhone: для Network Extension нужен платный Apple Developer Program.
- Каталоги `desktop/src/commonMain|desktopMain|desktopTest` и скрипты
  `scripts/build-desktop.sh`, `package-*-{msi,deb,appimage}.sh` — нерабочая заготовка
  1936f1d, не компилируются и не используются; к удалению.

## Сборка локально

```bash
gradle :desktop:test                     # тесты + конфиги в desktop/build/singbox-configs
gradle :desktop:run                      # запустить клиент
scripts/package-desktop.sh windows-x64   # пакеты своей ОС в dist/
```
