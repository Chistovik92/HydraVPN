# Участие в разработке

## Версии

Схема `X.Y.Z` и пре-релизы (`-a` альфа, `-b` бета, `-rcN` кандидат) — в [docs/ROADMAP.md](ROADMAP.md#схема-версий-решение-владельца-12102026). Альфа-ветки в `master` не вливаются, пока не проверены.

## Ветки и коммиты
- Основная ветка: `main`. Фичи — из `feature/*`, фиксы — `fix/*`.
- Стиль коммитов: Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`…).

## Кодстайл
- Kotlin official style (`kotlin.code.style=official`).
- UI — Jetpack Compose, без XML-верстки экранов.
- Комментарии и строки UI — на русском; идентификаторы — на английском.

## Перед PR
```bash
./gradlew :app:assembleStubDebug     # должно собираться без .aar
./gradlew :app:lintStubDebug
```

## Куда добавлять протокол
1. `data/model/Protocol.kt` — новый enum + движок.
2. `data/subscription/LinkParser.kt` — парсер ссылки.
3. `data/subscription/SingBoxConfigBuilder.kt` — генерация outbound.
4. При необходимости — ветка в `NativeCoreFactory`.

## Безопасность
Не коммитьте ключи подписи, реальные PSK/UUID и приватные подписки.
Секреты в рантайме — только через Keystore/EncryptedSharedPreferences.
