#!/usr/bin/env bash
#
# Ядро sing-box для Android: libbox.aar из исходников sing-box закреплённой версии + scripts/sing-box-reality-mlkem.patch
# → app/libs/libbox.aar. Так же собирает CI (.github/workflows/android.yml) и iOS (ios/Bridge/build.sh).
#
# Правка (0.8.2-b, SagerNet/sing-box#4520): без неё sing-box не подключается по REALITY к Xray-core >= 26.9.8 (3x-ui 3.10).
# Рядом кладётся app/libs/libbox.aar.patch — SHA-256 наложенного патча; release.sh не выпустит APK, если метки нет или она
# не совпадает с текущим патчем (иначе в выпуск уедет libbox без правки).
#
# Нужны: Go (сам скачает go1.25.5 — на 1.24/1.26 линковка Android падает, docs/BUILD.md 2.1), Android NDK, git.
# Использование:  scripts/build-libbox.sh [каталог-для-исходников]
#
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SING_BOX_VERSION="$(sed -n 's/^val singBoxVersion = "\([^"]*\)".*/\1/p' "$REPO/desktop/build.gradle.kts" | head -1)"
PATCH="$REPO/scripts/sing-box-reality-mlkem.patch"
WORK="${1:-$(mktemp -d)}/sing-box"

NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "ОШИБКА: не найден Android NDK (задайте ANDROID_NDK_HOME)" >&2; exit 1; }
export ANDROID_NDK_HOME="$NDK"
export GOTOOLCHAIN="${GOTOOLCHAIN:-go1.25.5}"

rm -rf "$WORK"
git -c advice.detachedHead=false clone -q --depth 1 --branch "v$SING_BOX_VERSION" https://github.com/SagerNet/sing-box "$WORK"
git -C "$WORK" apply "$PATCH"
echo "==> sing-box v$SING_BOX_VERSION + $(basename "$PATCH"), $(go version)"

go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.8
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.8
GB="$(go env GOPATH)/bin"
export PATH="$GB:$PATH"
cd "$WORK"
gomobile init
TAGS="with_gvisor,with_quic,with_utls,with_clash_api,with_wireguard,with_conntrack"
gomobile bind -v -androidapi 21 -javapkg=io.nekohasekai -libname=box \
  -tags "$TAGS" -trimpath -buildvcs=false \
  -ldflags="-X github.com/sagernet/sing-box/constant.Version=$SING_BOX_VERSION -s -w -buildid= -checklinkname=0" \
  -o "$REPO/app/libs/libbox.aar" ./experimental/libbox
tr -d '\r' < "$PATCH" | sha256sum | cut -d' ' -f1 > "$REPO/app/libs/libbox.aar.patch"
echo "==> app/libs/libbox.aar (sing-box $SING_BOX_VERSION, патч $(cut -c1-12 "$REPO/app/libs/libbox.aar.patch")…)"
