#!/usr/bin/env bash
#
# Сборка клиента olcRTC (`cmd/olcrtc`) под три ABI → app/libs/olcrtc/<abi>/libolcrtc.so.
#
# Это ИСПОЛНЯЕМЫЙ файл, а не gomobile-библиотека: приложение запускает его подпроцессом
# из nativeLibraryDir (режим cnc → локальный SOCKS5), поэтому свой Go-рантайм живёт в
# своём процессе и не конфликтует с libbox. Апстрим: github.com/openlibrecommunity/olcrtc (WTFPL).
#
# Нужны: Go (≥ версии из go.mod апстрима), Android NDK, git.
# Флаг -checklinkname=0 обязателен: зависимость wlynxg/anet ссылается на net.zoneCache.
# Использование:  scripts/build-olcrtc.sh [каталог-с-клоном]
#
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

WORK="${1:-$(mktemp -d)/olcrtc}"
NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "ОШИБКА: не найден Android NDK (задайте ANDROID_NDK_HOME)" >&2; exit 1; }
HOST="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
EXT=""; [[ "$HOST" == windows* ]] && EXT=".cmd"

[[ -d "$WORK/.git" ]] || git clone --depth 1 https://github.com/openlibrecommunity/olcrtc.git "$WORK"
cd "$WORK"

export CGO_ENABLED=1 GOOS=android CGO_LDFLAGS="-Wl,-z,max-page-size=16384"
build() { # abi goarch cc
  echo "==> $1"
  mkdir -p "$REPO/app/libs/olcrtc/$1"
  GOARCH="$2" GOARM=7 CC="$BIN/$3$EXT" go build -trimpath -ldflags="-s -w -checklinkname=0" \
    -o "$REPO/app/libs/olcrtc/$1/libolcrtc.so" ./cmd/olcrtc
}
build arm64-v8a   arm64 aarch64-linux-android26-clang
build armeabi-v7a arm   armv7a-linux-androideabi26-clang
build x86_64      amd64 x86_64-linux-android26-clang
echo "Готово: app/libs/olcrtc/*/libolcrtc.so"
