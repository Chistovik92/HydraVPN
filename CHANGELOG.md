# Changelog

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
