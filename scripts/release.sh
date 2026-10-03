#!/usr/bin/env bash
#
# Выпуск релиза Hydra — все платформы.
#
# Смысл скрипта — сделать структурно невозможным выпуск неполного релиза.
#   * Android собирается ЗДЕСЬ (ключ подписи есть только локально): оба APK,
#     проверка, что внутри full-APK реально лежит ядро sing-box, и что подпись
#     не Android Debug. Предыстория — docs/HANDOFF.md, «Честные оговорки» (0.6.0).
#   * Тег + ЧЕРНОВИК релиза с APK. Тег запускает .github/workflows/release.yml:
#     Windows / Linux (x64, arm64) / macOS (arm64, x64) и iOS собираются и
#     проверяются на своих ОС, докладываются в черновик, и только когда на месте
#     КАЖДАЯ платформа — релиз публикуется. Иначе он так и остаётся черновиком.
#
# Использование:
#   scripts/release.sh                 # собрать, проверить, выпустить (со спросом)
#   scripts/release.sh --dry-run       # только собрать и проверить Android
#   scripts/release.sh --yes           # без интерактивного подтверждения
#   scripts/release.sh --no-wait       # не ждать сборки остальных платформ в CI
#   scripts/release.sh --notes FILE    # взять текст релиза из файла
#
set -euo pipefail

cd "$(dirname "$0")/.."

DRY_RUN=0
ASSUME_YES=0
WAIT=1
NOTES_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --yes|-y)  ASSUME_YES=1; shift ;;
    --no-wait) WAIT=0; shift ;;
    --notes)   NOTES_FILE="${2:-}"; shift 2 ;;
    *) echo "Неизвестный аргумент: $1" >&2; exit 2 ;;
  esac
done

step() { printf '\n==> %s\n' "$*"; }
err()  { printf '\nОШИБКА: %s\n' "$*" >&2; exit 1; }

# --- 1. Версия ----------------------------------------------------------------
VERSION="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
[[ -n "$VERSION" ]] || err "не удалось прочитать versionName из app/build.gradle.kts"
TAG="v$VERSION"
STUB_APK="Hydra-stub-$VERSION.apk"

echo "Релиз Hydra $VERSION (тег $TAG)"
echo "Платформы: Android (здесь), Windows, Linux x64/arm64, macOS arm64/x64, iOS (в CI)"

# --- 2. Предполётные проверки ------------------------------------------------
step "Предполётные проверки"

[[ -f app/libs/libbox.aar ]] || err \
"нет app/libs/libbox.aar — full-APK будет нерабочим.
       Соберите ядро по docs/BUILD.md (раздел 2.1) или положите готовый .aar."
echo "    libbox.aar на месте"

# Xray — штатное второе ядро: без libXray.aar переключатель «Xray Core» в настройках неактивен
# (так вышли 0.6.x–0.7.2, пока ядро не было собрано).
[[ -f app/libs/libXray.aar ]] || err \
"нет app/libs/libXray.aar — в full-APK не будет ядра Xray.
       Соберите его по docs/BUILD.md (раздел 2.2) или положите готовый .aar."
echo "    libXray.aar на месте"

[[ -f keystore.properties ]] || err \
"нет keystore.properties в корне проекта — без него release-сборка уйдёт
       неподписанной/подписанной debug-ключом (см. docs/HANDOFF.md, «Честные
       оговорки», и docs/BUILD.md раздел «Подпись релиза»)."
echo "    keystore.properties на месте"

# gh: PATH или стандартные места установки на Windows (Git Bash не всегда видит PATH).
GH="$(command -v gh || true)"
if [[ -z "$GH" ]]; then
  for p in "/c/Program Files/GitHub CLI/gh.exe" "/c/Program Files (x86)/GitHub CLI/gh.exe"; do
    [[ -f "$p" ]] && { GH="$p"; break; }
  done
fi
if [[ -z "$GH" ]] && command -v where.exe >/dev/null 2>&1; then
  GH="$(where.exe gh 2>/dev/null | head -1 | tr -d '\r' || true)"
