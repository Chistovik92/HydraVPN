# Протоколы

## Семейство proxy (Xray / sing-box)

Клиент строит конфиг из `ServerProfile` и запускает нативное ядро поверх tun.

### VLESS
- Транспорт: `tcp` / `ws` / `grpc` / `http`.
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
  все шесть протоколов выше — рекомендуемый движок по умолчанию.
- **Xray-core** сам tun **не** обслуживает. Схема: `tun → tun2socks → socks-inbound Xray`.
  Xray поднимается с локальным socks5 (напр. `127.0.0.1:10808`), а tun2socks
  (`hev-socks5-tunnel`) перекладывает пакеты из tun-fd в этот socks. Xray оставлен
  как альтернатива для сценариев, где важна именно его реализация XTLS.

## Семейство IPsec

### L2TP/IPsec — почему его нет
- **Android 12 (2021)** убрал L2TP из системного VPN-UI.
- **Android 13 (2022)** удалил legacy-стек L2TP полностью — он строился на
  устаревшем и небезопасном **IKEv1**.
- Реализовать L2TP на уровне приложения означало бы тащить userspace-стек
  PPP + L2TP + IPsec ESP — непрактично и небезопасно.

Отраслевой тренд тот же: Microsoft объявил PPTP/L2TP устаревшими на стороне
Windows Server (2024), Apple в macOS 26 / iOS 26 (2025) удалил старые
криптопримитивы, ломая часть L2TP/IPsec-подключений. Рекомендованная замена
везде — **IKEv2**.

### IKEv2/IPsec — как это делается на Android
- С Android 12/13 приложение может провижионить IKEv2-профиль через
  `VpnManager` + `Ikev2VpnProfile` (`Ikev2Connector` в коде, требует API 33+).
- Это **системный** VPN, отдельный от нашего tun-сервиса. Аутентификация:
  PSK (`ServerProfile.uuidOrPassword`) или username/password (`extra`).
- Пункт «L2TP/IPsec (PSK)» в UI сохранён ради соответствия макету, но фактически
  ведёт в IKEv2. Если нужен именно L2TP — поднимите на сервере IKEv2 (напр.
  strongSwan) как современную замену.

## Лицензии встраиваемых компонентов
- **sing-box** — GPL-3.0.
- **Xray-core / libXray** — MPL-2.0.
- Из-за GPL-3.0 у sing-box итоговое приложение распространяется под **GPL-3.0**.
