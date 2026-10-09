#!/usr/bin/env bash
#
# Сборка ByeDPI (github.com/hufrea/byedpi, MIT) — локальный SOCKS5-прокси обхода DPI `ciadpi` —
# под четыре ABI Android (arm64-v8a, armeabi-v7a, x86_64, x86) → app/libs/byedpi/<abi>/libciadpi.so.
#
# Это ИСПОЛНЯЕМЫЙ файл (PIE), а не библиотека: приложение запускает его подпроцессом из
# nativeLibraryDir (так же, как libopenflux.so), см. ByeDpiCore. Приложение-аналог ByeByeDPI
# (romanvht/ByeByeDPI) гоняет тот же код через JNI в своём процессе; подпроцесс проще и надёжнее
# останавливается.
#
# Нужны: Android NDK, git. Использование:  scripts/build-byedpi.sh [каталог-с-клоном]
#
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

WORK="${1:-$(mktemp -d)/byedpi}"
NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "ОШИБКА: не найден Android NDK (задайте ANDROID_NDK_HOME)" >&2; exit 1; }
HOST="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
EXT=""; [[ "$HOST" == windows* ]] && EXT=".cmd"

# Метка v0.17.3 (26.03.2026). Сменить: BYEDPI_REF=<sha|tag> scripts/build-byedpi.sh
BYEDPI_REF="${BYEDPI_REF:-v0.17.3}"
if [[ ! -d "$WORK/.git" ]]; then
  git init -q "$WORK"
  git -C "$WORK" remote add origin https://github.com/hufrea/byedpi.git
fi
git -C "$WORK" fetch -q --depth 1 origin "$BYEDPI_REF"
git -C "$WORK" checkout -q FETCH_HEAD
echo "    ByeDPI: $BYEDPI_REF ($(git -C "$WORK" rev-parse --short HEAD))"
cd "$WORK"

SRC="packets.c main.c conev.c proxy.c desync.c mpool.c extend.c"
build() { # abi cc
  echo "==> $1"
  mkdir -p "$REPO/app/libs/byedpi/$1"
  "$BIN/$2$EXT" -D_DEFAULT_SOURCE -I. -std=c99 -O2 -fPIE -pie -s \
    -Wl,-z,max-page-size=16384 $SRC -o "$REPO/app/libs/byedpi/$1/libciadpi.so"
}
build arm64-v8a   aarch64-linux-android26-clang
build armeabi-v7a armv7a-linux-androideabi26-clang
build x86_64      x86_64-linux-android26-clang
build x86         i686-linux-android26-clang
echo "Готово: app/libs/byedpi/*/libciadpi.so"
