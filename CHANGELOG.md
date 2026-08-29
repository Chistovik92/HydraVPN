# Changelog

## [0.5.4] — живое переключение сервера, утечка тоннеля, чистые логи
Найдено и исправлено по отчёту пользователя: «переключение локации не
работает на лету, а после отключения тоннель остаётся активным». Проверено
живым тестом на **Realme X2 Pro (Android 11)**.

### Исправлено
- **Переключение сервера при активном соединении не работало**: выбор
  другого сервера в списке во время подключения раньше только менял
  выбранный ID, никак не влияя на уже поднятый туннель. Теперь тап по
  серверу при `CONNECTED`/`CONNECTING` сразу переключает туннель — без
  повторного запроса VPN-consent (он уже выдан).
- **Утечка core/tun при повторном connect()**: `HydraVpnService.connect()`
  безусловно перезаписывал поля `core`/`tun`, не останавливая предыдущие
  значения — при переключении сервера старое ядро и tun-интерфейс никогда
  не получали `stop()`/`close()` и оставались активными в фоне, невидимые
  для UI. Добавлен teardown перед установкой нового соединения + отмена
  предыдущей ещё не завершившейся попытки подключения.
- **ANSI-коды в логах**: sing-box шлёт строки с терминальными
  цветовыми escape-последовательностями — Compose Text их не
  интерпретирует, они утекали в UI как мусорные символы. Вырезаются перед
  сохранением строки лога.

### Верификация
- Realme X2 Pro (Android 11): подключение к серверу А → тап на сервер Б в
  списке во время активного соединения → туннель переключился без ручного
  отключения (новый tun-интерфейс, старый корректно погашен, `dumpsys
  connectivity` показал ровно одну VPN-сеть).
- Split tunneling (режим «Кроме выбранных», одно приложение) — `dumpsys
  connectivity` подтвердил точное исключение UID выбранного приложения из
  диапазона VPN-сети.
- Полный цикл подключение → отключение — без осиротевших
  сетей/процессов.

## [0.5.3] — краш на каждом запуске + «подключено, но нет интернета»
Оба бага найдены и исправлены живой отладкой на реальном устройстве
(OnePlus 15, Android 16, через adb: logcat, dumpsys dropbox/connectivity,
live-переустановка) — 0.5.2 не переживал запуск на реальном железе.

### Исправлено
- **Краш на КАЖДОМ запуске приложения**: `object VpnState` инициализировал
  поле `_logs` раньше `fmt` (используется внутри `line()`) — на момент
  вычисления `_logs` поле `fmt` было ещё `null`, `fmt.format(Date())` падал
  с `NullPointerException` в `<clinit>`. `VpnState` впервые трогается в
  конструкторе `MainViewModel`, создаваемой в `MainActivity.onCreate()` до
  отрисовки UI, — падало на каждом запуске, в любом флейворе, независимо от
  версии Android. Похоже, это и был исходный краш из самой первой жалобы.
- **Второй JNI-краш**: `startDefaultInterfaceMonitor` регистрировал
  `ConnectivityManager.NetworkCallback` на внутреннем потоке
  `ConnectivityManager` — вызов `listener.updateDefaultInterface()` (JNI в
  Go) с этого «чужого» потока воспроизводимо валил процесс нативным
  `SIGABRT` (тумбстоун, поток `ConnectivityThread`), что не перехватывается
  никаким `try/catch`/`runCatching` на стороне Kotlin. Теперь колбэк
  регистрируется на выделенном `HandlerThread`.
- **«Подключено», но нет интернета** (0.0 MB, 100% исходящих TCP-соединений
  sing-box падали с `no available network interface`):
  - `registerDefaultNetworkCallback()` у **владельца** VPN воспроизводимо
    репортил **собственный `tun0`** как «дефолтную сеть» вместо реального
    Wi-Fi/мобильного интерфейса — sing-box пытался биндить исходящий сокет
    на собственный туннель. Заменено на `registerNetworkCallback()` с явным
    `NetworkRequest`, требующим `NET_CAPABILITY_NOT_VPN`.
  - `getInterfaces()` никогда не заполнял поле `flags` (Go `net.Flags`) —
    все интерфейсы выглядели «не поднятыми» (flags=0), sing-box отбрасывал
    их все как непригодные.
  - `getInterfaces()` отдавал голый `hostAddress` без CIDR-маски, а для
    IPv6 link-local адресов ещё и с zone-id (`fe80::...%rmnet_data0`) —
    Go-паника `netip.ParsePrefix`.
  - Добавлен `VpnService.protect(fd)` через `SocketGuard` как официальный
    резервный механизм (`usePlatformAutoDetectInterfaceControl = true`).
