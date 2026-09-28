#!/bin/bash
# Build Linux deb package for HydraVPN Desktop
# Requires: dpkg-deb (usually pre-installed on Debian/Ubuntu)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

echo "=== Building Linux deb Package ==="

# Build desktop JAR
echo "Building desktop JAR..."
./gradlew :desktop:jar --no-daemon

# Create package structure
PKG_NAME="hydravpn"
PKG_VERSION="1.0.0"
PKG_ARCH="amd64"
STAGING_DIR="desktop/build/deb-staging"
PKG_DIR="$STAGING_DIR/${PKG_NAME}_${PKG_VERSION}_${PKG_ARCH}"

rm -rf "$STAGING_DIR"
mkdir -p "$PKG_DIR/DEBIAN"
mkdir -p "$PKG_DIR/opt/hydravpn"
mkdir -p "$PKG_DIR/usr/share/applications"
mkdir -p "$PKG_DIR/usr/share/icons/hicolor/256x256/apps"
mkdir -p "$PKG_DIR/etc/systemd/system"

# Copy JAR
cp desktop/build/libs/*.jar "$PKG_DIR/opt/hydravpn/hydravpn.jar"

# Create control file
cat > "$PKG_DIR/DEBIAN/control" << EOF
Package: ${PKG_NAME}
Version: ${PKG_VERSION}
Section: net
Priority: optional
Architecture: ${PKG_ARCH}
Depends: default-jre | java-runtime
Maintainer: HydraVPN <support@gidravpn.ru>
Description: HydraVPN Desktop Client
 Multi-protocol VPN client for Linux.
 Supports VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard, AmneziaWG.
EOF

# Create desktop entry
cat > "$PKG_DIR/usr/share/applications/hydravpn.desktop" << EOF
[Desktop Entry]
Name=HydraVPN
Comment=Multi-protocol VPN client
Exec=/usr/bin/java -jar /opt/hydravpn/hydravpn.jar
Icon=/usr/share/icons/hicolor/256x256/apps/hydravpn.png
Type=Application
Categories=Network;Security;
Terminal=false
EOF

# Create systemd service
cat > "$PKG_DIR/etc/systemd/system/hydravpn.service" << 'EOF'
[Unit]
Description=HydraVPN TUN Interface
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/java -jar /opt/hydravpn/hydravpn.jar
Restart=on-failure
RestartSec=5
CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_RAW CAP_NET_BIND_SERVICE
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW CAP_NET_BIND_SERVICE
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/dev/net/tun /var/lib/hydravpn

[Install]
WantedBy=multi-user.target
EOF

# Create postinst script
cat > "$PKG_DIR/DEBIAN/postinst" << 'EOF'
#!/bin/bash
set -e

# Create hydravpn user if not exists
if ! id -u hydravpn > /dev/null 2>&1; then
    useradd -r -s /bin/false hydravpn
fi

# Set permissions
chown -R hydravpn:hydravpn /opt/hydravpn
chmod 755 /opt/hydravpn/hydravpn.jar

# Reload systemd
if command -v systemctl &> /dev/null; then
    systemctl daemon-reload
fi

exit 0
EOF
chmod 755 "$PKG_DIR/DEBIAN/postinst"

# Build package
dpkg-deb --build "$PKG_DIR" "desktop/build/distributions/${PKG_NAME}_${PKG_VERSION}_${PKG_ARCH}.deb"

echo "deb package created: desktop/build/distributions/${PKG_NAME}_${PKG_VERSION}_${PKG_ARCH}.deb"