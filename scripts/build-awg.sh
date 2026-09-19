#!/usr/bin/env bash
#
# Сборка libwg-go.so (amneziawg-go) под три ABI → app/libs/awg/<abi>/libwg-go.so.
#
# Это НЕ gomobile: библиотека — обычная C-shared, собирается из
# github.com/amnezia-vpn/amneziawg-android (tunnel/tools/libwg-go), поэтому не
# конфликтует с go.Seq у libbox/libXray. Подробности — docs/BUILD.md, раздел 2.3.
#
# Нужны: Go (≥ 1.25, в PATH), Android NDK (переменная ANDROID_NDK_HOME или ndk/* в SDK), git.
# Использование:  scripts/build-awg.sh [каталог-с-клоном]
#
set -euo pipefail
cd "$(dirname "$0")/.."

WORK="${1:-$(mktemp -d)/amneziawg-android}"
PKG="ru.gidravpn.hydra"

NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "ОШИБКА: не найден Android NDK (задайте ANDROID_NDK_HOME)" >&2; exit 1; }
HOST="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
EXT=""; [[ "$HOST" == windows* ]] && EXT=".cmd"

if [[ ! -d "$WORK/.git" ]]; then
  # submodule'и (wg-tools, elf-cleaner) для libwg-go не нужны — без --recurse-submodules
  git clone --depth 1 https://github.com/amnezia-vpn/amneziawg-android.git "$WORK"
fi
cd "$WORK/tunnel/tools/libwg-go"

export CGO_ENABLED=1 GOOS=android
export CGO_LDFLAGS="-Wl,-soname=libwg-go.so -Wl,-z,max-page-size=16384 -Wl,--build-id=none"
LDFLAGS="-X github.com/amnezia-vpn/amneziawg-go/v3/ipc.socketDirectory=/data/data/$PKG/cache/amneziawg -buildid="

build() { # abi goarch cc [goarm]
  local abi="$1" goarch="$2" cc="$3" goarm="${4:-}"
  echo "==> $abi"
  mkdir -p "out/$abi"
  GOARCH="$goarch" GOARM="$goarm" CC="$BIN/$cc$EXT" \
    go build -tags linux -ldflags="$LDFLAGS" -trimpath -buildvcs=false \
    -o "out/$abi/libwg-go.so" -buildmode c-shared
  mkdir -p "$REPO/app/libs/awg/$abi"
  cp "out/$abi/libwg-go.so" "$REPO/app/libs/awg/$abi/"
}
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build arm64-v8a   arm64 aarch64-linux-android26-clang
build armeabi-v7a arm   armv7a-linux-androideabi26-clang 7
build x86_64      amd64 x86_64-linux-android26-clang
echo "Готово: app/libs/awg/*/libwg-go.so"
