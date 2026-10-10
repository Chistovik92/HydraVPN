# HANDOFF — управление роутером из Hydra (Фаза 13)

Состояние на 10.10.2026. Роутерная часть — отдельный репозиторий
[HydraVPNforRouters](https://github.com/Chistovik92/HydraVPNforRouters) (Go); последний релиз — **v1.2.5** (09.10.2026),
клиент Hydra сверен с ним (раздел «Что проверено»). Код клиента давно в `master` (0.7.0–0.7.2); ветки `claude/routers-screen`
и `claude/roadmap-radar-integration-*` больше не нужны, прежний список «что сломано» (сборка Android, iOS, десктоп) закрыт.

## Что сделано

| Что | Где | Статус |
|---|---|---|
| Клиент API роутера `/api/v1/*`, разбор ссылки сопряжения, закрепление отпечатка TLS | `shared/src/commonMain/kotlin/ru/gidravpn/hydra/router/RouterClient.kt` | в `master`; живой тест против 1.2.5 пройден |
| Общая логика (опрос, команды, очередь) | `shared/.../router/RouterManager.kt` | в `master` |
| «Роутеры» — ПК (Compose) | `desktop/.../ui/RoutersScreen.kt`, `RouterManage.kt`, `AppController.routers`, `Store.routers` | в `master`; управление разделами и подключение роутера к боту — только здесь |
| «Роутеры» — Android | `app/.../ui/screens/RoutersScreen.kt`, `RoutersViewModel.kt`, `data/repository/RouterRepository.kt`, `res/values*/strings_routers.xml`; пакет `router` берётся из `:shared` задачей `syncSharedRouter` | в `master`; разделы — только просмотр |
| «Роутеры» — iOS | `ios/Hydra/RoutersView.swift`, `RouterModel.swift`, `ios/Packages/HydraKit/.../Router.swift`, `RouterLinkTests.swift`; ссылка `hydravpn-router://` обрабатывается в `HydraApp.onOpenURL` | в `master`; собирается и тестируется только в macOS-CI, на устройстве не проверялся |
| Аккаунт бота «Радар» на самом роутере (вход по коду, синхронизация подписок) | `RouterClient.radar*`, вкладка «Роутеры» на ПК; на роутере — с 1.2.3 | в `master`; проверено вживую на заглушке бота (`RouterLiveTest`) |
| Фаза 13 в дорожной карте приведена к реальному контракту | `docs/ROADMAP.md` | актуально по 1.2.5 |

Возможности экрана «Роутеры»: сопряжение по ссылке `hydravpn-router://host:port?token=…&tls=1&fp=<sha256>` или адрес + токен (на
телефонах ещё QR), выбор роутера, состояние (онлайн, версия, служба, аптайм, последняя ошибка), узлы (выбор в группе, замер задержки),
разделы, подписки (добавить, обновить сейчас, удалить, **отправить подписку из Hydra на роутер**), журнал, диагностика, «Перечитать
конфиг», «Перезапустить службу» (с подтверждением). Опрос раз в 10 с, пока вкладка открыта. На ПК дополнительно: разделы (включить,
править, добавить, удалить), подключение роутера к боту «Радар» (код → подписки), диагностика `check/{name}`. Раздел с личными
ссылками роутер отдаёт замаскированными (`********`), поэтому приложение его не правит (запись затёрла бы ключи) — такой раздел
правят в веб-интерфейсе роутера.

## Совместимость с версиями роутера

| Версия роутера | Что важно клиенту |
|---|---|
| 1.2.0 | базовый контракт `/api/v1/*`, `selftest`, хук Entware для KeeneticOS |
| 1.2.1 | **неверные токены только замедляются** (до 5 с, не более 4 ожидающих), верный токен не блокируется (раньше 429 блокировал и верный); `?token=` принимается **только** на `/logs/stream`; `stop` убивает дочерний sing-box; запись конфига правит только нужный элемент (комментарии и порядок ключей сохраняются); `status`/`config` отвечают без ожидания блокировки (`"busy": true`); изменения отвечают за 25 с, дольше — `202 applying`; `api_token` короче 16 знаков отвергается |
| 1.2.2 | отладочные пре-релизы `vX.Y.Z-debug.N` (клиент их не предлагает) |
| 1.2.3 | **аккаунт «Радар» на роутере**: `GET/POST/DELETE /api/v1/radar…` (бот 5.9.1) — клиент использует |
| 1.2.4 | **самообновление из веб-интерфейса**: `GET /api/v1/update`, `POST /api/v1/update/check`, `POST /api/v1/update` — клиент Hydra пока не вызывает |
| 1.2.5 | типы доменных правил (`namespace:`, `full:`/`exact:`, `wildcard:`, `regexp:`, `keyword:`) в разделах и `rules[]`; `domainmap`, `genconfig`, Keenetic через `ndmc` (`POST /api/v1/domainmap`, `POST /api/v1/genconfig`, `GET /api/v1/keenetic`, `POST /api/v1/keenetic/proxy`); файлы релиза по именам ОС: `hydravpn-router-<версия>-<openwrt|keeneticos|routeros>-<arch>` (до 1.2.5 — `-linux-<arch>`); `.ipk` для Entware KeeneticOS; `install.rsc` для RouterOS; в `status` появился блок `update` |

Эндпоинты `/api/v1/*`, **которые использует клиент:** `version`, `status`, `sections` (GET/POST, PUT/DELETE `{name}`), `subscriptions`
(GET/POST, `{index}/refresh`, DELETE), `nodes` (+`select`, `test`), `logs`, `reload`, `restart`, `check/{name}`, `radar`
(+`link`, `sync`, DELETE). **Не используются пока:** `config`, `servers`, `logs/stream`, `update*`, `domainmap`, `genconfig`,
`keenetic*` (см. «Как продолжить»). Раздел, пришедший с роутера, заменяется целиком по сырому JSON, поэтому новые поля (в т. ч. `domains`
и `domain_regex` из 1.2.5) приложение **не теряет**, хотя пока и не показывает.

## Что проверено

10.10.2026, роутер `hydravpn-router-1.2.5-windows-amd64.exe` (SHA-256 сверен с `checksums.txt` релиза), настоящий sing-box 1.12.25 из
сборки Hydra, `settings.api_listen: 127.0.0.1:18088`:

- `RouterLiveTest` (ПК, `HYDRA_ROUTER_URL`/`HYDRA_ROUTER_TOKEN`): **4 из 4** — версия, статус, неверный токен (401), разделы (добавить →
  заменить без потери полей → дубликат → удалить), роутер входит в бота по коду и заводит подписки (заглушка бота), журнал и диагностика;
- вручную `curl`-ом: `version` (1.2.5), `update` (`supported: true`, `update_available: false`), `keenetic` (`available: false` вне
  KeeneticOS), `genconfig` (sing-box из trojan-ссылки), раздел с правилами `namespace:`/`full:`/`wildcard:`/`keyword:` — сохранён и
  прочитан обратно, верный токен отвечает за 24 мс после шести неверных, `?token=` вне потока журнала → 401.

**Не проверено:** любое реальное железо (OpenWrt, KeeneticOS, RouterOS) и Linux-сборка роутера (на Windows inbound `tproxy` не
поднимается — только Linux, служба стартует после ошибки), `nodes/test` на живых узлах, обновление подписки с настоящей панели,
`POST /api/v1/update`, `domainmap` с применением, Keenetic через `ndmc`; клиенты Android и iOS против живого роутера (проверялся клиент
на ПК, он общий с Android; Swift-клиент — только юнит-тесты разбора ссылки в macOS-CI).

## Известные ограничения (решения)

- **Android без TLS не работает**: `usesCleartextTraffic=false`, `network_security_config` запрещает HTTP. Роутер нужно сопрягать по
  https (`api_tls_cert`/`api_tls_key` на роутере; отпечаток закрепляется при сопряжении). Сообщение об этом показывается пользователю.
  Разрешать HTTP для LAN не стали — это поменяет модель безопасности приложения; решает владелец.
- **Тексты `RouterManager` (тосты/ошибки) только на русском** — они в общем коде `:shared`, без ресурсов. Подписи интерфейса
  локализованы на 5 языков (`strings_routers.xml`; iOS получает их скриптом).
- **Токен.** Android — DataStore `routers` + AES-GCM ключ в Android Keystore; хранилище намеренно не входит в `allStores()`
  (резервная копия/сброс). ПК — в `hydra.json` (запись атомарная, права 0600 на Linux/macOS; на Windows файл в профиле пользователя
  `%APPDATA%\Hydra`). iOS — Keychain, `ThisDeviceOnly`.
- Вкладка на ПК — между «Маршруты» и «Настройки»; на Android/iOS — перед «Настройками» (5 вкладок).
- iOS-строки **не править руками**: `bash scripts/android-strings-to-ios.sh` (нужен perl) пересобирает
  `ios/Shared/Resources/*/Localizable.strings` из `app/src/main/res/values*/strings*.xml`.
- Токен API роутера — минимум 16 знаков (с 1.2.1); короче роутер не запустит API.

## Как продолжить

1. Живой тест на Linux (контейнер, OpenWrt или KeeneticOS) с TLS: сопряжение из Android, ПК и iOS → статус → смена узла → добавление
   подписки → перезапуск; заодно `hydravpn-router selftest` на модели роутера.
2. Использовать новое из 1.2.4–1.2.5: кнопка «Обновить роутер» (`GET/POST /api/v1/update`, статус `state`, откат на версию), показ и
   правка доменных правил раздела (`domains`, типы `namespace:`/`full:`/`wildcard:`/`regexp:`/`keyword:`), `domainmap` и `genconfig`
   как сервисные действия, Keenetic (`keenetic`, `keenetic/proxy`) — только после проверки на железе.
3. Дальше по Фазе 13 (13d): перенос правил маршрутизации Hydra в раздел роутера («весь трафик этого устройства через роутерный VPN» через
   `fully_routed_ips`), разделы на Android и iOS (сейчас только просмотр), `/api/v1/capabilities` (появится в роутере — скрывать
   недоступное на старых версиях), виджет/плитка «роутер: онлайн, узел».
4. Раз в релиз Hydra сверять версию роутера: `gh release list --repo Chistovik92/HydraVPNforRouters`, `docs/API.md` роутера и этот файл;
   живой тест — `HYDRA_ROUTER_URL=http://127.0.0.1:18088 HYDRA_ROUTER_TOKEN=… gradle :desktop:test --tests '*RouterLiveTest*'`
   (запуск роутера: `hydravpn-router start -c t.yaml`; в `t.yaml` нужны `api_listen`, `api_token` ≥ 16 знаков, `singbox_binary`,
   `cache_path`, `config_path` — на Windows настоящие пути).
5. Помнить про KDoc: `/*` внутри комментария Kotlin открывает вложенный комментарий и ломает сборку (`Unclosed comment`) — так уже падал PR #19.
