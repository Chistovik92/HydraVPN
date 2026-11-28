#!/usr/bin/env bash
#
# Сборка нативного TrustTunnel-клиента (TrustTunnelClient, Apache-2.0) для Android:
#   app/libs/trusttunnel/<abi>/libtrusttunnel.so   (arm64-v8a, armeabi-v7a, x86_64, x86)
#
# Подпроцесс запускается из nativeLibraryDir как `trusttunnel_client --config <toml>`
# в режиме SOCKS5 (см. app/.../TrustTunnelCore.kt, shared TrustTunnelArgs).
#
# НЕ запускается в CI и здесь артефакты не создаёт: нужен полный Android-набор.
# Предпосылки (см. upstream trusttunnel platform/android/README.md):
#   Python 3.13+, CMake >= 3.24, Conan >= 2.0.5, Go >= 1.18.3, Ninja,
#   Android SDK 35+, Android NDK 29.0.14206865+, ANDROID_HOME/ANDROID_NDK_HOME.
# Бинарь называем libtrusttunnel.so, чтобы Android распаковал его в nativeLibraryDir.
#
# Проверка: ./scripts/build-trusttunnel.sh ; затем пересобрать app (native flavor).
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

WORK="${1:-$(mktemp -d)/TrustTunnelClient}"
TRUSTTUNNEL_REF="${TRUSTTUNNEL_REF:-v1.3.0}"
NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
  NDK="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "Ошибка: нет Android NDK 29+ (задайте ANDROID_NDK_HOME)" >&2; exit 1; }

command -v python3 >/dev/null || { echo "Нужен python3.13+" >&2; exit 1; }
command -v cmake   >/dev/null || { echo "Нужен cmake 3.24+" >&2; exit 1; }
command -v conan   >/dev/null || { echo "Нужен conan 2.0.5+ (pip install conan)" >&2; exit 1; }
command -v go      >/dev/null || { echo "Нужен go 1.18.3+" >&2; exit 1; }
command -v ninja   >/dev/null || { echo "Нужен ninja" >&2; exit 1; }

if [[ ! -d "$WORK/.git" ]]; then
  git init -q "$WORK"
  git -C "$WORK" remote add origin https://github.com/TrustTunnel/TrustTunnelClient.git
fi
git -C "$WORK" fetch -q --depth 1 origin "$TRUSTTUNNEL_REF"
git -C "$WORK" checkout -q FETCH_HEAD
echo "    TrustTunnelClient: $TRUSTTUNNEL_REF"

# conan-зависимости (upstream): внутри чекаута, venv
(
  cd "$WORK"
  python3 -m venv env
  # shellcheck disable=SC1091
  source env/bin/activate 2>/dev/null || source env/Scripts/activate
  pip3 install -q -r scripts/requirements.txt
  python3 scripts/bootstrap_conan_deps.py
  cd platform/android
  echo "cmake.dir=$(dirname "$(dirname "$(command -v cmake)")")" >> local.properties
  ./gradlew assembleRelease
)

# Собранные .so (arm64-v8a/armeabi-v7a/x86/x86_64) переименовываем в libtrusttunnel.so
ABIS="arm64-v8a armeabi-v7a x86_64 x86"
for ABI in $ABIS; do
  mkdir -p "$REPO/app/libs/trusttunnel/$ABI"
  FOUND="$(find "$WORK/platform/android" -path "*$ABI*" -name '*.so' 2>/dev/null | head -1 || true)"
  if [[ -n "$FOUND" ]]; then
    cp "$FOUND" "$REPO/app/libs/trusttunnel/$ABI/libtrusttunnel.so"
    echo "    -> app/libs/trusttunnel/$ABI/libtrusttunnel.so"
  else
    echo "    !! не найдено .so для $ABI (см. вывод gradle выше); положите вручную" >&2
  fi
done
echo "Готово. Пересоберите app (native flavor). Если чего-то нет — перенесите вручную."
