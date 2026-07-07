# Changelog

## [0.1.0] — каркас
### Добавлено
- UI на Jetpack Compose по макету (Главная / Серверы / Логи / Настройки).
- Модель данных, Room, репозиторий серверов и подписок.
- Парсер ссылок: vless, vmess, trojan, ss, hysteria2, tuic; импорт подписок (base64/plain).
- Генератор конфигов sing-box и Xray.
- `ShadowLinkVpnService` (tun) + абстракция ядра `VpnCore`.
- Flavors `stub` (симуляция) и `native` (libbox/libXray).
- Ветка IKEv2/IPsec (`Ikev2Connector`) как замена L2TP.
- Документация: архитектура, сборка, протоколы, панели.

### Известные ограничения
- Реальные туннели требуют сборки `libbox.aar` / `libXray.aar`.
- `PlatformInterface` sing-box реализован частично (нужна доводка под версию libbox).
- Xray требует tun2socks-моста.
- Статистика трафика в native-сборке — каркас (нужен CommandClient).
