# Документация Hydra — с чего начать

| Вопрос | Где ответ |
|---|---|
| Что это и как скачать | [README](../README.md) |
| Что нового в версии (для пользователя) | [CHANGELOG](../CHANGELOG.md) — от новых к старым |
| Все версии по порядку: дата, суть, ссылка на заметку | [versions/README.md](versions/README.md) |
| Подробности одной версии: что сломалось, что сделано, как проверено | `versions/<номер>.md`, например [0.8.0-rc4](versions/0.8.0-rc4.md) |
| Метки, коммиты и журнал решений владельца | [HISTORY.md](HISTORY.md) |
| Что впереди, правила выпуска и схема версий | [ROADMAP.md](ROADMAP.md) |
| Состояние проекта для разработчика, карта кода, честные оговорки | [HANDOFF.md](HANDOFF.md) |
| Свой сервер — инструкция для пользователя | [SERVERS.md](SERVERS.md) |
| Протоколы и их возможности на платформах | [PROTOCOLS.md](PROTOCOLS.md), [SERVICES.md](SERVICES.md) |
| Маршрутизация, обход DPI, Telegram по WebSocket | [ROUTING.md](ROUTING.md) |
| Сборка: ядра, библиотеки, релиз | [BUILD.md](BUILD.md), [`app/libs/README.md`](../app/libs/README.md) |
| Устройство кода и платформ | [ARCHITECTURE.md](ARCHITECTURE.md), [MULTIPLATFORM.md](MULTIPLATFORM.md) |
| Безопасность | [SECURITY.md](SECURITY.md) |
| Панели (3x-ui и др.) | [PANELS.md](PANELS.md) |
| Роутеры (HydraVPN for Router) | [HANDOFF_ROUTERS.md](HANDOFF_ROUTERS.md) |
| Другие проекты экосистемы (olcRTC, OpenFlux, ByeDPI…) | [ECOSYSTEM.md](ECOSYSTEM.md) |
| iOS: тестирование | [IOS_TESTING.md](IOS_TESTING.md) |
| Распространение (магазины) | [DISTRIBUTION.md](DISTRIBUTION.md) |
| Как вносить изменения | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Старое | [LEGACY.md](LEGACY.md), [archive/](archive/) — прежние дорожные карты (до rc3, ветки 0.8.1-a, ветки kaspersky) прежние тексты заметки 0.8.1 и инструкция «Свой сервер» альфы дословно |

Правило: при каждом выпуске обновляются CHANGELOG, `HISTORY.md` (строка версии и решения владельца), `versions/<номер>.md`
(для заметных версий), оглавление `versions/README.md` (`python scripts/docs-versions-index.py .`) и раздел «Сейчас» в `ROADMAP.md`.
