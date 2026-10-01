# HANDOFF — управление роутером из Hydra (Фаза 13)

Состояние на 01.10.2026. Код слит в ветку 0.7.0 (`claude/roadmap-radar-integration-a9ec65`); отдельная ветка
**`claude/routers-screen`** больше не нужна. Роутерная часть — отдельный репозиторий
[HydraVPNforRouters](https://github.com/Chistovik92/HydraVPNforRouters) (Go), релиз **v1.2.0**.

## Что сделано

| Что | Где | Статус |
|---|---|---|
| Клиент API роутера `/api/v1/*`, разбор ссылки сопряжения, закрепление отпечатка TLS | `shared/src/commonMain/kotlin/ru/gidravpn/hydra/router/RouterClient.kt` | влито в `master` (PR #19) |
| Общая логика (опрос, команды, очередь) | `shared/.../router/RouterManager.kt` | в ветке |
| Экран «Роутеры» — ПК (Compose Desktop) | `desktop/.../ui/RoutersScreen.kt`, `AppController.routers`, `Store.routers` | в ветке; тест отрисовки `UiRenderTest` проходил **до** переноса менеджера в `:shared` |
| Экран «Роутеры» — Android | `app/.../ui/screens/RoutersScreen.kt`, `RoutersViewModel.kt`, `data/repository/RouterRepository.kt`, `res/values*/strings_routers.xml` | в ветке, **не собирается** (см. ниже) |
| Экран «Роутеры» — iOS | `ios/Hydra/RoutersView.swift`, `RouterModel.swift`, `ios/Packages/HydraKit/Sources/HydraKit/Router.swift`, тесты `RouterLinkTests.swift` | в ветке, **Swift не собирался** (не было macOS) |
| Фаза 13 в дорожной карте приведена к реальному контракту | `docs/ROADMAP.md` | влито (PR #19) |

Возможности экрана (везде одинаково): сопряжение по ссылке `hydravpn-router://host:port?token=…&tls=1&fp=<sha256>`
или адрес + токен (на телефонах ещё QR), выбор роутера, состояние (онлайн, версия, служба, аптайм,
последняя ошибка), узлы (выбор в группе, замер задержки), секции, подписки (добавить, обновить сейчас,
удалить, **отправить подписку из Hydra на роутер**), журнал, «Перечитать конфиг», «Перезапустить службу»
(с подтверждением). Опрос раз в 10 с, пока вкладка открыта.

## Что сломано / не проверено — начать с этого

1. **(исправлено в 0.7.0: Android собирается, пакет `router` берётся через `kotlin.srcDir` + зависимость задач от `syncSharedRouter`.)** Было: Android не собирается. Приложение (`:app`) не подключает `:shared` (в `app` свои копии классов;
   целиком подключать нельзя — дубли ломали слияние dex, см. комментарий в `app/build.gradle.kts`).
   Поэтому пакет `ru.gidravpn.hydra.router` подтягивается в `:app` копированием: задача `syncSharedRouter`
   (Sync в `app/build/generated/sharedRouter`) + `java.srcDir(...)` в `sourceSets["main"]`. Kotlin эту
   папку **не видит**: `Unresolved reference 'router'` в `RouterRepository.kt`. Варианты: подключить
   папку через `kotlin.srcDir` (расширение `kotlin { sourceSets.getByName("main") … }`) или через
   `android.sourceSets["main"].kotlin` (AGP 8.x); либо просто скопировать три файла в `app` (как уже
   сделано для остальных моделей), если Sync-подход не заведётся.
   Проверка: `gradle :app:compileStubDebugKotlin` (нужны `ANDROID_HOME`, JDK 17, Gradle 8.10; `gradlew`
   без `gradle-wrapper.jar` не запустится — jar в `.gitignore`).
2. **Десктоп после переноса `RouterManager` в `:shared` не пересобирался.** Проверить:
   `gradle :desktop:test --tests '*UiRenderTest*'` (рендерит все 7 вкладок в PNG в `desktop/build/ui-render/`).
   Заполненное состояние экрана (роутер добавлен) тестом не покрыто — только пустое.
3. **iOS не собирался и не запускался.** Возможны ошибки компиляции. Проверить на macOS/в CI:
   `swift test` в `ios/Packages/HydraKit` (тесты `RouterLinkTests`), затем сборка приложения.
   Особенно: замыкания `@MainActor`-модели в `RouterModel.run`, `SecTrustCopyCertificateChain`
   (iOS 15+), `.onChange(of:)` в `RoutersView`. Deep-link `hydravpn-router://` в `HydraApp.onOpenURL`
   сейчас уходит в импорт серверов — обработчик роутеров не добавлен.
4. **Живой тест клиента Kotlin/Swift против роутера не проводился.** Сам API роутера v1.2.0 проверен
   вручную `curl`-ом (см. ниже), но клиенты — только чтением кода.

## Известные ограничения (решения)

- **Android без TLS не работает**: в приложении `usesCleartextTraffic=false` и
  `network_security_config` запрещает HTTP. Роутер нужно сопрягать по https (включить
  `api_tls_cert`/`api_tls_key` на роутере). Сообщение об этом показывается пользователю. Разрешать
  HTTP для LAN не стали — это поменяет модель безопасности приложения; решает владелец.
- **Тексты `RouterManager` (тосты/ошибки) только на русском** — они в общем коде `:shared`, без ресурсов.
  Интерфейс (подписи) локализован на 5 языков через `strings_routers.xml` (iOS получает их скриптом).
- **Токен на Android** — DataStore `routers` + AES-GCM ключ в Android Keystore; хранилище намеренно
  не входит в `allStores()` (бэкап/сброс). На ПК — открытым текстом в `hydra.json` (нужно: права 0600
  или хранилище ОС). На iOS — Keychain, `ThisDeviceOnly`.
- Вкладка на ПК — между «Маршруты» и «Настройки»; на Android/iOS — перед «Настройками» (5 вкладок).
- iOS-строки **не править руками**: `bash scripts/android-strings-to-ios.sh` (нужен perl) пересобирает
  `ios/Shared/Resources/*/Localizable.strings` из `app/src/main/res/values*/strings*.xml`.

## Контракт API роутера (v1.2.0) и как его проверяли

Документ роутера: `docs/API.md` в HydraVPNforRouters. Аутентификация `Authorization: Bearer <token>`,
доступ только из частных сетей (`api_allow`), 10 неверных токенов в минуту → 429 (блокирует и верный
токен с того же адреса), TLS по `api_tls_cert/key`, отпечаток в `hydravpn-router pair --host <адрес>`.
Конфиг при изменениях через API переписывается целиком (комментарии пропадают, отступы 4 пробела,
рядом `config.yaml.bak`).

Ручная проверка на Windows-сборке `hydravpn-router-1.2.0-windows-amd64.exe` (SHA-256 сверен с
`checksums.txt`), запуск `hydravpn-router start -c t.yaml` с `settings.api_listen: "127.0.0.1:18088"`:
версия/статус/секции/подписки (URL маскируется `********`)/журнал/`pair`/блокировка по 429/TLS —
работают; в `t.yaml` нужно `singbox_binary`, `cache_path`, `config_path` с Windows-путями.
`/nodes`, `/nodes/select` проверены отдельным настоящим sing-box 1.12.9 с Clash API на `:9090`
(на Windows сервис сам sing-box не поднимает — inbound `tproxy` только Linux). Не проверено: `/nodes/test`
на живых узлах, обновление подписки с настоящей панели, весь цикл на Linux/OpenWrt.

Замечания к роутеру (отправлены владельцем в ветку разработки роутера): 429 блокирует и верный токен;
`?token=` принимается на любом пути (нужен только для SSE); `stop` не завершает дочерний sing-box;
у `Reload` нет общего таймаута; нет `/api/v1/capabilities`, ролей «смотреть/управлять», автогенерации
самоподписанного сертификата, `/traffics` и соединений, mDNS, отката по `sing-box check`.

## Как продолжить (по порядку)

1. `git fetch && git checkout claude/routers-screen`.
2. Починить сборку Android (п. 1), пересобрать десктоп (п. 2).
3. Открыть PR в `master`; дождаться CI (jobs: `build-stub`, `build-native`, `encoding`, `linux/macos/windows`).
   Обратите внимание: `/*` внутри KDoc-комментария Kotlin открывает вложенный комментарий и ломает
   сборку (`Unclosed comment`) — так уже падал PR #19.
4. Проверить iOS на macOS (п. 3), при необходимости добавить обработчик `hydravpn-router://`.
5. Живой тест: поднять `hydravpn-router` на Linux (контейнер/OpenWrt) с TLS, сопрячь из Android/ПК/iOS,
   пройти сценарий: сопряжение → статус → смена узла → добавление подписки → перезапуск.
6. Дальше по Фазе 13: 13d (перенос правил маршрутизации Hydra в секцию роутера, «весь трафик этого
   устройства через роутерный VPN» через `fully_routed_ips`), после появления в роутере
   `/api/v1/capabilities` — скрывать недоступное на старых версиях; виджет/плитка «роутер: онлайн, узел».
