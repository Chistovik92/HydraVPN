# Аудит VPN-протоколов HydraVPN 0.8.4

Дата: 2026-11-28. Область: `shared/src/commonMain/kotlin` (`data.model.Protocol`,
`data.subscription.SingBoxConfigBuilder`, `data.subscription.LinkParser`,
`data.subscription.WireGuardParser`, `data.subscription.WireGuardConfigBuilder`,
`servers.*`) + `app/src/native/.../vpn/core` (`SingBoxCore`, `AmneziaWgCore`,
`SocksBridgeCore`, `NativeCoreFactory`, `TrustTunnelCore`). Движки: sing-box
libbox **1.14.5**, amneziawg-go, Xray (`libXray.aar`), Kotlin-PPP, нативные
подпроцессы.

**Метод.** Статический анализ кода + чтение upstream-схем (sing-box 1.14,
TrustTunnel PROTOCOL.md, amneziawg-go). **НЕ проведено:** живое тестирование
против реальных серверов, фаззинг парсеров, сборка нативных ядер, аудит
самих upstream-реализаций (sing-box/Xray/amneziawg-go/TrustTunnel — отдельная
работа). Все выводы помечены уверенностью.

---

## 1. OpenVPN

**В shared нет реализации OpenVPN.** `Protocol`-enum не содержит `OPENVPN`;
ни один `VpnCore` его не обслуживает; в shared нет OpenVPN-парсера `.ovpn`.
Упоминание «OpenVPN» в `docs`, `CHANGELOG`, `MacOSVpnManager` (только комментарий
про utun) и в бинаре sing-box (`libbox.so` содержит строки `openvpn`) не равно
поддержке.

- **sing-box 1.14.5:** OpenVPN добавлен как **endpoint** (`openvpn-client` /
  `openvpn-server`, build-tag `with_openvpn`, DNS-транспорт), а **не** как
  outbound. HydraVPN сейчас строит только `outbounds` + `wireguard`-endpoint;
  `openvpn-*`-endpoint не используется.
- **Совместимость (требование «не ломать существующие протоколы»):** OpenVPN не
  существовало до 0.8.4, поэтому нечего сохранять; добавление его — отдельная
  фича (эндпоинт sing-box или нативный OpenVPN3), в 0.8.4 **не реализовано**.
- **Аудитить нечего:** нет кода. Честный статус — `UNAVAILABLE`/нет протокола.

**Вывод:** при заявлениях «OpenVPN поддерживается» это неточно. На 0.8.4 OpenVPN
отсутствует; backward-compat не нарушена (ничего не сломано).

---

## 2. WireGuard (и AmneziaWG)

Реализации: (a) sing-box **wireguard-endpoint** (`SingBoxConfigBuilder.wireGuardToEndpoints`,
0.8.2-b), (b) `AmneziaWgCore` → `amneziawg-go` `libwg-go.so` + `WireGuardConfigBuilder.buildUapi`.
AmneziaWG = WireGuard + обфускация `Jc/Jmin/Jmax/S1–S4/H1–H4` (1.0/1.5) + `I1–I5`
(2.0), ключи `HeaderProtectionKey/ContentPaddingAddition/RandomTrailers`.
Обычный WireGuard на Android по умолчанию идёт через amneziawg-go (0.8.2-b,
`EngineToggles`), sing-box — запасной.

### Что проверено и ОК
- **Ключи:** приватный/публичный/preshared — base64 Curve25519 32 байта; в логах
  не выводятся (`"uapi — N строк, ключи скрыты"`). PresharedKey опционален.
- **`allowed_ips`:** всегда `0.0.0.0/0` + `::/0` для единственного пира (full-tunnel).
- **`local_address`:** обязателен; нормализуется `wgAddresses` → `/32` IPv4, `/128`
  IPv6; дефолт `172.19.0.2/32`. Комментарий в `AmneziaWgCore`: WG не делает NAT,
  адрес интерфейса обязан совпадать с `Address` пира (иначе нет обратного трафика).
- **`persistent_keepalive_interval`:** пробрасывается (`keepalive>0`); за NAT без
  keepalive маппинг истекает (рекомендовано 15–25 с на сервере/клиенте).
- **fd-гигиена (0.8.x):** Go получает **дубликат** tun (`ParcelFileDescriptor.dup`),
  закрывает сам; при ошибке старта исходный не закрывается дважды (`SIGABRT`-фикс).
  `stop()` → `awgTurnOff`. Документировано `SocketGuard`.
- **`auto_route/strict_route=false`, `auto_detect_interface=true`:** маршрутизацией
  владеет `VpnService.Builder`, нет петли до сервера. `default_domain_resolver=local`
  (1.14 откажется стартовать иначе); домен самого сервера резолвится мимо туннеля.
- **IPv6:** `::/0` в allowed_ips; tun-IPv6 только при `Ipv6Mode.ENABLE`; адреса `/128`.
- **WireGuard→endpoint (1.11+):** вместо устаревшего outbound `wireguard`
  (документированный ~5 КБ/с на Android), перенос в `endpoints`. Тег `proxy` сохранён.