fi
[[ -n "$GH" ]] || err "не найден gh CLI (нужен для публикации релиза)"
echo "    gh: $GH"

# Список файлов внутри APK: unzip, а на Windows без unzip — PowerShell.
apk_list() {
  if command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$1"
  elif command -v powershell >/dev/null 2>&1; then
    local win; win="$(cygpath -w "$1" 2>/dev/null || echo "$1")"
    powershell -NoProfile -Command "Add-Type -A System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::OpenRead('$win').Entries | ForEach-Object { \$_.FullName }" | tr -d '\r'
  else
    err "нет ни unzip, ни powershell — нечем проверить содержимое APK"
  fi
}

if [[ -n "${GRADLE:-}" ]]; then :
elif [[ -f gradle/wrapper/gradle-wrapper.jar ]]; then GRADLE="./gradlew"
elif command -v gradle >/dev/null 2>&1; then GRADLE="gradle"
else err "нет ни gradle/wrapper/gradle-wrapper.jar, ни gradle в PATH (или задайте GRADLE=…; см. docs/BUILD.md)"
fi
echo "    сборщик: $GRADLE"

if [[ -n "$(git status --porcelain)" ]]; then
  err "рабочее дерево не чистое. Остальные платформы CI собирает из тега —
       всё, что должно попасть в релиз, должно быть закоммичено и запушено."
fi
git fetch -q origin
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/master)" ]] || err \
"HEAD не совпадает с origin/master — сначала запушьте master (CI соберёт тег именно отсюда)."
echo "    дерево чистое, HEAD = origin/master"

# --- 3. Сборка Android --------------------------------------------------------
step "Сборка stub-варианта"
"$GRADLE" :app:assembleStubRelease

step "Сборка full-варианта (native, с ядром sing-box)"
"$GRADLE" :app:assembleNativeRelease

# 0.7.2: full-APK — отдельный файл на каждую архитектуру (arm64-v8a, armeabi-v7a, x86_64, x86); stub — один (без ядра).
ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
SRC_STUB="app/build/outputs/apk/stub/release/app-stub-arm64-v8a-release.apk"
SRC_FULLS=()
FULL_APKS=()
for abi in "${ABIS[@]}"; do
  src="app/build/outputs/apk/native/release/app-native-$abi-release.apk"
  [[ -f "$src" ]] || err "full-APK ($abi) не собрался: $src"
  SRC_FULLS+=("$src")
  FULL_APKS+=("Hydra-full-$VERSION-$abi.apk")
done
[[ -f "$SRC_STUB" ]] || err "stub-APK не собрался: $SRC_STUB"

# --- 4. Главная проверка: внутри каждого full-APK реально есть ядро своей архитектуры ---
step "Проверка содержимого full-APK"

for i in "${!ABIS[@]}"; do
  abi="${ABIS[$i]}"; src="${SRC_FULLS[$i]}"
  [[ "$(apk_list "$src" | grep -cE "^lib/$abi/libbox\.so$" || true)" -ge 1 ]] || err \
"внутри $src нет lib/$abi/libbox.so.
       Это НЕ рабочая сборка — публикация отменена."
  [[ "$(apk_list "$src" | grep -cE '^assets/libxray\.dex$' || true)" -ge 1 ]] || err \
"внутри $src нет assets/libxray.dex — ядро Xray не встроено (нужен app/libs/libXray.aar).
       Это неполная сборка — публикация отменена."
  if apk_list "$src" | grep -E '^lib/[^/]+/' | grep -vqE "^lib/$abi/"; then
    err "в $src есть библиотеки чужой архитектуры — разделение по ABI не сработало"
  fi
  size=$(stat -c %s "$src" 2>/dev/null || stat -f %z "$src")
  [[ "$size" -gt 15000000 ]] || err "$src подозрительно мал ($size Б) — ядро скорее всего не попало внутрь"
  echo "    $abi: ядро на месте, $((size / 1024 / 1024)) МБ"
done

if apk_list "$SRC_STUB" | grep -q 'libbox\.so'; then
  err "в stub-APK оказался libbox.so — артефакты перепутаны, публикация отменена"
