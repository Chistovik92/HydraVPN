# Мультиплатформенность — дорожная карта

Статус: **в реализации**. Фаза M0 (shared data layer) завершена, Фаза M1 (Desktop core) в работе. Compose UI заблокирован недоступностью JetBrains Maven repository.

## Зачем

Hydra сейчас — Android-only. Запрос на iOS/desktop-клиенты и на единый формат
ссылок для обмена конфигами между платформами возникает регулярно. Тащить это
бездумно (общий UI-фреймворк, общий VPN-core) дорого и рискованно — история
проекта (`docs/HANDOFF.md`, «Честные оговорки») показывает, что именно нативный
слой tun/VPN-core давал больше всего живых багов (утечки fd, JNI-крэши, гонки
при переключении сервера). Поэтому план разделяет код на то, что переносится
дёшево, и то, что переписывается заново на каждой платформе осознанно.

## Архитектура

```
HydraVPN/
├── app/              # Android-приложение (зависит от :shared)
├── shared/           # KMP модуль — общий data-слой
│   ├── commonMain/   # Модели, парсеры, билдеры конфигов, PPP-протокол
│   ├── androidMain/  # Room-сущности, DataStore
│   └── desktopMain/  # JVM-специфичные зависимости
├── desktop/          # Desktop-приложение (Windows/Linux)
│   ├── commonMain/   # Общий код Desktop
│   └── desktopMain/  # WinTun (Windows), /dev/net/tun (Linux), JNA
└── scripts/          # Скрипты сборки и упаковки
```

## Что переносимо, а что нет

| Пакет | Связь с Android | Перенос |
|---|---|---|
| `vpn/ppp/` (Md4, MsChapV2, Ppp, PppSession) | нет | ✅ перенесено в `commonMain` |
| `vpn/ppp/TunBridge.kt` | `ParcelFileDescriptor` | протокольная часть (NAT/чек-суммы) — ✅, обёртка fd — platform-specific |
| `data/subscription/LinkParser.kt`, `WireGuardParser.kt` | `android.net.Uri`, `android.util.Base64` | ✅ заменено на `kotlin.io.encoding.Base64` и `UriParser` |
| `data/model/ServerProfile.kt`, `Subscription.kt` | Room-аннотации | ✅ разделено: domain-модель в `commonMain`, Room-сущность в `androidMain` |
| `data/subscription/SingBoxConfigBuilder.kt`, `WireGuardConfigBuilder.kt` | нет | ✅ перенесено в `commonMain` |
| `vpn/core/*Core.kt` | JNI/gomobile, `VpnService.Builder` | **не переносится** — свой tun-механизм на каждой платформе |
| `ui/**` | Jetpack Compose | не переносится — Compose Multiplatform для Desktop, SwiftUI для iOS |

## Стратегия

**KMP только для `data`-слоя.** Не для VPN-core, не для UI целиком.

- `commonMain`: `data/model`, `data/subscription` (парсеры ссылок, билдеры конфигов), PPP-протокол
- Каждая платформа: свой VPN-core (нативный tun-механизм) и свой UI
- **Не** пытаемся написать общий VPN-core через abstraction layer поверх разных tun API

## Фазы

### Фаза M0 — подготовка `commonMain` ✅ ЗАВЕРШЕНО
- ✅ Заменён `android.util.Base64` → `kotlin.io.encoding.Base64`
- ✅ Заменён `android.net.Uri` → `UriParser` (свой лёгкий парсер)
- ✅ Разделены `ServerProfile`/`Subscription` на domain-модель и Room-сущность
- ✅ Вынесена протокольная часть `TunBridge` (NAT/чек-суммы)
- ✅ Создан модуль `:shared` с `commonMain`/`androidMain`/`desktopMain`
- ✅ Android-приложение переведено на зависимость от `:shared`
- ✅ Проверено: Android собирается и работает идентично

### Фаза M1 — Desktop (Windows/Linux) 🔄 В РАБОТЕ
- ✅ Создан модуль `:desktop` с JVM target
- ✅ Windows: WinTun JNA wrapper, route management, DNS, kill switch (Windows Firewall)
- ✅ Linux: /dev/net/tun, route management (iproute2), DNS (systemd-resolved), kill switch (nftables)
- ✅ Тесты для desktop модуля (8 тестов, все проходят)
- ✅ CI workflow для desktop (Windows/Linux/macOS)
- ✅ Скрипты упаковки: MSI (Windows), deb/AppImage (Linux)
- ⚠️ Compose Multiplatform UI — заблокирован (JetBrains Maven repo 503)
- ⏳ Упаковка MSI/AppImage/deb/rpm — требует Compose Desktop plugin

### Фаза M2 — macOS 🔄 В РАБОТЕ
- ✅ Создан macOS VPN manager (utun, route management, DNS, pfctl kill switch)
- ✅ Интеграция в desktop модуль (desktopMain)
- ⚠️ Compose Multiplatform UI — заблокирован (JetBrains Maven repo 503)
- ⏳ Упаковка — требует Xcode и Apple Developer Program

### Фаза M3 — iOS 🔄 В РАБОТЕ
- ✅ Создана структура iOS проекта (SwiftUI + Network Extension)
- ✅ PacketTunnelProvider с базовой конфигурацией
- ✅ Info.plist для основного приложения и extension
- ⚠️ Требуется Xcode для сборки и тестирования
- ⏳ sing-box integration через Libbox.xcframework
- ⏳ App Store review — проверка политики Apple по VPN-приложениям

### Фаза M4 — свой формат ссылок (план)
- `hydra://crypt1/<AES-256-GCM payload>` — обфускация от автоматики
- Реализация в `commonMain`, когда он уже есть

## Что осознанно не делаем сейчас

- **Не** переписываем VPN-core через общий абстрактный слой поверх разных tun API
- **Не** начинаем сразу с iOS — Desktop даёт первую реальную проверку
- **Не** переводим сразу весь UI на Compose Multiplatform ради iOS

## Сборка

```bash
# Android
./gradlew :app:assembleStubDebug
./gradlew :app:assembleNativeDebug

# Desktop (JVM)
./gradlew :desktop:compileKotlinDesktop
./gradlew :desktop:test

# Все платформы
./gradlew build
```

## Тестирование

```bash
# Android тесты
./gradlew :app:test

# Desktop тесты
./gradlew :desktop:test

# Все тесты
./gradlew test
```

## Упаковка

```bash
# Windows (требует WiX Toolset)
./scripts/package-windows-msi.sh

# Linux deb (требует dpkg-deb)
./scripts/package-linux-deb.sh

# Linux AppImage (требует appimagetool)
./scripts/package-linux-appimage.sh
```

## Открытые вопросы

- Монorepo vs отдельные репозитории? Монорепо проще синхронизировать
- Единый бренд/appId на iOS/Desktop? `ru.gidravpn.hydra` — Android
- GPL-3.0 — для Desktop/iOS сборок тоже становится определяющей лицензией