### Находки
| # | Серьёзность | Находка | Статус / рекомендация |
|---|---|---|---|
| WG-1 | Средняя | `pre_shared_key`/`preshared_key` читается из `extra` без проверки длины 32 байта/Base64 — кривой PSK даст невнятную ошибку amneziawg-go/sing-box. | Валидировать Base64+32Б на импорте (`WireGuardParser`); UX, не security. **Не исправлено в 0.8.4.** |
| WG-2 | Низкая | `keepalive` строкой `extra.optString(...).toIntOrNull()`, отрицательное/0 → не ставится. | ОК. |
| WG-3 | Низкая | `multiple peers`/`AllowedIPs` сужение не поддержано — только один пир + full-tunnel. | Осознанно; одиночный сервер. |
| WG-4 | Низкая | При `Engine.AWG` обычный WireGuard тоже идёт в amneziawg-go (он понимает обычные конфиги). | ОК (0.8.2-b). |
| WG-5 | Инфо | `AmneziaWG` beta-обфускация (I1–I5/2.0, HeaderProtectionKey) зависит от версии amneziawg-go; проверяется по исходнику v3. | Держать amneziawg-go актуальным. |

**Крипто:** Noise_IK, Curve25519, ChaCha20-Poly1305, BLAKE2s, HKDF; replay-защита,
key-zeroing в upstream. Cookie/reply-MAC (DoS) — в upstream. Hydra не ослабляет.
**`insecure`-флага у WireGuard нет** (в отличие от TLS-протоколов) — хорошо.

---

## 3. Hysteria2

Реализация: sing-box `hysteria2`-outbound (`SingBoxConfigBuilder`, `Engine.SINGBOX`;
Xray — только VLESS/VMess/Trojan/SS, не Hysteria2). QUIC (RFC 9000), `tls` обязателен,
`utls` **отключён** для QUIC (`quic=true`), опциональная обфускация `salamander`.

### Что проверено и ОК
- **TLS:** `tls.enabled=true`, `server_name=sni||address`, `alpn`, `reality` (если
  `security==reality` + `reality_pbk`); `utls` не ставится (sing-box не поддерживает
  uTLS для QUIC Hysteria2/TUIC — комментарий 0.8.x).
- **Auth:** `password` = `uuidOrPassword`.
- **Salamander:** `obfs.type=salamander`, `obfs.password` (из `extra.obfs_password`),
  включается `extra.has("obfs")`. Без пароля salamander-обфускация бессмысленна
  (сервер требует одинаковый).
- **QUIC idle:** sing-box дефолты; `congestion_control` для Hysteria2 задаёт сервер.

### Находки
| # | Серьёзность | Находка | Статус / рекомендация |
|---|---|---|---|
| HY2-1 | **Средняя (security)** | `insecure` (`extra.optBoolean("insecure")`, исторически `allowInsecure`/`allow_insecure`) отключает проверку сертификата сервера → MITM. Флаг молча пробрасывается из подписки. | Корректно как *опция*, но: по умолчанию ВЫКЛ; показывать в UI предупреждение; не включать из недоверенной подписки без явного согласия. В 0.8.4 оставлено как upstream-совместимо. |
| HY2-2 | Низкая | `alpn` переопределяется пользователем; Hysteria2 ожидает `h3`. | Если `alpn` непустой и не `h3` — соединение упадёт; валидировать/предупреждать. |
| HY2-3 | Низкая | `salamander` без `obfs_password` → obfs включён с пустым паролем. | Ставить obfs только при непустом пароле. Не критично. |
| HY2-4 | Инфо | `server_name` пустой → `p.address`. Если SNI должен отличаться от IP сервера — указать `sni`. | ОК. |

**Крипто:** QUIC TLS 1.3, сертификат сервера проверяется (кроме `insecure`).
Hysteria2 не имеет собственного слоя поверх TLS помимо salamander (обфускация, не
шифрование). Core Hysteria2 в upstream sing-box — отдельный аудит.

---

## 4. TrustTunnel (добавлено в 0.8.4)

`Protocol.TRUSTTUNNEL` + `Engine.TRUSTTUNNEL`; `TrustTunnelCore : SocksBridgeCore`
→ нативный `trusttunnel_client` (`libtrusttunnel.so`, TrustTunnelClient Apache-2.0,
`scripts/build-trusttunnel.sh`) → локальный SOCKS5 `127.0.0.1:10811` → sing-box
(`buildXrayBridge`). `TrustTunnelArgs` генерирует TOML `[endpoint]`+`[listener.socks]`;
`killswitch=false`, системный TUN у клиента выключен. Импорт `tt://`/`trusttunnel://`
(`TrustTunnelLink`, совместимое подмножество + `[endpoint]` TOML; канонический
декодер `tt://` — нативный `trusttunnel_deeplink_decode`). Установка сервера —
`servers.TrustTunnelServer` (`trusttunnel_endpoint`, upstream `install.sh`).
**См. `docs/TRUSTTUNNEL.md`.**

