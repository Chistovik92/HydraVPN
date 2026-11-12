#!/usr/bin/env bash
# Добавляет системный .desktop и значок в готовый DEB/RPM и убирает зависимость от xdg-utils.
# Использование: scripts/add-linux-shortcut.sh <package.deb|package.rpm>
set -euo pipefail
cd "$(dirname "$0")/.."
PACKAGE="$(realpath "$1")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
DESKTOP="$WORK/hydra-vpn.desktop"
ICON="$WORK/hydra-vpn.png"
cp desktop/packaging/linux/hydra-vpn.desktop "$DESKTOP"
cp desktop/build/icons/hydra.png "$ICON"

case "$PACKAGE" in
  *.deb)
    ROOT="$WORK/root"; CONTROL="$WORK/control"
    mkdir -p "$ROOT/usr/share/applications" "$ROOT/usr/share/icons/hicolor/512x512/apps" "$CONTROL"
    dpkg-deb -x "$PACKAGE" "$ROOT"
    dpkg-deb -e "$PACKAGE" "$CONTROL"
    # jpackage кладёт в payload свой .desktop и значок (/opt/hydra-vpn/lib/hydra-vpn-Hydra.desktop,
    # /opt/hydra-vpn/lib/Hydra.png). До 0.8.3-b их ставили в меню второй иконкой; убираем из payload.
    rm -f "$ROOT/opt/hydra-vpn/lib/hydra-vpn-Hydra.desktop" "$ROOT/opt/hydra-vpn/lib/Hydra.png"
    install -m 644 "$DESKTOP" "$ROOT/usr/share/applications/hydra-vpn.desktop"
    install -m 644 "$ICON" "$ROOT/usr/share/icons/hicolor/512x512/apps/hydra-vpn.png"
    sed -i -E 's/(^Depends:.*), xdg-utils/\1/; s/^Depends: xdg-utils, /Depends: /; /^Depends: xdg-utils$/d' "$CONTROL/control"
    sed -i '/^Installed-Size:/d' "$CONTROL/control"
    # В DEBIAN не должно быть пустых строк: dpkg воспринимает текст после них как второй control.
    sed -i '/^$/d' "$CONTROL/control"
    printf 'Installed-Size: %s\n' "$(du -sk "$ROOT" | cut -f1)" >> "$CONTROL/control"
    # 0.8.3-rc1: postinst чистит второй ярлык, оставшийся после обновления с 0.8.3-a и ранее.
    rm -f "$CONTROL/prerm"
    cat > "$CONTROL/postinst" <<'EOF'
#!/bin/sh
# Убрать ярлык, оставшийся от jpackage-пакетов 0.8.3-a и ранее (вторая иконка в меню).
set -e
case "$1" in
  configure)
    rm -f /usr/share/applications/hydra-vpn-Hydra.desktop \
          /usr/local/share/applications/hydra-vpn-Hydra.desktop
    if command -v update-desktop-database >/dev/null 2>&1; then
      update-desktop-database -q 2>/dev/null || true
    fi
    ;;
esac
exit 0
EOF
    chmod 0755 "$CONTROL/postinst"
    mv "$CONTROL" "$ROOT/DEBIAN"
    dpkg-deb --root-owner-group --build "$ROOT" "$PACKAGE" >/dev/null
    ;;
  *.rpm)
    ROOT="$WORK/root"
    mkdir -p "$ROOT"
    (cd "$ROOT" && rpm2cpio "$PACKAGE" | cpio -idm --quiet)
    # jpackage кладёт в payload свой .desktop и значок; до 0.8.3-b их ставили в меню второй иконкой — убираем.
    rm -f "$ROOT/opt/hydra-vpn/lib/hydra-vpn-Hydra.desktop" "$ROOT/opt/hydra-vpn/lib/Hydra.png"
    install -d -m 755 "$ROOT/usr/share/applications" "$ROOT/usr/share/icons/hicolor/512x512/apps"
    install -m 644 "$DESKTOP" "$ROOT/usr/share/applications/hydra-vpn.desktop"
    install -m 644 "$ICON" "$ROOT/usr/share/icons/hicolor/512x512/apps/hydra-vpn.png"
    VERSION="$(rpm -qp --qf '%{VERSION}' "$PACKAGE")"
    RELEASE="$(rpm -qp --qf '%{RELEASE}' "$PACKAGE")"
    ARCH="$(rpm -qp --qf '%{ARCH}' "$PACKAGE")"
    export HYDRA_RPM_ROOT="$ROOT" HYDRA_RPM_VERSION="$VERSION" HYDRA_RPM_RELEASE="$RELEASE" HYDRA_RPM_ARCH="$ARCH"
    cat > "$WORK/hydra-vpn.spec" <<'EOF'
Name: hydra-vpn
Version: %{getenv:HYDRA_RPM_VERSION}
Release: %{getenv:HYDRA_RPM_RELEASE}
Summary: Hydra
License: GPL-3.0-or-later
Group: Network
Vendor: Hydra VPN
AutoReqProv: no
BuildArch: %{getenv:HYDRA_RPM_ARCH}
%description
Hydra VPN client
%install
cp -a %{getenv:HYDRA_RPM_ROOT}/. %{buildroot}/
# 0.8.3-rc1: чистим второй ярлык, оставшийся после обновления с 0.8.3-a и ранее.
%post
rm -f /usr/share/applications/hydra-vpn-Hydra.desktop \
      /usr/local/share/applications/hydra-vpn-Hydra.desktop
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database -q 2>/dev/null || true
fi
%files
/opt
/usr/share/applications
/usr/share/icons/hicolor
/usr/share/licenses
EOF
    rpmbuild --quiet --define "_topdir $WORK/rpmbuild" --define "_binary_payload w9.gzdio" --define "_build_id_links none" -bb "$WORK/hydra-vpn.spec"
    cp "$WORK/rpmbuild/RPMS/$ARCH"/*.rpm "$PACKAGE"
    ;;
  *) echo "Ожидается DEB или RPM: $PACKAGE" >&2; exit 2 ;;
esac
