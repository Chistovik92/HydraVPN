#!/usr/bin/env bash
#
# Зеркало сторонних сборок для Hydra: sing-box, Xray-core, OpenFlux (ПК/Classic) и libXray (Android).
# Апстрим удаляет релизы (Xray v26.10.10 и v26.10.17 пропали), и сборка прежней версии перестаёт скачиваться. Скрипт берёт
# закреплённые версии и SHA-256 из desktop/build.gradle.kts и scripts/fetch-libxray.sh, скачивает файлы, сверяет хеши и кладёт их
# в каталог mirror/ под именами, которые ждут сборка ПК (`Xray-<версия>-<архив>.zip`) и fetch-libxray.sh (`libxray-android-<версия>.zip`).
#
# По умолчанию ничего не публикует. С --publish загружает файлы в релиз `deps` репозитория (создаёт его, если нет) — это публичное
# действие, делается только по команде владельца.   Использование:  scripts/mirror-deps.sh [--publish]
#
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GH_REPO="${GH_REPO:-Chistovik92/HydraVPN}"
OUT="$REPO/mirror"
GH="${GH:-$(command -v gh || true)}"
if [[ -z "$GH" && -f "/mnt/c/Program Files/GitHub CLI/gh.exe" ]]; then GH="/mnt/c/Program Files/GitHub CLI/gh.exe"; fi
[[ -n "$GH" ]] || { echo "Не найден gh. Укажите GH=/полный/путь/gh.exe" >&2; exit 2; }
mkdir -p "$OUT"

fetch() {   # url имя sha256
  local url="$1" name="$2" sha="$3"
  if [[ -f "$OUT/$name" && "$(sha256sum "$OUT/$name" | cut -d' ' -f1)" == "$sha" ]]; then echo "уже есть: $name"; return; fi
  echo "==> $url"
  curl -fL --retry 4 --retry-delay 3 -o "$OUT/$name" "$url"
  [[ "$(sha256sum "$OUT/$name" | cut -d' ' -f1)" == "$sha" ]] || { echo "ОШИБКА: SHA-256 $name не совпал с закреплённым" >&2; rm -f "$OUT/$name"; exit 1; }
}

