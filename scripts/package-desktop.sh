#!/usr/bin/env bash
#
# Пакеты Hydra Desktop для ТЕКУЩЕЙ ОС (jpackage умеет собирать только под свою
# платформу, поэтому в CI это матрица раннеров, см. .github/workflows/desktop.yml).
#
#   scripts/package-desktop.sh <windows-x64|linux-x64|linux-arm64|macos-arm64|macos-x64> [gradle]
#
# Результат — в dist/:
#   Windows: Hydra-desktop-<v>-windows-x64.msi / .exe / -portable.zip
#   Linux:   Hydra-desktop-<v>-linux-<arch>.deb / .rpm / .AppImage / .tar.gz
#   macOS:   Hydra-desktop-<v>-macos-<arch>.dmg
# Каждый пакет содержит ядро sing-box своей платформы; перед выкладкой проверяется,
# что ядро внутри и что собранное приложение запускается (дымовой тест).
#
set -euo pipefail
cd "$(dirname "$0")/.."

TARGET="$1"
GRADLE="${2:-gradle}"
VERSION="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
BIN="desktop/build/compose/binaries/main"
DIST="dist"
NAME="Hydra-desktop-$VERSION-$TARGET"
rm -rf "$DIST"; mkdir -p "$DIST"

step() { printf '\n==> %s\n' "$*"; }
err() { echo "::error::$*" >&2; exit 1; }

case "$TARGET" in
  windows-*) CORE="sing-box.exe"; APP_DIR="$BIN/app/Hydra"; LAUNCHER="$APP_DIR/Hydra.exe"; RES="$APP_DIR/app/resources" ;;
  linux-*)   CORE="sing-box";     APP_DIR="$BIN/app/Hydra"; LAUNCHER="$APP_DIR/bin/Hydra";  RES="$APP_DIR/lib/app/resources" ;;
  macos-*)   CORE="sing-box";     APP_DIR="$BIN/app/Hydra.app"; LAUNCHER="$APP_DIR/Contents/MacOS/Hydra"; RES="$APP_DIR/Contents/app/resources" ;;
  *) err "неизвестная цель $TARGET" ;;
esac

step "Сборка ($TARGET, версия $VERSION)"
case "$TARGET" in
  windows-*) "$GRADLE" :desktop:createDistributable :desktop:packageMsi :desktop:packageExe --stacktrace ;;
  linux-*)
    # DEB/RPM — jpackage из ГОТОВОГО образа (--app-image): packageDeb/packageRpm Compose
    # пересобирают образ сами и теряют бит исполнения у ядра.
    "$GRADLE" :desktop:createDistributable --stacktrace
    JPACKAGE="${JAVA_HOME:+$JAVA_HOME/bin/}jpackage"
    for type in deb rpm; do
      mkdir -p "$BIN/$type"
      "$JPACKAGE" --type "$type" --app-image "$APP_DIR" --dest "$BIN/$type" \
        --name Hydra --app-version "$VERSION" --vendor "Hydra VPN" \
        --description "Hydra VPN client" --license-file LICENSE \
        --icon desktop/build/icons/hydra.png \
        --linux-package-name hydra-vpn --linux-deb-maintainer "Hydra VPN <dev@shadowlink.local>" \
        --linux-menu-group Network --linux-app-category Network --linux-shortcut \
        --linux-rpm-license-type "GPL-3.0-or-later"
    done
    ;;
  macos-*)   "$GRADLE" :desktop:createDistributable --stacktrace ;;
esac

step "Проверка: ядро и geo-базы внутри пакета"
[[ -f "$RES/$CORE" ]] || err "в пакете нет ядра: $RES/$CORE"
[[ -d "$RES/geo/geoip" ]] || err "в пакете нет geo-баз: $RES/geo/geoip"
"$RES/$CORE" version | head -1