fi
echo "    stub чист от ядра (как и ожидалось)"

# --- 4b. Проверка: подпись — не Android Debug ---------------------------------
# История: релизы 0.5.1–0.6.1 уходили с автосгенерированным debug-ключом
# (assembleXxxDebug вместо assembleXxxRelease) — apksigner показывал
# "CN=Android Debug". У debug-ключа на каждой машине своё значение, поэтому
# случайное возвращение к debug-сборке ломает обновление для всех, у кого
# уже стоит правильно подписанная версия. Проверяем все сборки.
step "Проверка подписи (не должна быть Android Debug)"

APKSIGNER="$(command -v apksigner || true)"
if [[ -z "$APKSIGNER" && -n "${ANDROID_HOME:-}" ]]; then
  APKSIGNER="$(ls -1 "$ANDROID_HOME"/build-tools/*/apksigner* 2>/dev/null | sort -V | tail -1 || true)"
fi

if [[ -z "$APKSIGNER" ]]; then
  echo "    ВНИМАНИЕ: apksigner не найден — подпись НЕ проверена автоматически." >&2
  echo "    Проверьте вручную: apksigner verify --print-certs ${SRC_FULLS[0]}" >&2
else
  for apk in "${SRC_FULLS[@]}" "$SRC_STUB"; do
    CERT_DN="$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null | grep 'certificate DN' | head -1)"
    if [[ "$CERT_DN" == *"Android Debug"* ]]; then
      err "$apk подписан debug-ключом (Android Debug) — это не релизная подпись.
       Проверьте keystore.properties и signingConfig в app/build.gradle.kts."
    fi
    [[ -n "$CERT_DN" ]] || err "$apk: не удалось определить подпись — файл не подписан?"
  done
  echo "    все сборки подписаны релизным ключом (не Android Debug)"
fi

# --- 5. Готовим ассеты --------------------------------------------------------
for i in "${!ABIS[@]}"; do cp -f "${SRC_FULLS[$i]}" "${FULL_APKS[$i]}"; done
cp -f "$SRC_STUB" "$STUB_APK"
trap 'rm -f "${FULL_APKS[@]}" "$STUB_APK"' EXIT

if [[ $DRY_RUN -eq 1 ]]; then
  step "--dry-run: публикация пропущена"
  echo "    Android-проверки пройдены. Desktop/iOS проверяются в CI (desktop.yml, ios.yml)."
  exit 0
fi

# --- 6. Тег + черновик релиза -------------------------------------------------
step "Выпуск $TAG"

if [[ $ASSUME_YES -eq 0 ]]; then
  read -r -p "    Ставим тег и создаём релиз на GitHub? [y/N] " answer
  [[ "$answer" =~ ^[YyДд]$ ]] || { echo "    отменено"; exit 0; }
fi

if [[ -z "$NOTES_FILE" ]]; then
  NOTES_FILE="$(mktemp)"
  cat > "$NOTES_FILE" <<EOF
## Hydra $VERSION

