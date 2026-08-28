# Протоколы

## Семейство proxy (Xray / sing-box)

Клиент строит конфиг из `ServerProfile` и запускает нативное ядро поверх tun.

### VLESS
- Транспорт: `tcp` / `ws` / `grpc` / `http` / `httpupgrade`.
- Безопасность: `tls` / `reality` / `none`.
- REALITY: `pbk` (public key) и `sid` (short id) хранятся в `extra`.
- Flow: `xtls-rprx-vision`.
- Ссылка: `vless://uuid@host:port?type=ws&security=reality&pbk=...&sid=...&sni=...&flow=...&fp=chrome#name`

### VMess
- Ссылка: `vmess://<base64 JSON>` (поля `add/port/id/net/tls/host/path/aid/ps`).

### Trojan
- `trojan://password@host:port?sni=...&type=...#name`, обычно поверх TLS.

### Shadowsocks
- `ss://base64(method:password)@host:port#name` или полностью base64-вариант.
- Метод хранится в `extra.method` (например `aes-256-gcm`, `chacha20-ietf-poly1305`).

### Hysteria2 (QUIC)
- `hysteria2://password@host:port?sni=...&obfs=salamander&obfs-password=...#name`.
- Только sing-box.

### TUIC v5 (QUIC)
- `tuic://uuid:password@host:port?sni=...&congestion_control=bbr&alpn=h3#name`.
- Только sing-box.

### Про движки
- **sing-box** обслуживает tun сам (через `PlatformInterface.openTun`) и покрывает
  протоколы выше **и WireGuard** — рекомендуемый движок по умолчанию.
- **Xray-core** сам tun **не** обслуживает. Схема: `tun → tun2socks → socks-inbound Xray`.
  Xray поднимается с локальным socks5 (напр. `127.0.0.1:10808`), а tun2socks
  (`hev-socks5-tunnel`) перекладывает пакеты из tun-fd в этот socks. Xray оставлен
  как альтернатива для сценариев, где важна именно его реализация XTLS.

## WireGuard / AmneziaWG

### WireGuard (через sing-box)
- Импорт: `.conf` (секции `[Interface]`/`[Peer]`), ссылки `wireguard://<base64 конфига>`.
- Ключи в `extra` (`private_key`, `public_key`, `preshared_key`, `allowed_ips`,
  `local_address`, `mtu`, `dns`); endpoint → `address:port` профиля.
- sing-box-конфиг собирается в `SingBoxConfigBuilder` (outbound `wireguard`).

### AmneziaWG 1.0 / 1.5 / 2.0 (отдельный движок amneziawg-go)
- Обфускация: **1.0/1.5** — `Jc, Jmin, Jmax, S1, S2, H1–H4` (мусорные пакеты);
  **2.0** — `I1–I5` (маркеры заголовков). Версия профиля определяется автоматически
  при импорте (`extra.awg_version`).
- `WireGuardParser` разбирает оба варианта и ссылки `awg://<base64>`.
- `WireGuardConfigBuilder` собирает `.conf` и **uapi** (ключи base64 → hex).
- Запуск туннеля требует `amneziawg-go.aar` (docs/BUILD.md).

## Userspace-PPP: SSTP, L2TP, PPTP

Полный PPP-стек на Kotlin (`vpn/ppp`), без нативных .aar и без root.

### SSTP (MS-SSTP)
- PPP поверх TLS 1.2/1.3 поверх HTTPS (`SSTP_DUPLEX_POST /sra_{BA195780-…}/`).
- Аутентификация MS-CHAPv2 (взаимная, RFC 2759), опционально PAP.
- **Crypto-binding** (CALL_CONNECTED): Compound MAC = HMAC-SHA1/SHA256 от
  CMK (RFC 3079 GetMasterKey) — связывает PPP-аутентификацию и TLS-сессию.
- Проверка хэша сертификата сервера из ENC_INFO.
- Ссылка: `sstp://user:pass@host:port#name` (параметры `sni`, `allow_insecure`).

### L2TP (RFC 2661, без IPsec)
- Туннель по UDP: SCCRQ→SCCRP→SCCCN, сессия ICRQ→ICRP→ICCN,
  Ns/Nr + ZLB-подтверждения, опциональный tunnel-auth (MD5).
- Внутри — тот же PPP-стек; IP/DNS приходят по IPCP.
- **Без IPsec/ESP**: ESP недоступен в userspace (нужен IKE-демон и CAP_NET_ADMIN).
  Шифрование — только на уровне PPP; для защищённого канала используйте
  SSTP/WireGuard.
- Ссылка: `l2tp://user:pass@host:port#name` (параметр `tunnel_secret`).

### PPTP — честно недоступно
- Данные PPTP идут в **GRE** (IP-протокол 47) — требует raw-сокетов (root).
- Системный стек PPTP удалён из Android 12/13.
- UI сообщает правду вместо молчаливого отказа (`PptpCore`).

### Как PPP вливается в tun
- Туннель поднимается с адресом-заглушкой `172.19.0.1/28`; реальный IP
  приходит по IPCP. `TunBridge` делает симметричный NAT
  (исходящие: src 172.19.0.1 → назначенный IP; входящие: dst → 172.19.0.1)
  с инкрементальной коррекцией чек-сумм IPv4/TCP/UDP (RFC 1624).
- Транспортные сокеты выводятся из-под VPN-маршрутизации через
  `SocketGuard.protect()` — иначе петля маршрутизации.

## Ознакомительные движки (BETA)

### WDTT — WireGuard через TURN-релей ВК
- Нативный `libclient.so` (JNI) + VK-авторизация (OAuth/WebView) →
  TURN-credentials. Внешне трафик выглядит как WebRTC/TURN к облаку ВК.
- До сборки библиотеки ядро честно отказывает (`WdttCore`). См. docs/SERVICES.md.

### olcRTC — TCP поверх WebRTC
- gomobile `olcrtc.aar`: компонент `cnc` устанавливает WebRTC-сессию и
  выставляет локальный SOCKS5; tun2socks (hev-socks5-tunnel) заворачивает в
  него пакеты из tun. См. docs/SERVICES.md.

## Лицензии встраиваемых компонентов
- **sing-box** — GPL-3.0.
- **Xray-core / libXray** — MPL-2.0.
- **wireguard-go / amneziawg-go** — MIT (форк wireguard-go).
- **hev-socks5-tunnel** — MIT (проверьте upstream перед дистрибуцией).
- Из-за GPL-3.0 у sing-box итоговое приложение распространяется под **GPL-3.0**
  (полный список — `THIRD_PARTY_NOTICES.md`).
