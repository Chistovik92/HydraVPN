#!/usr/bin/env bash
#
# Выпуск релиза Hydra.
#
# Смысл скрипта — сделать структурно невозможным выпуск «пустого» релиза:
# без full-APK, внутри которого реально лежит ядро sing-box, скрипт просто не
# дойдёт до публикации. Предыстория — docs/HANDOFF.md, «Честные оговорки» (0.6.0):
# релиз 0.6.0 сначала уехал с одной stub-сборкой, где соединение симулируется.
#
# Использование:
#   scripts/release.sh                 # собрать, проверить, опубликовать (со спросом)
#   scripts/release.sh --dry-run       # только собрать и проверить, ничего не публиковать
#   scripts/release.sh --yes           # без интерактивного подтверждения
#   scripts/release.sh --notes FILE    # взять текст релиза из файла
#
set -euo pipefail

cd "$(dirname "$0")/.."

DRY_RUN=0
ASSUME_YES=0
NOTES_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --yes|-y)  ASSUME_YES=1; shift ;;
    --notes)   NOTES_FILE="${2:-}"; shift 2 ;;
    *) echo "Неизвестный аргумент: $1" >&2; exit 2 ;;
  esac
done

err()  { echo "" >&2; echo "ОШИБКА: $*" >&2; exit 1; }
step() { echo ""; echo "==> $*"; }

# --- 1. Версия ---------------------------------------------------------------
VERSION="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
[[ -n "$VERSION" ]] || err "не удалось прочитать versionName из app/build.gradle.kts"

TAG="v$VERSION"
FULL_APK="Hydra-full-$VERSION-debug.apk"
STUB_APK="Hydra-stub-$VERSION-debug.apk"

echo "Релиз Hydra $VERSION (тег $TAG)"

# --- 2. Предполётные проверки ------------------------------------------------
step "Предполётные проверки"

[[ -f app/libs/libbox.aar ]] || err \
"нет app/libs/libbox.aar — full-APK будет нерабочим.
       Соберите ядро по docs/BUILD.md (раздел 2.1) или положите готовый .aar."
echo "    libbox.aar на месте"

command -v gh >/dev/null 2>&1 || err "не найден gh CLI (нужен для публикации релиза)"
command -v unzip >/dev/null 2>&1 || err "не найден unzip (нужен для проверки содержимого APK)"

if [[ -f gradle/wrapper/gradle-wrapper.jar ]]; then
  GRADLE="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="gradle"
else
  err "нет ни gradle/wrapper/gradle-wrapper.jar, ни gradle в PATH (см. docs/BUILD.md)"
fi
echo "    сборщик: $GRADLE"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "    ВНИМАНИЕ: рабочее дерево не чистое — в релиз попадёт только закоммиченное"
fi

# --- 3. Сборка ---------------------------------------------------------------
step "Сборка stub-варианта"
"$GRADLE" :app:assembleStubDebug

step "Сборка full-варианта (native, с ядром sing-box)"
"$GRADLE" :app:assembleNativeDebug

SRC_FULL="app/build/outputs/apk/native/debug/app-native-debug.apk"
SRC_STUB="app/build/outputs/apk/stub/debug/app-stub-debug.apk"
[[ -f "$SRC_FULL" ]] || err "full-APK не собрался: $SRC_FULL"
[[ -f "$SRC_STUB" ]] || err "stub-APK не собрался: $SRC_STUB"

# --- 4. Главная проверка: внутри full-APK реально есть ядро -------------------
step "Проверка содержимого full-APK"

SO_LIST="$(unzip -l "$SRC_FULL" | awk '{print $NF}' | grep -E '^lib/[^/]+/libbox\.so$' || true)"
[[ -n "$SO_LIST" ]] || err \
"внутри $SRC_FULL нет lib/*/libbox.so.
       Это НЕ рабочая сборка — публикация отменена."

echo "$SO_LIST" | sed 's/^/    ядро: /'

if unzip -l "$SRC_STUB" | grep -q 'libbox\.so'; then
  err "в stub-APK оказался libbox.so — артефакты перепутаны, публикация отменена"
fi
echo "    stub чист от ядра (как и ожидалось)"

FULL_SIZE=$(stat -c %s "$SRC_FULL" 2>/dev/null || stat -f %z "$SRC_FULL")
[[ "$FULL_SIZE" -gt 40000000 ]] || err \
"full-APK подозрительно мал ($FULL_SIZE Б) — ядро скорее всего не попало внутрь"
echo "    размер full-APK: $((FULL_SIZE / 1024 / 1024)) МБ"

# --- 5. Готовим ассеты --------------------------------------------------------
cp -f "$SRC_FULL" "$FULL_APK"
cp -f "$SRC_STUB" "$STUB_APK"
trap 'rm -f "$FULL_APK" "$STUB_APK"' EXIT

if [[ $DRY_RUN -eq 1 ]]; then
  step "--dry-run: публикация пропущена"
  echo "    проверки пройдены, релиз можно выпускать"
  exit 0
fi

# --- 6. Публикация ------------------------------------------------------------
step "Публикация релиза $TAG"
echo "    ассеты: $FULL_APK, $STUB_APK"

if [[ $ASSUME_YES -eq 0 ]]; then
  read -r -p "    Публикуем на GitHub? [y/N] " answer
  [[ "$answer" == "y" || "$answer" == "Y" ]] || { echo "    отменено пользователем"; exit 1; }
fi

if ! git rev-parse "$TAG" >/dev/null 2>&1; then
  git tag -a "$TAG" -m "Hydra $VERSION"
fi
git push origin "$TAG"

if [[ -z "$NOTES_FILE" ]]; then
  NOTES_FILE="$(mktemp)"
  cat > "$NOTES_FILE" <<EOF
## Hydra $VERSION

Что изменилось — см. [CHANGELOG.md](https://github.com/Chistovik92/HydraVPN/blob/master/CHANGELOG.md).

| Файл | Что внутри |
|---|---|
| **\`$FULL_APK\`** | **Рабочее приложение** — с реальным ядром sing-box. |
| \`$STUB_APK\` | Только для разработки/CI: соединение **симулируется**. |
EOF
fi

if gh release view "$TAG" >/dev/null 2>&1; then
  gh release upload "$TAG" "$FULL_APK" "$STUB_APK" --clobber
else
  gh release create "$TAG" "$FULL_APK" "$STUB_APK" \
    --title "Hydra $VERSION" --notes-file "$NOTES_FILE"
fi

step "Готово: $(gh release view "$TAG" --json url --jq .url)"
