#!/usr/bin/env bash
#
# Зеркало сторонних сборок для Hydra (0.8.2-rc2): Xray-core (архивы ПК) и libXray (Android) закреплённых версий.
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
mkdir -p "$OUT"

fetch() {   # url имя sha256
  local url="$1" name="$2" sha="$3"
  if [[ -f "$OUT/$name" && "$(sha256sum "$OUT/$name" | cut -d' ' -f1)" == "$sha" ]]; then echo "уже есть: $name"; return; fi
  echo "==> $url"
  curl -fL --retry 4 --retry-delay 3 -o "$OUT/$name" "$url"
  [[ "$(sha256sum "$OUT/$name" | cut -d' ' -f1)" == "$sha" ]] || { echo "ОШИБКА: SHA-256 $name не совпал с закреплённым" >&2; rm -f "$OUT/$name"; exit 1; }
}

# Xray-core для ПК: строки вида  "windows-x64" to ("windows-64" to "<sha256>"),
XRAY_VERSION="$(sed -n 's/^val xrayVersion = "\([^"]*\)".*/\1/p' "$REPO/desktop/build.gradle.kts")"
[[ -n "$XRAY_VERSION" ]] || { echo "не нашёл xrayVersion" >&2; exit 1; }
while read -r suffix sha; do
  fetch "https://github.com/XTLS/Xray-core/releases/download/v$XRAY_VERSION/Xray-$suffix.zip" "Xray-$XRAY_VERSION-$suffix.zip" "$sha"
done < <(sed -n '/^val xrayArchives/,/^)/p' "$REPO/desktop/build.gradle.kts" | sed -n 's/.*" to ("\([a-z0-9-]*\)" to "\([0-9a-f]\{64\}\)").*/\1 \2/p')

# libXray для Android
LV="$(sed -n 's/^LIBXRAY_VERSION="\([^"]*\)".*/\1/p' "$REPO/scripts/fetch-libxray.sh")"
LS="$(sed -n 's/^LIBXRAY_SHA256="\([^"]*\)".*/\1/p' "$REPO/scripts/fetch-libxray.sh")"
fetch "https://github.com/XTLS/libXray/releases/download/v$LV/libxray-android.zip" "libxray-android-$LV.zip" "$LS"

echo; ls -l "$OUT"
if [[ "${1:-}" == "--publish" ]]; then
  gh release view deps -R "$GH_REPO" >/dev/null 2>&1 || gh release create deps -R "$GH_REPO" --prerelease --title "Зеркало сторонних сборок" \
    --notes "Копии закреплённых сторонних сборок (Xray-core, libXray) на случай, если апстрим удалит релиз. SHA-256 — в desktop/build.gradle.kts и scripts/fetch-libxray.sh. Не для установки."
  gh release upload deps -R "$GH_REPO" --clobber "$OUT"/*
  echo "Опубликовано в релиз deps репозитория $GH_REPO"
else
  echo "Ничего не опубликовано (запустите с --publish по команде владельца)."
fi
