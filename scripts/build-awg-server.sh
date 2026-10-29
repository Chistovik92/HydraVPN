#!/usr/bin/env bash
#
# Серверная сборка AmneziaWG для «Моих серверов» (0.8.0): статические amneziawg-go, awg и awg-quick
# под linux amd64 / arm64 / armv7 -> dist/awg-server/<arch>/{amneziawg-go,awg,awg-quick} + dist/awg-server/SHA256SUMS.
#
# Нужны: Go (>= 1.25), git, make, gcc; для arm64/armv7 — gcc-aarch64-linux-gnu и gcc-arm-linux-gnueabihf.
# Использование:  scripts/build-awg-server.sh [amd64 arm64 armv7]
# Версии закреплены ниже; сменить: AWG_GO_VERSION=v3.1.2026XXXX AWG_TOOLS_REF=<тег или коммит> scripts/build-awg-server.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
OUT="$ROOT/dist/awg-server"
WORK="${WORK:-$(mktemp -d)}"
AWG_GO_VERSION="${AWG_GO_VERSION:-v3.1.20260828}"
# Текущий стабильный выпуск v3.1.20260812 (раньше здесь был подвижный master).
AWG_TOOLS_REF="${AWG_TOOLS_REF:-ee0f0a9aa34ff0a0da4b3433b9512781cfe02843}"
ARCHS=("$@"); [[ ${#ARCHS[@]} -gt 0 ]] || ARCHS=(amd64 arm64 armv7)

git clone --depth 1 --branch "$AWG_GO_VERSION" https://github.com/amnezia-vpn/amneziawg-go.git "$WORK/go" 2>/dev/null \
  || git clone --depth 1 https://github.com/amnezia-vpn/amneziawg-go.git "$WORK/go"
git clone --depth 1 --branch "$AWG_TOOLS_REF" https://github.com/amnezia-vpn/amneziawg-tools.git "$WORK/tools" 2>/dev/null \
  || { git clone https://github.com/amnezia-vpn/amneziawg-tools.git "$WORK/tools" \
    && git -C "$WORK/tools" checkout --detach "$AWG_TOOLS_REF"; }
echo "    amneziawg-go: $(git -C "$WORK/go" describe --tags --always)  tools: $(git -C "$WORK/tools" rev-parse --short HEAD)"

for arch in "${ARCHS[@]}"; do
  echo "==> $arch"
  case "$arch" in
    amd64) GOARCH=amd64; GOARM=;  CC=gcc ;;
    arm64) GOARCH=arm64; GOARM=;  CC=aarch64-linux-gnu-gcc ;;
    armv7) GOARCH=arm;   GOARM=7; CC=arm-linux-gnueabihf-gcc ;;
    *) echo "неизвестная архитектура $arch" >&2; exit 1 ;;
  esac
  d="$OUT/$arch"; mkdir -p "$d"
  ( cd "$WORK/go" && CGO_ENABLED=0 GOOS=linux GOARCH=$GOARCH GOARM=$GOARM \
      go build -trimpath -ldflags "-s -w -buildid=" -o "$d/amneziawg-go" . )
  ( cd "$WORK/tools/src" && make clean >/dev/null 2>&1 || true
    make -j"$(nproc)" CC="$CC" LDFLAGS="-static" wg >/dev/null
    cp wg "$d/awg"
    [[ "$arch" == amd64 ]] && strip "$d/awg" || true )
  cp "$WORK/tools/src/wg-quick/linux.bash" "$d/awg-quick"
  chmod 755 "$d"/*
done

( cd "$OUT" && find . -type f ! -name SHA256SUMS | sort | xargs sha256sum > SHA256SUMS )
echo "Готово: $OUT"; cat "$OUT/SHA256SUMS"

# Пакеты для релиза: Hydra-awg-server-<версия>-<arch>.zip + .sha256 (их качает приложение, 0.8.0)
VERSION="${VERSION:-$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)}"
REL="$ROOT/dist/awg-release"; rm -rf "$REL"; mkdir -p "$REL"
for arch in "${ARCHS[@]}"; do
  z="$REL/Hydra-awg-server-$VERSION-$arch.zip"
  ( cd "$OUT/$arch" && zip -q -X -j "$z" amneziawg-go awg awg-quick )
  ( cd "$REL" && sha256sum "$(basename "$z")" > "$(basename "$z").sha256" )
done
echo "Пакеты релиза: $REL"; ls -l "$REL"
