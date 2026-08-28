# Сервисы и интеграции

Описания VPN-сервисов, с которыми умеет работать Hydra, и их интеграция.
Про форматы ссылок и панели подписок — [PANELS.md](PANELS.md), про
протоколы — [PROTOCOLS.md](PROTOCOLS.md).

## Собственные серверы (self-hosted)

### Xray / sing-box панели
- x-ui, 3x-ui, PasarGuard, Remnawave и любые панели, выдающие стандартные
  ссылки (`vless://`, `vmess://`, …) или base64-подписки. См. [PANELS.md](PANELS.md).
- Движок по умолчанию — sing-box; Xray — опционально.

### WireGuard / AmneziaWG
- Импорт `.conf` от любого WG-сервиса (в т.ч. генераторы конфигов AmneziaVPN).
- AmneziaWG 1.0/1.5 (Jc/Jmin/Jmax/S1/S2/H1–H4) и 2.0 (I1–I5) распознаются
  автоматически; движок — amneziawg-go.

### SoftEther / Mikrotik / RRAS (SSTP, L2TP)
- **SSTP**: Windows RRAS, SoftEther VPN Server, Mikrotik (включить SSTP-server).
  Аутентификация MS-CHAPv2 (рекомендуется — нужен для crypto-binding) или PAP.
- **L2TP**: SoftEther, Mikrotik, accel-ppp — «чистый» L2TP без IPsec
  (см. [PROTOCOLS.md](PROTOCOLS.md) про ограничения шифрования).
- Статус: реализовано на Kotlin, требует on-device проверки против реального
  сервера (см. HANDOFF, открытые задачи).

## Ознакомительные сервисы (BETA)

### WDTT — WireGuard через TURN-релей ВК
- **Что это:** обфусцированный канал поверх TURN-серверов VK Cloud. Внешне
  трафик неотличим от обычного WebRTC-медиа-ретранслята, что помогает в
  сетях с DPI-блокировками VPN-протоколов.
- **Как устроено:** нативный клиент `libclient.so` — WG-сессия инкапсулируется
  в TURN-allocate/relay; авторизация в VK (OAuth 2.0 через WebView) выдаёт
  TURN-credentials (user/credential в `extra` профиля).
- **Статус:** BETA. Требуется сборка `libclient.so` (docs/BUILD.md, п. 2.4),
  JNI-мост в `WdttCore` и проверка лицензии upstream перед включением
  бинарника в дистрибутив.
- В UI помечен плашкой BETA (`Protocol.beta = true` → `BetaBadge`).

### olcRTC — TCP поверх WebRTC
- **Что это:** транспорт, маскирующий VPN-потоки под WebRTC DataChannel
  (шум DPI-фингерпринтинга), сигналинг — через сервер `cnc`.
- **Как устроено:** gomobile-биндинг `olcrtc.aar` поднимает WebRTC-сессию и
  локальный SOCKS5; tun2socks (hev-socks5-tunnel) заворачивает в него пакеты
  из tun (схема как у XrayCore).
- **Статус:** BETA. Требуется сборка `olcrtc.aar` + tun2socks
  (docs/BUILD.md, п. 2.5), мост в `OlcRtcCore`.
- В UI помечен плашкой BETA.

## Честные ограничения (для поддержки)

| Протокол | Ограничение | Что предлагать пользователю |
|---|---|---|
| PPTP | GRE требует root; стек удалён из Android 12/13 | SSTP / L2TP / WireGuard |
| L2TP+IPsec | ESP недоступен в userspace | SSTP (TLS) или AWG |
| SSTP+PAP | Нет crypto-binding (PAP не даёт CMK) | MS-CHAPv2 |
| WDTT/olcRTC | Нет .aar/.so в дистрибутиве | Подождать сборку или собрать самим |
