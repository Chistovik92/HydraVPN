# ShadowLink

**Мультипротокольный VPN-клиент для Android** на Jetpack Compose.
Поддерживает proxy-протоколы ядра **Xray** и **sing-box** (VLESS, VMess, Trojan,
Shadowsocks, Hysteria2, TUIC), а также семейство **IPsec** (см. важное замечание
по L2TP ниже). Импортирует подписки из панелей **x-ui, 3x-ui, PasarGuard, Remnawave**.

> ⚠️ **Статус: ранний каркас (0.1.0).** Полностью реализованы UI, слой данных,
> парсинг ссылок/подписок и генерация конфигов. Реальные туннели требуют
> сборки нативных ядер (`libbox.aar`, `libXray.aar`) — см. [docs/BUILD.md](docs/BUILD.md).
> Flavor `stub` собирается и запускается без ядер (симуляция соединения).

---

## Возможности

- 🎛 **Единый клиент** для нескольких семейств протоколов.
- 📥 **Импорт подписок** (base64 или список ссылок) и одиночных ссылок
  `vless:// vmess:// trojan:// ss:// hysteria2:// tuic://`, в т.ч. по deep-link.
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
| L2TP/IPsec | — | **недоступно на Android 13+** → используется IKEv2 |
| IKEv2/IPsec | системный (VpnManager) | каркас, API 33+ |

> **Про L2TP.** Android 12 убрал L2TP из системного UI, Android 13 удалил стек
> целиком (он строился на устаревшем IKEv1). Реализовать L2TP на уровне
> приложения нереалистично. Пункт «L2TP/IPsec» в интерфейсе сохранён (как в
> макете), но фактически маршрутизируется в системный **IKEv2/IPsec**.
> Подробности — [docs/PROTOCOLS.md](docs/PROTOCOLS.md).

## Быстрый старт

```bash
git clone https://github.com/<you>/ShadowLink.git
cd ShadowLink
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
             ShadowLinkVpnService (tun)  →  VpnCore
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
