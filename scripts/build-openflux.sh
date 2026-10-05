#!/usr/bin/env bash
#
# Сборка клиента OpenFlux (github.com/p1neappleXpress/OpenFlux, GPL-3.0) под четыре ABI (arm64-v8a, armeabi-v7a, x86_64, x86) →
# app/libs/openflux/<abi>/libopenflux.so.
#
# Это ИСПОЛНЯЕМЫЙ файл (как и в официальном OpenFluxAndroid): приложение запускает его
# подпроцессом из nativeLibraryDir в режиме `--role client --inbound socks5`.
# CGO обязателен: без него Go читает /etc/resolv.conf, которого на Android нет, и ни одно имя
# транспорта не резолвится. Флаг -checklinkname=0 — как в build_android.sh апстрима.
#
# Нужны: Go (версия из go.mod апстрима), Android NDK, git.
# Использование:  scripts/build-openflux.sh [каталог-с-клоном]
#
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

WORK="${1:-$(mktemp -d)/OpenFlux}"
NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "ОШИБКА: не найден Android NDK (задайте ANDROID_NDK_HOME)" >&2; exit 1; }
HOST="$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
EXT=""; [[ "$HOST" == windows* ]] && EXT=".cmd"

# Коммит закреплён (а не HEAD и не метка): 04.10.2026 апстрим переставил метку v0.3.0, а HEAD ушёл дальше.
# Это тот же коммит, что и у готовых бинарей на ПК (desktop/build.gradle.kts). Сменить: OPENFLUX_REF=<sha> scripts/build-openflux.sh
OPENFLUX_REF="${OPENFLUX_REF:-bb55dc35604ea11e3b611fda55dd6383f0c3c68d}"
if [[ ! -d "$WORK/.git" ]]; then
  git init -q "$WORK"
  git -C "$WORK" remote add origin https://github.com/p1neappleXpress/OpenFlux.git
fi
git -C "$WORK" fetch -q --depth 1 origin "$OPENFLUX_REF"
git -C "$WORK" checkout -q FETCH_HEAD
echo "    OpenFlux: $OPENFLUX_REF"
cd "$WORK"

export CGO_ENABLED=1 GOOS=android CGO_LDFLAGS="-Wl,-z,max-page-size=16384"
build() { # abi goarch cc
  echo "==> $1"
  mkdir -p "$REPO/app/libs/openflux/$1"
  GOARCH="$2" GOARM=7 CC="$BIN/$3$EXT" go build -trimpath -ldflags="-s -w -checklinkname=0" \
    -o "$REPO/app/libs/openflux/$1/libopenflux.so" .
}
build arm64-v8a   arm64 aarch64-linux-android26-clang
build armeabi-v7a arm   armv7a-linux-androideabi26-clang
build x86_64      amd64 x86_64-linux-android26-clang
build x86           386   i686-linux-android26-clang
echo "Готово: app/libs/openflux/*/libopenflux.so"