# sing-box для обычной Desktop и Classic (включая legacy Windows 7).
SING_VERSION="$(sed -n 's/^val singBoxVersion = "\([^"]*\)".*/\1/p' "$REPO/desktop/build.gradle.kts")"
[[ -n "$SING_VERSION" ]] || { echo "не нашёл singBoxVersion" >&2; exit 1; }
while read -r archive sha; do
  name="sing-box-$SING_VERSION-$archive"
  fetch "https://github.com/SagerNet/sing-box/releases/download/v$SING_VERSION/$name" "$name" "$sha"
done < <(sed -n '/^val coreArchives/,/^)/p' "$REPO/desktop/build.gradle.kts" | sed -n 's/.*CoreArchive("[^"]*", "\([^"]*\)", "\([0-9a-f]\{64\}\)").*/\1 \2/p')
# Официальный SagerNet не публикует legacy Windows 7: исполняемые файлы и лицензия
# извлечены из выпущенных архивов Hydra Classic. Зеркало хранит их в общем релизе deps.
while read -r asset sha; do
  asset="${asset//\$singBoxVersion/$SING_VERSION}"
  fetch "https://github.com/Chistovik92/HydraVPN/releases/download/deps/$asset" "$asset" "$sha"
done < <(sed -n '/^val classicTargets/,/^)/p' "$REPO/desktop/build.gradle.kts" | sed -n 's/^[[:space:]]*"\(sing-box-[^"]*legacy-windows-7[^"]*\)", "\([0-9a-f]\{64\}\)",.*/\1 \2/p')
while read -r asset sha; do
  asset="${asset//\$singBoxVersion/$SING_VERSION}"
  fetch "https://github.com/SagerNet/sing-box/releases/download/v$SING_VERSION/$asset" "$asset" "$sha"
done < <(sed -n '/^val classicTargets/,/^)/p' "$REPO/desktop/build.gradle.kts" | sed -n 's/^[[:space:]]*"\(sing-box-[^"]*\)", "\([0-9a-f]\{64\}\)",.*/\1 \2/p' | grep -v legacy-windows-7)

# Xray-core для ПК: строки вида  "windows-x64" to ("windows-64" to "<sha256>"),
XRAY_VERSION="$(sed -n 's/^val xrayVersion = "\([^"]*\)".*/\1/p' "$REPO/desktop/build.gradle.kts")"
[[ -n "$XRAY_VERSION" ]] || { echo "не нашёл xrayVersion" >&2; exit 1; }
while read -r suffix sha; do
  fetch "https://github.com/XTLS/Xray-core/releases/download/v$XRAY_VERSION/Xray-$suffix.zip" "Xray-$XRAY_VERSION-$suffix.zip" "$sha"
done < <(sed -n '/^val xrayArchives/,/^)/p' "$REPO/desktop/build.gradle.kts" | sed -n 's/.*" to ("\([a-z0-9-]*\)" to "\([0-9a-f]\{64\}\)").*/\1 \2/p')

# Classic Xray (32 бита и legacy Windows 7).
while read -r asset sha; do
  fetch "https://github.com/XTLS/Xray-core/releases/download/v$XRAY_VERSION/$asset" "Xray-$XRAY_VERSION-${asset#Xray-}" "$sha"
done < <(sed -n '/^val classicTargets/,/^)/p' "$REPO/desktop/build.gradle.kts" | sed -n 's/^[[:space:]]*"\(Xray-[^"]*\)", "\([0-9a-f]\{64\}\)",.*/\1 \2/p')

# OpenFlux: готовые бинари Desktop и Classic Linux ARMv7.
OF_VERSION="$(sed -n 's/^val openFluxVersion = "\([^"]*\)".*/\1/p' "$REPO/desktop/build.gradle.kts")"
[[ -n "$OF_VERSION" ]] || { echo "не нашёл openFluxVersion" >&2; exit 1; }
while read -r asset sha; do
  fetch "https://github.com/p1neappleXpress/OpenFlux/releases/download/v$OF_VERSION/$asset" "OpenFlux-$OF_VERSION-$asset" "$sha"
done < <(sed -n '/^val openFluxBinaries/,/^)/p' "$REPO/desktop/build.gradle.kts" | sed -n 's/.*" to ("\([^"]*\)" to "\([0-9a-f]\{64\}\)").*/\1 \2/p')
while read -r asset sha; do
  fetch "https://github.com/p1neappleXpress/OpenFlux/releases/download/v$OF_VERSION/$asset" "OpenFlux-$OF_VERSION-$asset" "$sha"
done < <(sed -n '/^val classicTargets/,/^)/p' "$REPO/desktop/build.gradle.kts" | sed -n 's/.*openFlux = "\([^"]*\)" to "\([0-9a-f]\{64\}\)".*/\1 \2/p')

# libXray для Android
LV="$(sed -n 's/^LIBXRAY_VERSION="\([^"]*\)".*/\1/p' "$REPO/scripts/fetch-libxray.sh")"
LS="$(sed -n 's/^LIBXRAY_SHA256="\([^"]*\)".*/\1/p' "$REPO/scripts/fetch-libxray.sh")"
fetch "https://github.com/XTLS/libXray/releases/download/v$LV/libxray-android.zip" "libxray-android-$LV.zip" "$LS"

echo; ls -l "$OUT"
if [[ "${1:-}" == "--publish" ]]; then
  "$GH" release view deps -R "$GH_REPO" >/dev/null 2>&1 || "$GH" release create deps -R "$GH_REPO" --prerelease --title "Зеркало сторонних сборок" \
    --notes "Копии закреплённых сторонних сборок (sing-box, Xray-core, OpenFlux, libXray) на случай, если апстрим удалит релиз. SHA-256 — в desktop/build.gradle.kts и scripts/fetch-libxray.sh. Не для установки."
  files=("$OUT"/*)
  if [[ "$GH" == *.exe ]] && command -v wslpath >/dev/null 2>&1; then
    for i in "${!files[@]}"; do files[$i]="$(wslpath -w "${files[$i]}")"; done
  fi
  "$GH" release upload deps -R "$GH_REPO" --clobber "${files[@]}"
  echo "Опубликовано в релиз deps репозитория $GH_REPO"
else
  echo "Ничего не опубликовано (запустите с --publish по команде владельца)."
fi
