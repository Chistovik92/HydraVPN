#!/bin/bash
# Build Linux AppImage for HydraVPN Desktop
# Requires: appimagetool (https://github.com/AppImage/AppImageKit/releases)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

echo "=== Building Linux AppImage ==="

# Check for appimagetool
if ! command -v appimagetool &> /dev/null; then
    echo "ERROR: appimagetool not found. Download from https://github.com/AppImage/AppImageKit/releases"
    exit 1
fi

# Build desktop JAR
echo "Building desktop JAR..."
./gradlew :desktop:jar --no-daemon

# Create AppDir structure
APPDIR="desktop/build/AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/bin"
mkdir -p "$APPDIR/usr/lib"
mkdir -p "$APPDIR/usr/share/applications"
mkdir -p "$APPDIR/usr/share/icons/hicolor/256x256/apps"
mkdir -p "$APPDIR/usr/share/metainfo"

# Copy JAR
cp desktop/build/libs/*.jar "$APPDIR/usr/lib/hydravpn.jar"

# Create AppRun script
cat > "$APPDIR/AppRun" << 'EOF'
#!/bin/bash
SELF=$(readlink -f "$0")
HERE=${SELF%/*}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/default-java}
export PATH="$JAVA_HOME/bin:$PATH"
exec "$JAVA_HOME/bin/java" -jar "$HERE/usr/lib/hydravpn.jar" "$@"
EOF
chmod 755 "$APPDIR/AppRun"

# Create desktop entry
cat > "$APPDIR/hydravpn.desktop" << EOF
[Desktop Entry]
Name=HydraVPN
Comment=Multi-protocol VPN client
Exec=AppRun
Icon=hydravpn
Type=Application
Categories=Network;Security;
Terminal=false
EOF

# Create AppStream metadata
cat > "$APPDIR/usr/share/metainfo/hydravpn.appdata.xml" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<component type="desktop-application">
  <id>ru.gidravpn.hydra</id>
  <metadata_license>CC0-1.0</metadata_license>
  <project_license>GPL-3.0</project_license>
  <name>HydraVPN</name>
  <summary>Multi-protocol VPN client</summary>
  <description>
    <p>Multi-protocol VPN client supporting VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard, and AmneziaWG.</p>
  </description>
  <launchable type="desktop-id">hydravpn.desktop</launchable>
  <provides>
    <binary>hydravpn</binary>
  </provides>
  <releases>
    <release version="1.0.0" date="$(date +%Y-%m-%d)"/>
  </releases>
</component>
EOF

# Build AppImage
appimagetool "$APPDIR" "desktop/build/distributions/HydraVPN-1.0.0-x86_64.AppImage"

echo "AppImage created: desktop/build/distributions/HydraVPN-1.0.0-x86_64.AppImage"