step "Дымовой тест: приложение запускается и живёт 20 с"
smoke_log="$(mktemp)"
case "$TARGET" in
  linux-*) HOME_SMOKE="$(mktemp -d)"; HOME="$HOME_SMOKE" XDG_CONFIG_HOME="$HOME_SMOKE/.config" xvfb-run -a "$LAUNCHER" >"$smoke_log" 2>&1 & ;;
  *) "$LAUNCHER" >"$smoke_log" 2>&1 & ;;
esac
pid=$!
sleep 20
if ! kill -0 "$pid" 2>/dev/null; then
  cat "$smoke_log"; err "приложение завершилось в первые 20 секунд"
fi
case "$TARGET" in
  windows-*) taskkill //F //IM Hydra.exe >/dev/null 2>&1 || true ;;
  *) pkill -f "$LAUNCHER" 2>/dev/null || kill "$pid" 2>/dev/null || true ;;
esac
grep -iE 'exception|error' "$smoke_log" | head -5 || true
echo "    запуск OK"

step "Сбор пакетов в $DIST/"
case "$TARGET" in
  windows-*)
    cp "$BIN"/msi/*.msi "$DIST/$NAME.msi"
    cp "$BIN"/exe/*.exe "$DIST/$NAME.exe"
    if command -v 7z >/dev/null 2>&1; then
      (cd "$BIN/app" && 7z a -tzip -mx=7 "$OLDPWD/$DIST/$NAME-portable.zip" Hydra >/dev/null)
    else
      powershell -NoProfile -Command "Compress-Archive -Path '$(cygpath -w "$APP_DIR")' -DestinationPath '$(cygpath -w "$PWD/$DIST/$NAME-portable.zip")' -Force"
    fi
    ;;
  linux-*)
    cp "$BIN"/deb/*.deb "$DIST/$NAME.deb"
    dpkg-deb -c "$DIST/$NAME.deb" | grep 'resources/sing-box' | sed 's/^/    deb: /'
    dpkg-deb -c "$DIST/$NAME.deb" | grep 'resources/sing-box$' | grep -q '^-rwx' \
      || err "в DEB ядро без бита исполнения"
    rpm -qplv "$DIST/$NAME.rpm" | grep 'resources/sing-box$' | grep -q '^-rwx' \
      || err "в RPM ядро без бита исполнения"
    cp "$BIN"/rpm/*.rpm "$DIST/$NAME.rpm"
    tar -C "$BIN/app" -czf "$DIST/$NAME.tar.gz" Hydra

    arch="x86_64"; [[ "$TARGET" == "linux-arm64" ]] && arch="aarch64"
    appdir="$(mktemp -d)/Hydra.AppDir"
    mkdir -p "$appdir"
    cp -a "$APP_DIR" "$appdir/Hydra"
    cp desktop/build/icons/hydra.png "$appdir/hydra.png"
    cat > "$appdir/hydra.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Hydra
Comment=Hydra VPN client
Exec=Hydra
Icon=hydra
Categories=Network;
Terminal=false
EOF
    cat > "$appdir/AppRun" <<'EOF'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/Hydra/bin/Hydra" "$@"
EOF
    chmod +x "$appdir/AppRun"
    tool="$(mktemp -d)/appimagetool"
    curl -fsSL -o "$tool" "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$arch.AppImage"
    chmod +x "$tool"
    ARCH="$arch" APPIMAGE_EXTRACT_AND_RUN=1 "$tool" --no-appstream "$appdir" "$DIST/$NAME.AppImage"
    ;;
  macos-*)
    # Без Apple Developer ID: ad-hoc подпись (обязательна для arm64) + DMG.
    codesign --force --deep --sign - "$APP_DIR"
    codesign --verify --deep --strict "$APP_DIR"
    stage="$(mktemp -d)"
    cp -R "$APP_DIR" "$stage/"
    ln -s /Applications "$stage/Applications"
    hdiutil create -volname "Hydra $VERSION" -srcfolder "$stage" -ov -format UDZO "$DIST/$NAME.dmg"
    ;;
esac

ls -la "$DIST"