- Экран «Настройки» показывал захардкоженную версию (`Hydra 0.5.1`,
  отставшую от реального релиза) — теперь берётся из `BuildConfig.VERSION_NAME`.

### Изменено
- `VpnState.log()` дублирует сообщения в logcat (тег `HydraCore`) — упрощает
  диагностику на реальном устройстве без пересборки debug-APK ради
  временных логов.
- `buildFeatures.buildConfig = true` — включено для доступа к
  `BuildConfig.VERSION_NAME` из UI.

### Верификация
- Подтверждено на реальном устройстве: чистая установка → запуск без
  краша → подключение → загрузка настоящих страниц в браузере
  (accessibility-дамп подтвердил результаты поиска с внешним IP через
  VPN-сервер, отличным от реального IP телефона).
- `no available network interface` сведено со 100% исходящих TCP-соединений
  до единичных в первую долю секунды при старте (до первого сетевого
  колбэка) — не устранено полностью, но не мешает работе.

## [0.5.2] — критические фиксы краша + верификация native-сборки
### Исправлено
- **Краш при каждом подключении VPN на Android 14+**: `AndroidManifest.xml`
  объявлял `HydraVpnService` с `android:foregroundServiceType="systemExempted"`,
  а разрешение `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` имеет уровень защиты
  `signature|privileged` и никогда не выдаётся сторонним приложениям —
  `startForeground()` в `HydraVpnService.onStartCommand` падал с
  `SecurityException` при каждой попытке подключения. Заменено на
  `specialUse` + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn" />`.
- **Краш userspace-PPP (SSTP/L2TP) на битых TCP-пакетах**:
  `TunBridge.fixChecksums` не проверял границы буфера для TCP-ветки (в
  отличие от UDP-ветки рядом) — фрагментированный/укороченный TCP-пакет от
  сервера приводил к `ArrayIndexOutOfBoundsException` в фоновом потоке без
  обработчика и убивал процесс целиком. Добавлена проверка `p.size >= ihl + 18`.
- **ANR на экране Split Tunneling**: `PackageManager.queryIntentActivities()`
  вызывался синхронно на главном потоке при первом рендере списка
  приложений — перенесено на `Dispatchers.Default` через `LaunchedEffect`.

### Добавлено
- Runtime-запрос `POST_NOTIFICATIONS` (Android 13+) в `MainActivity.onCreate` —
  разрешение было объявлено в манифесте, но никогда не запрашивалось, из-за
  чего постоянное уведомление о статусе VPN не показывалось пользователю.

### Изменено (накоплено после 0.5.1, ранее не отражено в Changelog)
- **native-flavor**: интеграция с реальным `libbox.aar` 1.12.9 (sing-box);
  проверенная рецептура сборки — `docs/BUILD.md` (Go 1.25, `-checklinkname=0`).
- Правки компиляции stub-сборки, стандартный `gradle-wrapper`.
- CI: выгрузка stub-APK как артефакта workflow.

### Известные ограничения
- SSTP/L2TP по-прежнему не проверялись on-device против реального сервера
  (SoftEther/Mikrotik) — исправлен только конкретный краш чек-суммы, полное
  согласование PPP не подтверждено.
- native-flavor компилируется только при наличии `.aar` — ожидаемо, см.
  `docs/BUILD.md`.

## [0.5.1] — выпуск: лицензии, документация, CI
### Добавлено
- `LICENSE` — полный текст GPL-3.0 (канонический, gnu.org).
- `THIRD_PARTY_NOTICES.md` — лицензии встраиваемых компонентов
  (sing-box GPL-3.0, Xray-core MPL-2.0, amneziawg-go/wireguard-go MIT,
  hev-socks5-tunnel MIT, WDTT/olcRTC — проверить upstream) и библиотек.
- `docs/SERVICES.md` — сервисы и интеграции: панели, SoftEther/Mikrotik
  (SSTP/L2TP), WDTT (TURN ВК), olcRTC; таблица честных ограничений для поддержки.
- `docs/SECURITY.md` — политика безопасности, модель угроз (SSTP-сертификаты,
  L2TP без IPsec, PAP, beta-движки), процесс disclosure.
- CI: триггеры на `master` и `main`.

### Изменено
- `docs/PROTOCOLS.md` — разделы userspace-PPP (SSTP/L2TP/PPTP, TunBridge,
  SocketGuard), WireGuard/AmneziaWG 1.0/1.5/2.0, BETA-движки; лицензии.