Что изменилось — см. [CHANGELOG.md](https://github.com/Chistovik92/HydraVPN/blob/master/CHANGELOG.md).

### Android
| Файл | Что внутри |
|---|---|
| **\`Hydra-full-$VERSION-arm64-v8a.apk\`** | **Рабочее приложение** для большинства телефонов (64-бит ARM) — с реальным ядром sing-box. |
| \`Hydra-full-$VERSION-armeabi-v7a.apk\` | То же для 32-битных ARM-устройств: старые телефоны, приставки Android TV. |
| \`Hydra-full-$VERSION-x86_64.apk\` | То же для эмуляторов и 64-битных устройств на Intel/AMD. |
| \`Hydra-full-$VERSION-x86.apk\` | То же для 32-битных устройств на Intel (x86): планшеты, приставки, Chromebook. |
| \`$STUB_APK\` | Только для разработки/CI: соединение **симулируется**. |

### Windows 10/11 (x64)
| Файл | |
|---|---|
| \`Hydra-desktop-$VERSION-windows-x64.msi\` | Установщик (для текущего пользователя) |
| \`Hydra-desktop-$VERSION-windows-x64.exe\` | Установщик (EXE) |
| \`Hydra-desktop-$VERSION-windows-x64-portable.zip\` | Без установки: распаковать, запустить \`Hydra.exe\` |

Режим «Системный прокси» работает без прав администратора. Для режима TUN (весь трафик) Hydra предложит перезапуститься от имени администратора.
Установщик не подписан сертификатом издателя — SmartScreen может показать «Неизвестный издатель» → «Подробнее» → «Выполнить в любом случае».

### Linux (x64 и arm64)
| Файл | |
|---|---|
| \`…-linux-<arch>.deb\` | Debian / Ubuntu / Mint: \`sudo apt install ./Hydra-desktop-…deb\` |
| \`…-linux-<arch>.rpm\` | Fedora / RHEL / openSUSE: \`sudo dnf install ./Hydra-desktop-…rpm\` |
| \`…-linux-<arch>.AppImage\` | Любой дистрибутив: \`chmod +x\` и запустить |
| \`…-linux-<arch>.tar.gz\` | Портативная версия: \`Hydra/bin/Hydra\` |

Системный прокси выставляется в GNOME и KDE. Для режима TUN при первом включении система один раз спросит пароль (права CAP_NET_ADMIN для ядра через pkexec).

### macOS 12+ (Apple Silicon и Intel)
| Файл | |
|---|---|
| \`Hydra-desktop-$VERSION-macos-arm64.dmg\` | M1/M2/M3/M4 |
| \`Hydra-desktop-$VERSION-macos-x64.dmg\` | Intel |

Приложение не нотаризовано Apple (нужен платный Developer ID). При первом запуске: «Системные настройки → Конфиденциальность и безопасность → Всё равно открыть», либо \`xattr -dr com.apple.quarantine /Applications/Hydra.app\`. В режиме TUN macOS спрашивает пароль администратора при подключении и отключении.

**Протоколы на ПК:** VLESS (в т.ч. REALITY), VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard; подписки. AmneziaWG, SSTP, L2TP, WDTT — пока только Android (olcRTC и OpenFlux — BETA с 0.7.4).

### iOS
\`Hydra-ios-$VERSION-unsigned.ipa\` — **без подписи**. Установить на iPhone можно только после переподписи сертификатом платного аккаунта Apple Developer Program (Network Extension не выдаётся бесплатным аккаунтам), в App Store приложения пока нет.
EOF
fi

git tag -a "$TAG" -m "Hydra $VERSION"
git push origin "$TAG"

if "$GH" release view "$TAG" >/dev/null 2>&1; then
  "$GH" release upload "$TAG" "${FULL_APKS[@]}" "$STUB_APK" --clobber
else
  "$GH" release create "$TAG" "${FULL_APKS[@]}" "$STUB_APK" --draft \
    --title "Hydra $VERSION" --notes-file "$NOTES_FILE"
fi
echo "    черновик $TAG с APK создан; остальные платформы собирает release.yml"

# --- 7. Ждём CI: публикация — только когда собраны и проверены ВСЕ платформы ----
if [[ $WAIT -eq 0 ]]; then
  step "Готово (без ожидания). Релиз опубликуется сам после release.yml: $("$GH" run list --workflow release.yml --limit 1 --json url --jq '.[0].url' 2>/dev/null || true)"
  exit 0
fi

step "Жду release.yml (desktop ×5, iOS, проверка состава)"
RUN_ID=""
for _ in $(seq 1 30); do
  RUN_ID="$("$GH" run list --workflow release.yml --branch "$TAG" --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"
  [[ -n "$RUN_ID" ]] && break
  sleep 10
done
[[ -n "$RUN_ID" ]] || err "release.yml для $TAG не запустился — проверьте Actions"
"$GH" run watch "$RUN_ID" --exit-status --interval 30 || err \
"release.yml упал — релиз $TAG остался черновиком. Логи: $("$GH" run view "$RUN_ID" --json url --jq .url)"

step "Готово: $("$GH" release view "$TAG" --json url --jq .url)"
