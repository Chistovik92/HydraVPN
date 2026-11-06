#!/usr/bin/env bash
# Проверка содержимого DEB/RPM без установки в систему. Вызывается упаковкой и Linux CI.
# Использование: scripts/check-linux-packages.sh <file.deb> <file.rpm> <expected-version>
set -euo pipefail

DEB="$1"
RPM="$2"
EXPECTED="${3:-}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "::error::$*" >&2; exit 1; }

[[ -f "$DEB" && -f "$RPM" ]] || fail "DEB/RPM не найдены"
command -v dpkg-deb >/dev/null || fail "нужен dpkg-deb"
command -v rpm >/dev/null || fail "нужен rpm"

check_desktop() {
  local file="$1" root="$2"
  local desktop="$root/usr/share/applications/hydra-vpn.desktop"
  local icon="$root/usr/share/icons/hicolor/512x512/apps/hydra-vpn.png"
  local launcher="$root/opt/hydra-vpn/bin/Hydra"
  [[ -f "$desktop" ]] || fail "в $file нет /usr/share/applications/hydra-vpn.desktop"
  [[ -f "$icon" ]] || fail "в $file нет системного значка Hydra"
  [[ -x "$launcher" ]] || fail "в $file нет исполняемого /opt/hydra-vpn/bin/Hydra"
  grep -Fx 'Exec=/opt/hydra-vpn/bin/Hydra' "$desktop" >/dev/null || fail "$file: неправильный Exec в ярлыке"
  grep -Fx 'Icon=hydra-vpn' "$desktop" >/dev/null || fail "$file: неправильный Icon в ярлыке"
  grep -Fx 'Terminal=false' "$desktop" >/dev/null || fail "$file: ярлык запускается в терминале"
  for bin in sing-box xray openflux olcrtc; do
    [[ -x "$root/opt/hydra-vpn/lib/app/resources/$bin" ]] || fail "$file: $bin отсутствует или не исполняем"
  done
}

mkdir -p "$WORK/deb" "$WORK/rpm"
dpkg-deb -x "$DEB" "$WORK/deb"
rpm -qpl "$RPM" | sed -n 's#^/usr/share/applications/hydra-vpn.desktop$#OK#p' | grep -Fx OK >/dev/null \
  || fail "в RPM нет системного .desktop"
deb_abs="$(cd "$(dirname "$DEB")" && pwd)/$(basename "$DEB")"
rpm_abs="$(cd "$(dirname "$RPM")" && pwd)/$(basename "$RPM")"
DEB="$deb_abs"
RPM="$rpm_abs"
if command -v rpm2cpio >/dev/null && command -v cpio >/dev/null; then
  (cd "$WORK/rpm" && rpm2cpio "$RPM" | cpio -idm --quiet)
else
  fail "для распаковки RPM нужны rpm2cpio и cpio (пакет cpio в Ubuntu)"
fi
check_desktop "$DEB" "$WORK/deb"
check_desktop "$RPM" "$WORK/rpm"

if [[ -n "$EXPECTED" ]]; then
  deb_version="$(dpkg-deb -f "$DEB" Version)"
  rpm_version="$(rpm -qp --qf '%{VERSION}-%{RELEASE}' "$RPM")"
  [[ "$deb_version" == "$EXPECTED-1" ]] || fail "версия DEB $deb_version, ожидалась $EXPECTED-1"
  [[ "$rpm_version" == "$EXPECTED-1" ]] || fail "версия RPM $rpm_version, ожидалась $EXPECTED-1"
fi

# Ни установка, ни удаление не должны зависеть от xdg-desktop-menu/xdg-utils.
mkdir -p "$WORK/control"
dpkg-deb -e "$DEB" "$WORK/control"
! grep -R -E 'xdg-(desktop-menu|utils)' "$WORK/control" >/dev/null || fail "DEB сохранил зависимость от xdg-utils"
! rpm -qpR "$RPM" | grep -E 'xdg-(desktop-menu|utils)' >/dev/null || fail "RPM сохранил зависимость от xdg-utils"
! rpm -qp --scripts "$RPM" | grep 'xdg-desktop-menu' >/dev/null || fail "RPM сохранил xdg-desktop-menu в scriptlet"
! rpm -qpl "$RPM" | grep -Fx '/' >/dev/null || fail "RPM ошибочно включает корневой каталог /"

printf 'DEB/RPM: ярлык, значок, launcher, ядра и версии — OK\n'
