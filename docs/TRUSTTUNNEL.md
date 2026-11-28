# TrustTunnel в HydraVPN 0.8.4

TrustTunnel — открытый VPN-протокол **AdGuard** (Apache-2.0), первоначально из
AdGuard VPN, теперь отдельный проект `TrustTunnel/TrustTunnel`. Трафик TCP, UDP и
ICMP туннелируется **внутри обычного HTTPS**: **HTTP/1.1, HTTP/2 (`h2`, TCP+TLS)
и HTTP/3 (`h3`, QUIC)**. Мимикрия под обычный веб-трафик мешает DPI/троттлингу;
TLS 1.2+, SNI обязательны, проверка сертификата сервера обязательна.
Авторизация — `proxy-authorization: Basic base64(username:password)`.
Спецификация: `TrustTunnel/TrustTunnel` → `PROTOCOL.md`.

Протокол добавлен в **0.8.4** как `Protocol.TRUSTTUNNEL` / `Engine.TRUSTTUNNEL`,
additive — существующие протоколы не затронуты.

- Клиент (репозиторий пользователя в сообщении): **`https://github.com/TrustTunnel/TrustTunnelClient`**
  (C++ libs + CLI `trusttunnel_client`, + `trusttunnel_deeplink_decode`, Android/Apple adapters; Apache-2.0).
- Сервер: **`https://github.com/TrustTunnel/TrustTunnel`** (`trusttunnel_endpoint`; Linux/macOS; Apache-2.0).

## Клиент в HydraVPN

`app/src/native/.../TrustTunnelCore.kt` (`SocksBridgeCore`):

```
trusttunnel_client --config <tmp/trusttunnel.client.toml>   # TOML; 0600; удаляется в stop()
        → SOCKS5 127.0.0.1:10811 (killswitch off, без [listener.tun])
        → sing-box buildXrayBridge → tun (маршрутизация/DNS/split — у Hydra)
```

- **Нативный бинарь `libtrusttunnel.so`** в `app/libs/trusttunnel/<abi>/`
  (arm64-v8a, armeabi-v7a, x86_64, x86), собирается `scripts/build-trusttunnel.sh`
  (Python 3.13+, CMake 3.24+, Conan 2.0.5+, Go 1.18.3+, Ninja, Android SDK 35+,
  **NDK 29.0.14206865+**, upstream `platform/android`). В репозитории **не**
  поставляется; **CI-сборка его не требует** (проверка `exe.exists()` в рантайме).
  Без него соединение даёт явную ошибку «в сборке нет libtrusttunnel.so».
- **Все платформы:** `Engine.TRUSTTUNNEL` в shared KMP; на Android (`native`
  flavor) — `TrustTunnelCore`. Desktop/iOS используют тот же shared-парсер
  (`TrustTunnelLink`, `TrustTunnelArgs`) + нативный `trusttunnel_client`
  (upstream поддерживает Linux/macOS/Windows); `stub`-flavor — заглушка.

### Конфигурация `[endpoint]` (TOML, генерируется `TrustTunnelArgs.buildToml`)
`hostname` (TLS; `sni|remote_id` → `hostname`+`custom_sni`), `addresses` (`IP:port`,
IPv6 `[..]:port`, несколько), `username`, `password`, `has_ipv6`, `client_random`,
`client_random_auth_key`, `skip_verification` (**дефолт false**), `certificate`
(PEM, временный файл `0600`), `dns_upstreams`, `upstream_protocol` `http2|http3`,
`tls_profile`, `anti_dpi`, `name`; `[listener.socks] address=127.0.0.1:10811`;
`killswitch_enabled=false`; `vpn_mode=general`; `post_quantum_group_enabled=true`.
Схема сверена с upstream `trusttunnel_client.toml` (`trusttunnel/README.md`).

### Импорт ссылок
- `trusttunnel://user:pass@host:port?sni=..&hostname=..&custom_sni=..&upstream=http2|http3&insecure=1&tls_profile=chrome&has_ipv6=1&client_random=..&client_random_auth_key=..&anti_dpi=1&name=..#name`
- `tt://...` (то же)
- `tt://` + `[endpoint]` TOML (вывод `trusttunnel_deeplink_decode`)
- официальный `tt://` deep-link `trusttunnel_client --export-deeplink`:
  **канонический декодер — нативный `trusttunnel_deeplink_decode`** (deeplink-ffi);
  наш `TrustTunnelLink.parse` — совместимое подмножество. Надёжнее: сгенерировать
  `trusttunnel_client.toml` (`--setup-wizard --mode non-interactive --endpoint_config ...`).
- Ручной ввод: `ManualLink` протокол `trusttunnel`.
- `Protocol.fromScheme("trusttunnel"|"tt")`, `fromId("trusttunnel")`.

## Сервер (разворачивание) — `servers.TrustTunnelServer`

Linux + systemd + root. `trusttunnel_endpoint`, `/opt/trusttunnel`, `vpn.toml`.

```bash
# 1) установка (upstream)
curl -fsSL https://raw.githubusercontent.com/TrustTunnel/TrustTunnel/refs/heads/master/scripts/install.sh | sh -s - -o /opt/trusttunnel
# 2) настройка (мастер; TLS/сертификат, пользователи, порт)
/opt/trusttunnel/trusttunnel_endpoint --setup-wizard --settings /opt/trusttunnel/vpn.toml
# 3) запуск
systemctl enable --now trusttunnel        # или: /opt/trusttunnel/trusttunnel_endpoint --config /opt/trusttunnel/vpn.toml
# 4) экспорт клиентской конфигурации (tt:// / [endpoint])
... trusttunnel_endpoint ... --export ...
```

В коде: `TrustTunnelServer.installSteps(port, firewall)` → `List<Step>` для
`Installer` (SSH); `openPortStep`; открыть порт эндпоинта (дефолт 443);
секреты пользователей заводятся отдельно и не пишутся в журнал (`Redactor`).
**Честно:** полностью неинтерактивный провижининг (Let's Encrypt, пользователи,
сервис) в upstream делается мастером; `TrustTunnelServer` даёт шаги установки +
подсказки, но **не прогонялся** против живого сервера; точные non-interactive
флаги мастера сверяйте с upstream `TrustTunnel/TrustTunnel`.

## Совместимость и ограничения

- Additive: старые `Protocol`/`Engine`, `protocolId`, `.conf`, подписки, ссылки
  не изменены; `EngineToggles` расширен `Engine.TRUSTTUNNEL -> null` (выбор
  напрямую по протоколу).
- sing-box **не имеет** `trusttunnel`-outbound (проверено 1.14.5 libbox) — отсюда
  отдельный нативный движок + SOCKS5-мост, а не `SingBoxConfigBuilder.outboundFor`.
- `upstream_protocol` http2/http3 (`auto`→http2 как upstream deep-link).
- `skip_verification` и `insecure` — явный opt-in, по умолчанию **ВЫКЛ** (MITM-риск).
- Точная битовая схема официального `tt://`, сборка `libtrusttunnel.so` и живой
  эндпоинт в этой сессии **не проверялись** (нет NDK-набора/сервера) — см.
  `docs/PROTOCOL-AUDIT-0.8.4.md` §4/§6.

## Лицензии
HydraVPN (см. `LICENSE`); TrustTunnel + TrustTunnelClient — **Apache-2.0**;
добавить attribution в `THIRD_PARTY_NOTICES.md`. sing-box libbox 1.14.5 — GPL-3.0
(уже учтён в проекте).