### Безопасность (по upstream PROTOCOL.md)
- TLS 1.2+, SNI обязателен, `proxy-authorization: Basic base64(user:pass)`.
- **Проверка сертификата сервера обязательна;** `skip_verification` по умолчанию
  ВЫКЛ; `certificate` PEM — отдельный временный файл `0600`, удаляется в `stop`.
- `password`, `client_random_auth_key`, TOML-конфиг, `client_random` — секреты:
  файл `0600`, не в журнал (`SocksBridgeCore` + `Redactor`), удаляются.
- `post_quantum_group_enabled=true`, `anti_dpi`, `tls_profile` (uTLS-мимикрия),
  `client_random[/mask]` + `client_random_auth_key` (HKDF+AES), `custom_sni`,
  `sni|remote_id`, `hostname`. `upstream_protocol` http2/http3.
- `persistent_keepalive`-аналог `has_ipv6`, DNS-апстримы через VPN опционально.

### Честно (не проверено здесь)
- **Нативный `libtrusttunnel.so` не поставляется** в репозитории; собирается
  `scripts/build-trusttunnel.sh` (NDK29/CMake/Conan/Go, upstream Android adapter).
  Без него `TrustTunnelCore.start` падает с явной ошибкой «нет libtrusttunnel.so».
  CI-сборка не требует его (проверка в рантайме).
- **Официальная `tt://`-схема** декодируется upstream `trusttunnel_deeplink`;
  наш `TrustTunnelLink.parse` — совместимое подмножество + `[endpoint]`-TOML,
  не полный порт deeplink-ffi. Битовую совместимость нужно подтвердить тестом
  против `trusttunnel_client --export-deeplink`.
- **Живого эндпоинта нет** — сквозной тест TCP/UDP/ICMP, QUIC h3, анти-DPI и
  серверный провижининг (`TrustTunnelServer`) не прогонялись.
- Сервер `trusttunnel_endpoint` совместим с Linux/macOS; мастер
  `--setup-wizard`, Let's Encrypt, systemd — вручную/отдельными шагами.

---

## 5. Общие наблюдения (все протоколы)

- **`insecure`-семейство флагов** (`insecure`, `allowInsecure`, `allow_insecure`,
  `skip_verification`, `allow_insecure`→`insecure`): по умолчанию ВЫКЛ, но из
  подписки пробрасывается. Единая точка: `SingBoxConfigBuilder.tlsBlock` (`extra.insecure`)
  + парсеры. **Рекомендация:** явный opt-in в UI + пометка «MITM-риск».
- **DNS-утечки:** `hijack-dns`, `dns.final=remote` через прокси, `bootstrap` DoH по
  IP через прокси, `default_domain_resolver=local`, домен сервера мимо туннеля,
  `ip_is_private→direct`. Рассмотрено в `SingBoxConfigBuilder`. **Не проверено
  в рантайме** (нет захвата трафика).
- **Маршрутизация:** `auto_route/strict_route=false`, `auto_detect_interface`,
  `addDisallowedApplication`, `SocketGuard` против loop; `SocksBridgeCore`
  (olcRTC/OpenFlux/TrustTunnel) — свой UID процесса, app исключено из tun.
  **Не проверено** на устройстве.
- **Секреты:** `Redactor`, `secretFile 0600`, `CredentialVault`, `SocksBridgeCore`
  чистит временные секрет-файлы. `SingBoxCore` пишет конфиг sing-box — проверить,
  что он не светится в логах `log.level=warn`. **Частично.**
- **Backward-compat:** новый `Protocol.TRUSTTUNNEL` + `Engine.TRUSTTUNNEL`
  добавлены additive; `fromScheme`/`fromId` расширены; старые enum-значения и
  `protocolId` не тронуты; `when`-исчерпаемость сохранена. Старые профили и
  подписки не затронуты.

## 6. Не проведено (честно)

- Живые серверы/сертификаты (вкл. Let's Encrypt), реальные ключи.
- Сборка/аудит upstream: sing-box, Xray, amneziawg-go, TrustTunnelClient,
  `trusttunnel_endpoint`, PPP-стек.
- Сетевые тесты: утечки DNS/IPv6, kill-switch, reconnect, roaming, split-tunnel,
  MTU, анти-DPI, QUIC на lossy-линке, IPv6-only.
- Фаззинг `LinkParser`/`WireGuardParser`/`TrustTunnelLink`, TOML-инъекции.
- OpenVPN endpoint sing-box 1.14 (`with_openvpn`) — не интегрирован.

**Итог 0.8.4:** WireGuard/AmneziaWG и Hysteria2 — зрелые, аккуратно обёрнутые
реализации без критичных находок в слое Hydra; находки WG-1, HY2-1..3 — уровня
UX/валидации. OpenVPN отсутствует (не сломано). TrustTunnel добавлен с корректной
моделью безопасности, но сквозная работоспособность зависит от нативного
`libtrusttunnel.so` + живого эндпоинта и **требует подтверждения** (см. §4).
