#!/usr/bin/env bash
#
# Ядро Xray для Android: готовый libXray.aar из релиза апстрима (github.com/XTLS/libXray, MPL-2.0) → app/libs/libXray.aar.
#
# С 0.8.2-b берём официальную сборку вместо своей `gomobile bind` (docs/BUILD.md, раздел 2.2): классы (`libXray.LibXray`,
# `DialerController`) побайтно те же, все четыре ABI (arm64-v8a, armeabi-v7a, x86_64, x86), сегменты выровнены по 16 КБ
# (Android 15+), а символы отладки вырезаны — AAR 58 МБ вместо 99. Внутри — Xray-core того же коммита, что и у ПК
# (desktop/build.gradle.kts, xrayVersion). Хеш архива закреплён: метки апстрима бывают перезалиты (урок OpenFlux 0.7.10).
#
# Нужны: curl (или gh), unzip, sha256sum. Использование:  scripts/fetch-libxray.sh
#
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

LIBXRAY_VERSION="26.10.10"
LIBXRAY_SHA256="aad01c82eb82e98702838f7b4ac3f33dba1ae5ff4102538e5ca7f05bbab57437"
# Коммит Xray-core внутри (go.mod libXray v26.10.10 = метка Xray-core v26.10.10). release.sh сверяет его с app/libs/libXray.aar.
LIBXRAY_XRAY_COMMIT="701af60772cd"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ZIP="$WORK/libxray-android.zip"
URL="https://github.com/XTLS/libXray/releases/download/v$LIBXRAY_VERSION/libxray-android.zip"
echo "==> $URL"
curl -fL --retry 4 --retry-delay 3 -o "$ZIP" "$URL"

actual="$(sha256sum "$ZIP" | cut -d' ' -f1)"
[[ "$actual" == "$LIBXRAY_SHA256" ]] || { echo "ОШИБКА: SHA-256 libxray-android.zip $actual, ожидался $LIBXRAY_SHA256" >&2; exit 1; }

unzip -q -o "$ZIP" -d "$WORK"
AAR="$WORK/libxray-android/libXray.aar"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  # grep -c, а не -q: -q закрывает канал раньше конца, unzip получает SIGPIPE, и pipefail считает это провалом.
  unzip -p "$AAR" "jni/$abi/libgojni.so" | grep -ac "xtls/xray-core.*$LIBXRAY_XRAY_COMMIT" >/dev/null \
    || { echo "ОШИБКА: в jni/$abi/libgojni.so нет Xray-core $LIBXRAY_XRAY_COMMIT" >&2; exit 1; }
done

mkdir -p "$REPO/app/libs"
cp "$AAR" "$REPO/app/libs/libXray.aar"
echo "==> app/libs/libXray.aar: libXray v$LIBXRAY_VERSION (Xray-core $LIBXRAY_XRAY_COMMIT), $(du -h "$REPO/app/libs/libXray.aar" | cut -f1)"
