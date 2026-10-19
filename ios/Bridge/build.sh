#!/usr/bin/env bash
#
# Libbox.xcframework для iOS (0.7.10): libbox sing-box + клиенты olcRTC и OpenFlux ОДНОЙ сборкой gomobile.
#
# Зачем одной: расширение VPN на iOS — один процесс без подпроцессов, а две gomobile-библиотеки (у каждой
# свой рантайм Go) в один бинарь не линкуются. Поэтому sing-box, olcRTC (пакет hydraolc) и OpenFlux (hydraflux)
# собираются вместе; Swift видит прежний модуль `Libbox` плюс классы Hydraolc*/Hydraflux*.
#
# Флаги и теги — как у cmd/internal/build_libbox sing-box для `-target apple -platform ios`; плюс
# -checklinkname=0 (зависимости olcRTC и OpenFlux ссылаются на внутренности net — так собирают их апстримы).
#
# Нужны: macOS с Xcode, Go (версия из go.mod), git. Использование: ios/Bridge/build.sh [каталог-вывода]
#
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-$PWD}"

SING_BOX_VERSION="${SING_BOX_VERSION:-1.12.25}"
# Тот же коммит OpenFlux, что у ПК и Android (scripts/build-openflux.sh, desktop/build.gradle.kts).
OPENFLUX_REF="${OPENFLUX_REF:-f12e981629a18c8c01007d59ac002254c02972bb}"

# OpenFlux подключён через replace (модуль `mobile` объявлен как openflux-mobile) — нужен клон коммита.
if [[ ! -d third_party/OpenFlux/.git ]]; then
  rm -rf third_party/OpenFlux
  git init -q third_party/OpenFlux
  git -C third_party/OpenFlux remote add origin https://github.com/p1neappleXpress/OpenFlux.git
fi
git -C third_party/OpenFlux fetch -q --depth 1 origin "$OPENFLUX_REF"
git -C third_party/OpenFlux checkout -q --force FETCH_HEAD
echo "    OpenFlux: $OPENFLUX_REF"

# sing-box — тоже клоном (go.mod: replace → third_party/sing-box) с правкой REALITY (0.8.2-b, SagerNet/sing-box#4520):
# без неё sing-box не подключается к серверам Xray-core >= 26.9.8 (3x-ui 3.10), а Xray на iOS нет.
rm -rf third_party/sing-box
git -c advice.detachedHead=false clone -q --depth 1 --branch "v$SING_BOX_VERSION" https://github.com/SagerNet/sing-box third_party/sing-box
git -C third_party/sing-box apply "$PWD/../../scripts/sing-box-reality-mlkem.patch"
echo "    sing-box: v$SING_BOX_VERSION + scripts/sing-box-reality-mlkem.patch"

go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.8
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.8
export PATH="$PATH:$(go env GOPATH)/bin"
gomobile init

TAGS="with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_conntrack,with_dhcp,with_low_memory"
gomobile bind -v -target ios -libname=box -trimpath -buildvcs=false \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=$SING_BOX_VERSION -s -w -buildid= -checklinkname=0" \
  -tags "$TAGS" -o "$OUT/Libbox.xcframework" \
  github.com/sagernet/sing-box/experimental/libbox ./hydraolc ./hydraflux
echo "Готово: $OUT/Libbox.xcframework"
