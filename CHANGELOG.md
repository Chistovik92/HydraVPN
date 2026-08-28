# Changelog

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