- `docs/ARCHITECTURE.md` — карта кода 0.5.1 (ppp-стек, split tunneling,
  SocketGuard, все ядра).
- `docs/BUILD.md` — сборка amneziawg-go.aar, libclient.so (WDTT),
  olcrtc.aar + tun2socks.
- `README.md` — финальная таблица протоколов, флейворы, быстрый старт,
  ссылка на репозиторий.
- `docs/HANDOFF.md` — обновлён до состояния 0.5.1 (открытые задачи,
  карта кода).

### Известные ограничения
- Собранных `.aar`/`.so` в репозитории нет (лицензионная политика);
  сборка ядер — docs/BUILD.md. Flavor `stub` собирается в CI без них.

## [0.5.0] — раздельное туннелирование
### Добавлено
- **SplitTunnel**: модель (`SplitTunnelMode` OFF/INCLUDE/EXCLUDE + список
  пакетов), хранение в DataStore (`SplitTunnelRepository`, JSON).
- **Экран SplitTunnel**: выбор режима, список установленных приложений
  (launcher-intent, пометка системных), поиск, переключатель системных.
- Применение правил в `HydraVpnService.establishTun`: `addAllowedApplication`/
  `addDisallowedApplication` по режиму (INCLUDE/EXCLUDE); собственное
  приложение всегда вне VPN.
- Вкладка «Split» в навигации.
- `MainViewModel`: splitTunnel-состояние, setMode/toggleApp.

### Изменено
- `establishTun` стал suspend (чтение DataStore до establish()).

## [0.4.1] — Xray streamSettings и sing-box PlatformInterface
### Добавлено
- **XrayConfigBuilder**: полный `streamSettings` — tls (serverName/alpn/uTLS/
  allowInsecure), reality (publicKey/shortId/spiderX), транспорт ws/grpc/http/
  httpupgrade/tcp-header; outbounds под протокол (vmess/trojan/ss/vless),
  DNS и маршрутизация (geoip:private → direct).
- **XrayCore**: детальная схема моста «tun → tun2socks (hev-socks5-tunnel) →
  socks-inbound Xray» с параметрами запуска.
- **HydraPlatformInterface** расширен (реф. SagerNet/sing-box-for-android →
  PlatformInterfaceWrapper): `findConnectionOwner` (ConnectivityManager, API 29+),
  `usePlatformDefaultInterfaceMonitor`, `underNetworkExtension`,
  `includeAllNetworks`; версионно-зависимые каркасы `startDefaultInterfaceMonitor`,
  `getInterfaces`, `readWIFIState`, `systemCertificates` (TODO-маркеры под
  конкретную версию libbox).
- `AppCtx.appContext` — контекст для платформенных методов libbox.

### Известные ограничения
- Сигнатуры libbox-типов PlatformInterface сверяются после сборки .aar
  (компилятор укажет расхождения — это ожидаемо, см. SingBoxCore).

## [0.4.0] — WDTT и olcRTC (BETA-доступ)
### Добавлено
- **WDTT (beta)**: WireGuard через TURN-релей облака ВК. Движок — нативный
  `libclient.so` (JNI), поток VK-авторизации (WebView) — каркас `WdttCore`
  с честным отказом до сборки библиотеки и проверки лицензии upstream.
- **olcRTC (beta)**: TCP поверх WebRTC DataChannel. Движок — gomobile
  `olcrtc.aar` (компонент cnc → локальный SOCKS5) + tun2socks
  (hev-socks5-tunnel) — каркас `OlcRtcCore`.
- Флаг `Protocol.beta` и компонент `BetaBadge`: ознакомительные протоколы
  помечены в UI (карточка сервера, главный экран, выбор протокола).
- `Engine.WDTT`, `Engine.OLCRTC`; маршрутизация в `NativeCoreFactory`.

### Известные ограничения
- WDTT/olcRTC требуют `libclient.so` / `olcrtc.aar` + hev-socks5-tunnel
  (docs/BUILD.md); до этого подключение завершается понятным отказом.

## [0.3.0] — SSTP, L2TP и userspace-PPP-стек
### Добавлено
- **Userspace PPP-стек на Kotlin** (`vpn/ppp`, без нативных .aar):
  - `Md4` (RFC 1320 — нужен для MS-CHAPv2, в JCE отсутствует);
  - `MsChapV2` (RFC 2759 + RFC 3079 GetMasterKey — CMK для SSTP crypto-binding),
    константы и тест-векторы сверены с RFC;
  - `Ppp` — фрейм-кодек (LCP/IPCP/CHAP/PAP, опции);
  - `PppSession` — машина состояний: LCP → MS-CHAPv2/PAP → IPCP (IP/DNS по IPCP);
  - `TunBridge` — мост tun ⇄ PPP: SNAT 172.19.0.1 ⇄ назначенный IP,
    инкрементальная коррекция чек-сумм IPv4/TCP/UDP (RFC 1624).
