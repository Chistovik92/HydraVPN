# Hydra

**Мультипротокольный VPN-клиент для Android** на Jetpack Compose.
Поддерживает proxy-протоколы ядра **Xray** и **sing-box** (VLESS, VMess, Trojan,
Shadowsocks, Hysteria2, TUIC, WireGuard), **AmneziaWG** (обфусцированный WireGuard),
а также **SSTP** и **L2TP**, реализованные целиком на Kotlin (userspace-PPP).
Импортирует подписки из панелей **x-ui, 3x-ui, PasarGuard, Remnawave**.

> ⚠️ **Статус: 0.3.0.** Полностью реализованы UI, слой данных, парсинг
> ссылок/подписок (.conf WireGuard/AmneziaWG в т.ч.), генерация конфигов и
> userspace-PPP-стек (SSTP/L2TP — без нативных зависимостей).
> Proxy-туннели требуют сборки нативных ядер (`libbox.aar`, `libXray.aar`,
> `amneziawg-go.aar`) — см. [docs/BUILD.md](docs/BUILD.md).
> Flavor `stub` собирается и запускается без ядер (симуляция соединения).

---

## Возможности

- 🎛 **Единый клиент** для нескольких семейств протоколов.
- 📥 **Импорт подписок** (base64 или список ссылок) и одиночных ссылок
  `vless:// vmess:// trojan:// ss:// hysteria2:// tuic:// wireguard:// awg://`
  (или `.conf` WireGuard/AmneziaWG целиком), в т.ч. по deep-link.
- 🧩 **Совместимость с панелями** x-ui / 3x-ui / PasarGuard / Remnawave
  (см. [docs/PANELS.md](docs/PANELS.md)).
- 🌗 Тёмный интерфейс на Compose (дизайн — из макета проекта).
- 🗂 Хранение серверов и подписок в Room; авто-обновление подписок.
- 📝 Экран логов, статистика трафика, foreground-уведомление.

## Поддержка протоколов

| Протокол | Движок | Статус |
|---|---|---|
| VLESS (+ REALITY/XTLS Vision) | sing-box / Xray | конфиг готов, нужен `.aar` |
| VMess | sing-box / Xray | конфиг готов, нужен `.aar` |
| Trojan | sing-box | конфиг готов, нужен `.aar` |
| Shadowsocks | sing-box | конфиг готов, нужен `.aar` |
| Hysteria2 | sing-box | конфиг готов, нужен `.aar` |
| TUIC v5 | sing-box | конфиг готов, нужен `.aar` |
| WireGuard | sing-box | конфиг готов, нужен `.aar` |
| AmneziaWG 1.0/1.5/2.0 | amneziawg-go | .conf/uapi готовы, нужен `.aar` |
| SSTP | userspace (Kotlin) | реализован, нужен тест на устройстве |
| L2TP (без IPsec) | userspace (Kotlin) | реализован, нужен тест на устройстве |
| PPTP | — | **недоступно**: GRE требует root, стек удалён из Android 12/13 |

> **Про L2TP.** Системный стек L2TP удалён из Android (12 — из UI, 13 — целиком,
> строился на устаревшем IKEv1), а L2TP/IPsec недоступен в userspace (ESP).
> Поэтому Hydra реализует «чистый» L2TP (RFC 2661) на Kotlin: туннель по UDP,
> аутентификация и согласование IP — на PPP (MS-CHAPv2/PAP + IPCP).
> Шифрование канала — только на уровне PPP; если нужен защищённый транспорт,
> используйте SSTP (TLS) или WireGuard/AmneziaWG. Подробности —
> [docs/PROTOCOLS.md](docs/PROTOCOLS.md).

## Быстрый старт

```bash
git clone https://github.com/<you>/Hydra.git
cd Hydra
# Открыть в Android Studio (Giraffe+), либо:
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleStubDebug      # сборка без нативных ядер (симуляция)
```

Для реальных туннелей соберите ядра и положите `.aar` в `app/libs/`, затем:

```bash
./gradlew :app:assembleNativeDebug
```

Полная инструкция по сборке ядер — [docs/BUILD.md](docs/BUILD.md).

## Архитектура (кратко)

```
UI (Compose)  →  MainViewModel  →  ServerRepository (Room + подписки)
                       │
                       ▼
             HydraVpnService (tun)  →  VpnCore
                                              ├─ SingBoxCore  (libbox.aar)
                                              ├─ XrayCore     (libXray.aar + tun2socks)
                                              └─ NoopCore     (stub)
             Ikev2Connector (VpnManager)  ← ветка L2TP/IPsec
```

Подробно — [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Лицензия

GPL-3.0. Проект встраивает компоненты sing-box (GPL-3.0) и Xray-core (MPL-2.0);
их лицензии сохраняются. См. [LICENSE](LICENSE) и [docs/PROTOCOLS.md](docs/PROTOCOLS.md).

## Дисклеймер

Инструмент для доступа к **собственным** VPN-серверам и обхода сетевых
ограничений в законных целях. Соблюдайте законодательство своей юрисдикции.
