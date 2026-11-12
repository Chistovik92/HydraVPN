# HydraVPN

## Локальное окружение сборки/релиза (машина владельца, Windows)

Ключи подписи и инструменты для сборки и выпуска релиза лежат на этой машине. Пути:

**Ключи подписи Android**
- Keystore: `C:\Users\Chist\.android-keys\HydraVPN\Hydra.jks` (alias `Hydra`)
- Конфиг подписи: `E:\Git\HydraVPN\keystore.properties` (в `.gitignore`; внутри `storeFile`/`storePassword`/`keyAlias`/`keyPassword` — пароль брать оттуда)
- Бэкап пароля: `C:\Users\Chist\.android-keys\HydraVPN\Hydra.jks.password.txt`

**Инструменты**
- JDK 17 (для сборки Android): `C:\Users\Chist\.jdks\jdk-17.0.20+8` — подключён через `org.gradle.java.home` в `C:\Users\Chist\.gradle\gradle.properties`. Есть и JDK 21: `C:\Users\Chist\.jdks\jdk-21.0.12.1+1`. Дефолтный `java` в PATH — Oracle JDK 26, для сборки НЕ годится.
- Android SDK: `C:\Users\Chist\AppData\Local\Android\Sdk` (переменная `ANDROID_HOME`)
- gh CLI: `C:\Program Files\GitHub CLI\gh.exe` (вход: Chistovik92, scopes `repo`+`workflow`)
- Git Bash: `C:\Program Files\Git\bin\bash.exe` (запуск bash-скриптов на Windows)
- Gradle: обёртка `./gradlew` (Gradle 8.9), сборщик определяет `scripts/release.sh` сам.

**Запуск релиза** (пример 0.8.3-rc1, 12.11.2026): через Git Bash
`export JAVA_HOME='C:/Users/Chist/.jdks/jdk-17.0.20+8' && ./scripts/release.sh --yes --no-wait`
(`--yes` пропускает интерактивный вопрос; `--no-wait` не ждёт CI — desktop/iOS достраивает `release.yml` и сам публикует черновик). Без `JAVA_HOME` обёртка берёт Oracle JDK 26 и падает.

## Claude Code History

Полная история диалогов с Claude Code по этому проекту: `claude-history/_INDEX.md` (индекс) и `claude-history/*.md` (полные диалоги user/assistant с таймстампами и упоминаниями инструментов). Используй для контекста при дальнейшей разработке: — roadmap, принятые решения, известные баги и их фиксы.
