# Hydra для iOS (Фаза 12)

Нативный клиент на SwiftUI с ядром sing-box в Network Extension — тот же подход, что у
официального клиента sing-box для Apple. Стратегия и почему не Kotlin Multiplatform сейчас —
[docs/MULTIPLATFORM.md](../docs/MULTIPLATFORM.md), фаза M2.

## Состав

| Каталог | Что внутри |
|---|---|
| `Packages/HydraKit` | Переносимая логика (только Foundation): модели, парсер ссылок и подписок, WireGuard/AmneziaWG `.conf`, конфиг sing-box, синхронизация подписок, резервная копия **в формате Android**, маска ключей, локация. Тесты — `swift test`. |
| `HydraTunnel` | Network Extension (`NEPacketTunnelProvider`): собирает конфиг из общего состояния в App Group и поднимает Libbox. geo-базы `.srs` — те же файлы, что в Android-ассетах. |
| `Hydra` | Приложение: Главная, Серверы, Профиль, Настройки; блокировка Face ID / кодом; фоновое автообновление подписок. |
| `HydraWidgets` | Виджет (экран «Домой» и экран блокировки: локация, статус, кнопка), кнопка Пункта управления (iOS 18). |
| `Shared` | Общий код приложения и виджета, локализации. |

Локализации **не правятся вручную**: `scripts/android-strings-to-ios.sh` собирает их из
`app/src/main/res/values*/strings*.xml` (5 языков) плюс `Shared/IOSStrings/*.strings`.

## Что есть и чего нет по сравнению с Android

| Android | iOS |
|---|---|
| sing-box: VLESS/REALITY, VMess, Trojan, SS, Hysteria2, TUIC, WireGuard | ✅ то же ядро и тот же конфиг |
| Подписки: HWID, название/трафик/срок от панели, автообновление, синхронизация | ✅ (автообновление — BGAppRefreshTask) |
| Импорт: буфер, ссылка, QR камерой/с фото, `.conf`, deep-link | ✅ + импорт из файла |
| DNS, GeoIP по странам, фрагментация TLS, MTU, IPv6, профили маршрутизации | ✅ |
| Раздельное туннелирование по IP/доменам | ✅ |
| Kill Switch | ✅ `includeAllNetworks` |
| Автоподключение при запуске/загрузке, переподключение | ✅ правила On-Demand |
| Плитка в шторке, виджет, ярлыки | ✅ виджет, Пункт управления, «Команды» |
| Блокировка приложения, скрытие ключей | ✅ Face ID / Touch ID / код |
| Хотспот-прокси | ⚠️ собран, не проверен (слушатель в Network Extension) |
| Резервная копия | ✅ совместима с Android в обе стороны |
| Темы, 5 языков | ✅ (язык — в Настройках iOS для приложения) |
| Раздельное туннелирование по приложениям | ❌ iOS разрешает только через MDM |
| Xray Core | ❌ второй Go-рантайм в процессе расширения несовместим с Libbox; VLESS/REALITY/Vision работает через sing-box |
| olcRTC, OpenFlux | ❌ iOS запрещает подпроцессы |
| AmneziaWG | ⏳ отдельное расширение на amneziawg-go — следующий шаг |
| SSTP / L2TP | ⏳ порт PPP-стека с Kotlin — следующий шаг |
| Проверка обновлений | — на iOS обновления приходят через TestFlight/App Store |

## Сборка

Нужен Mac с Xcode 16+ (CI: `.github/workflows/ios.yml`, собирает без подписи на каждом push).

```bash
# 1. Libbox.xcframework из исходников sing-box той же версии, что на Android (1.12.9)
git clone --depth 1 --branch v1.12.9 https://github.com/SagerNet/sing-box && cd sing-box
go install github.com/sagernet/gomobile/cmd/gomobile@latest github.com/sagernet/gomobile/cmd/gobind@latest
gomobile init && go run ./cmd/internal/build_libbox -target apple -platform ios
mv Libbox.xcframework ../Hydra/ios/Frameworks/

# 2. Локализации и проект
bash scripts/android-strings-to-ios.sh
brew install xcodegen && cd ios && xcodegen generate && open Hydra.xcodeproj
```

### Запуск на iPhone

Network Extension работает только с подписью разработчика, у которой есть entitlement
**Packet Tunnel** — это требует платного **Apple Developer Program** ($99 в год). Бесплатный
аккаунт Xcode такой entitlement не выдаёт. Дальше: указать свой Team ID в `project.yml`
(`DEVELOPMENT_TEAM`), зарегистрировать App Group `group.ru.gidravpn.hydra` и три bundle id
(`ru.gidravpn.hydra`, `.tunnel`, `.widgets`), собрать на устройство или выложить в TestFlight.

Неподписанный `.ipa` из CI (артефакт `hydra-ios-unsigned`) можно переподписать своим профилем.

## Статус

Собирается в CI, тесты HydraKit проходят. **На iPhone не запускался ни разу** — у проекта
пока нет аккаунта Apple Developer. Туннель написан по образцу официального клиента sing-box
под ту же версию libbox, но живой трафик через него не проверен.