- **SSTP** (`SstpCore`, MS-SSTP): TCP → TLS 1.2/1.3 (SNI, опция allow_insecure),
  HTTP SSTP_DUPLEX_POST /sra_{BA195780-…}/, CALL_CONNECT_REQUEST/ACK,
  PPP поверх SSTP-кадров, CALL_CONNECTED с crypto-binding
  (Compound MAC = HMAC-SHA1/SHA256 от CMK), ECHO-поддержка, проверка хэша
  сертификата сервера из ENC_INFO.
- **L2TP** (`L2tpCore` + `L2tpTransport`, RFC 2661): SCCRQ→SCCRP→SCCCN,
  ICRQ→ICRP→ICCN (AVP по RFC §4.4), Ns/Nr + ZLB-ack, ретрансмиссия,
  опциональный tunnel-auth (Challenge/Challenge Response, MD5),
  PPP поверх UDP-канала данных. Без IPsec/ESP (недоступен в userspace).
- **PPTP** (`PptpCore`): честный отказ — GRE (IP-протокол 47) требует
  raw-сокетов/root, стек удалён из Android 12/13.
- `SocketGuard` — protect() сокетов userspace-ядер против петли маршрутизации.
- Парсинг ссылок `sstp://`, `l2tp://`, `pptp://` (user:pass@host:port).

### Изменено
- Ветка IKEv2/IPsec (`Ikev2Connector`) удалена: L2TP теперь настоящий
  userspace-PPP; `Engine.IKEV2` → `Engine.USERSPACE`/`Engine.UNAVAILABLE`.
- MTU tun для PPP-движков — 1400 (иначе фрагментация на TLS/UDP).

### Известные ограничения
- SSTP/L2TP требуют проверки на устройстве против реального SoftEther/Mikrotik
  (согласование PPP, crypto-binding, SNAT в `TunBridge`).
- L2TP без IPsec: шифрование только на уровне PPP (MS-CHAPv2 не шифрует данные);
  для защищённого канала используйте SSTP/WireGuard.

## [0.2.0] — Hydra: WireGuard и AmneziaWG
### Добавлено
- **WireGuard**: outbound в конфиге sing-box (`SingBoxConfigBuilder`), импорт `.conf`
  и ссылок `wireguard://` (`WireGuardParser`).
- **AmneziaWG 1.0/1.5/2.0**: парсер параметров обфускации (Jc/Jmin/Jmax/S1/S2/H1–H4 —
  v1.x; I1–I5 — v2.0), генерация `.conf` и uapi (`WireGuardConfigBuilder`),
  каркас ядра `AmneziaWgCore` (движок amneziawg-go, требуется `amneziawg-go.aar`).
- Deep-link schemes `wireguard://` и `awg://`; вставка `.conf` целиком в поле импорта.
- `Protocol.WIREGUARD` / `Protocol.AMNEZIAWG`, движок `Engine.AWG`.

### Изменено
- **Ребрендинг**: ShadowLink → **Hydra**, пакет `com.shadowlink` → `ru.gidravpn.hydra`
  (applicationId `ru.gidravpn.hydra`, БД `hydra.db`, tun-интерфейс `hydra-tun`).

### Известные ограничения
- AmneziaWG-туннель требует сборки `amneziawg-go.aar` (см. docs/BUILD.md);
  генерация `.conf`/uapi готова и не зависит от .aar.

## [0.1.0] — каркас
### Добавлено
- UI на Jetpack Compose по макету (Главная / Серверы / Логи / Настройки).
- Модель данных, Room, репозиторий серверов и подписок.
- Парсер ссылок: vless, vmess, trojan, ss, hysteria2, tuic; импорт подписок (base64/plain).
- Генератор конфигов sing-box и Xray.
- `HydraVpnService` (tun) + абстракция ядра `VpnCore`.
- Flavors `stub` (симуляция) и `native` (libbox/libXray).
- Ветка IKEv2/IPsec (`Ikev2Connector`) как замена L2TP.
- Документация: архитектура, сборка, протоколы, панели.

### Известные ограничения
- Реальные туннели требуют сборки `libbox.aar` / `libXray.aar`.
- `PlatformInterface` sing-box реализован частично (нужна доводка под версию libbox).
- Xray требует tun2socks-моста.
- Статистика трафика в native-сборке — каркас (нужен CommandClient).
