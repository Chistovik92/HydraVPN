#!/usr/bin/env bash
# Реальная установка собранного DEB/RPM в Ubuntu CI: чистая установка → ярлык → запуск → обновление.
# Не заменяет проверку на Simply Linux, но воспроизводит путь системного пакетного менеджера.
set -euo pipefail
cd "$(dirname "$0")/.."
TYPE="${1:-}"
[[ "$TYPE" == deb || "$TYPE" == rpm ]] || { echo "Использование: $0 deb|rpm" >&2; exit 2; }
[[ "${EUID:-$(id -u)}" == 0 ]] || SUDO=sudo
SUDO="${SUDO:-}"

package="$(find dist -maxdepth 1 -name "Hydra-desktop-*-linux-*.$TYPE" -print -quit)"
[[ -n "$package" ]] || { echo "Пакет .$TYPE не найден в dist/" >&2; exit 1; }
package="$(realpath "$package")"

install_pkg() {
  if [[ "$TYPE" == deb ]]; then
    $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -q "$1"
  else
    $SUDO rpm -Uvh --replacepkgs "$1"
  fi
}
remove_pkg() {
  if [[ "$TYPE" == deb ]]; then $SUDO apt-get remove -y -q hydra-vpn; else $SUDO rpm -e hydra-vpn; fi
}

smoke() {
  local stage="$1" home log pid
  home="$(mktemp -d)"; log="$(mktemp)"
  HOME="$home" XDG_CONFIG_HOME="$home/.config" xvfb-run -a /opt/hydra-vpn/bin/Hydra >"$log" 2>&1 & pid=$!
  sleep 20
  if ! kill -0 "$pid" 2>/dev/null; then cat "$log"; echo "Hydra не запустилась после $stage" >&2; exit 1; fi
  pkill -f '/opt/hydra-vpn/bin/Hydra' 2>/dev/null || kill "$pid" 2>/dev/null || true
  grep -iE 'exception|error' "$log" | head -5 || true
  rm -rf "$home" "$log"
}
check_shortcut() {
  test -f /usr/share/applications/hydra-vpn.desktop
  test -f /usr/share/icons/hicolor/512x512/apps/hydra-vpn.png
  grep -Fx 'Exec=/opt/hydra-vpn/bin/Hydra' /usr/share/applications/hydra-vpn.desktop >/dev/null
}

trap 'remove_pkg >/dev/null 2>&1 || true' EXIT
install_pkg "$package"
check_shortcut
smoke "чистой установки $TYPE"
install_pkg "$package"
check_shortcut
smoke "обновления $TYPE"
remove_pkg
trap - EXIT
! test -e /usr/share/applications/hydra-vpn.desktop || { echo "Ярлык остался после удаления" >&2; exit 1; }
! test -e /usr/share/icons/hicolor/512x512/apps/hydra-vpn.png || { echo "Значок остался после удаления" >&2; exit 1; }
echo "$TYPE: чистая установка, обновление, запуск и удаление ярлыка — OK"
