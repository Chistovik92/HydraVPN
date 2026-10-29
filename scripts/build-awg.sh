#!/usr/bin/env bash
#
# Сборка libwg-go.so (amneziawg-go) под четыре ABI (arm64-v8a, armeabi-v7a, x86_64, x86) → app/libs/awg/<abi>/libwg-go.so.
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
HYDRA_ROOT="$PWD"

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

# Коммит amneziawg-android закреплён (0.8.2-b): раньше клонировался HEAD. 5c16489 — выпуск v3.1.20260814, последний коммит,
# менявший tunnel/tools/libwg-go (на 16.10.2026). Сменить: AWG_ANDROID_REF=<sha> scripts/build-awg.sh
AWG_ANDROID_REF="${AWG_ANDROID_REF:-5c16489e2cd9ed3a0a7a27c7445bba5238132f86}"
if [[ ! -d "$WORK/.git" ]]; then
  # submodule'и (wg-tools, elf-cleaner) для libwg-go не нужны — без --recurse-submodules
  git init -q "$WORK"
  git -C "$WORK" remote add origin https://github.com/amnezia-vpn/amneziawg-android.git
fi
git -C "$WORK" fetch -q --depth 1 origin "$AWG_ANDROID_REF"
git -C "$WORK" checkout -q --force FETCH_HEAD
echo "    amneziawg-android: $AWG_ANDROID_REF"
cd "$WORK/tunnel/tools/libwg-go"

# Версия ядра amneziawg-go закреплена здесь, а не берётся из go.mod клиента: клиент отстаёт от ядра
# (0.7.8: клиент тянул v3.1.20260814, а в v3.1.20260828 — исправление «underload при DisableCookies»).
# Сменить: AWG_GO_VERSION=v3.1.2026XXXX scripts/build-awg.sh
AWG_GO_VERSION="${AWG_GO_VERSION:-v3.1.20260828}"
go get "github.com/amnezia-vpn/amneziawg-go/v3@$AWG_GO_VERSION"
echo "    amneziawg-go: $AWG_GO_VERSION"

# Исправление ошибки upstream (0.7.8): при сбое IpcSet libwg-go закрывал tun-fd вручную, а device.Close() закрывал его ещё раз —
# на переиспользованном номере fdsan убивал процесс (воспроизведено на эмуляторе с некорректным ключом пира). См. патч.
PATCH="$HYDRA_ROOT/scripts/awg-libwg-go-fd.patch"
if git -C "$WORK" apply --ignore-whitespace --reverse --check "$PATCH" 2>/dev/null; then
  echo "    патч libwg-go уже применён"
else
  git -C "$WORK" apply --ignore-whitespace "$PATCH" || {
    echo "ОШИБКА: патч libwg-go не лёг — апстрим изменил api-android.go; обновите scripts/awg-libwg-go-fd.patch" >&2; exit 1; }
  echo "    патч libwg-go применён"
fi

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
  # 0.8.2-b: в выпусках до 0.8.2-a4 оказался v3.1.20260814 вместо закреплённого — сверяем версию ядра в самом файле.
  grep -ac "amneziawg-go/v3.$AWG_GO_VERSION" "out/$abi/libwg-go.so" >/dev/null     || { echo "ОШИБКА: в $abi/libwg-go.so не amneziawg-go $AWG_GO_VERSION" >&2; exit 1; }
  mkdir -p "$HYDRA_ROOT/app/libs/awg/$abi"
  cp "out/$abi/libwg-go.so" "$HYDRA_ROOT/app/libs/awg/$abi/"
}
build arm64-v8a   arm64 aarch64-linux-android26-clang
build armeabi-v7a arm   armv7a-linux-androideabi26-clang 7
build x86_64      amd64 x86_64-linux-android26-clang
build x86           386   i686-linux-android26-clang
echo "Готово: app/libs/awg/*/libwg-go.so"
