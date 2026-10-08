#!/usr/bin/env bash
#
# Сквозная проверка desktop-ядра: реальный трафик через конфиг, который строит Hydra.
#
#   scripts/desktop-e2e.sh <sing-box> <каталог-с-конфигами> <windows|linux|macos> [proxy|tun|all] [xray] [openflux]
#
# Поднимает локальный сервер sing-box (Shadowsocks 2022 на 127.0.0.1:18388) и клиент
# из desktop/build/singbox-configs/desktop-ss-<os>-<mode>.json (их пишет DesktopConfigTest),
# направленный на этот сервер. Затем:
#   proxy — curl через mixed-inbound 127.0.0.1:12080 (HTTP и SOCKS5) + delay через clash_api;
#   tun   — curl БЕЗ прокси: трафик ОС должен уйти в tun (auto_route) — проверяется по логу
#           клиента. Нужны права: root (sudo) на Linux/macOS, администратор на Windows.
# Трафик самого сервера в TUN-режиме уводится мимо туннеля правилом process_name.
#
# С пятым аргументом (путь к xray) то же самое повторяется для движка Xray: Xray-клиент
# (xray-configs/e2e-xray-<os>-<mode>.json, socks 127.0.0.1:18090 с паролем) + sing-box-мост
# (e2e-xray-<os>-<mode>.json) — трафик должен пройти tun/прокси → мост → Xray → сервер.
#
# С шестым аргументом (путь к openflux, 0.7.10) — движок OpenFlux: локальный exit-узел OpenFlux (транспорт direct,
# 127.0.0.1:18500) + клиент OpenFlux с аргументами из приложения (openflux-e2e-client.args, SOCKS5 127.0.0.1:18091)
# + sing-box-мост (e2e-openflux-<os>-<mode>.json): tun/прокси → мост → клиент → exit → интернет.
#
set -euo pipefail

SB="$1"; CFG_DIR="$2"; OS="$3"; MODES="${4:-all}"; XRAY="${5:-}"; OPENFLUX="${6:-}"
ENGINE="sing-box"   # текущий прогон: sing-box | xray | openflux
WORK="$(mktemp -d)"
trap 'kill_all; rm -rf "$WORK"' EXIT

EXE=""; [[ "$OS" == "windows" ]] && EXE=".exe"
SERVER_BIN="$WORK/sing-box-server$EXE"
cp "$SB" "$SERVER_BIN"; chmod +x "$SERVER_BIN" 2>/dev/null || true

SUDO=""
if [[ "$OS" != "windows" && "$(id -u)" != "0" ]]; then SUDO="sudo"; fi

PIDS=()
kill_all() {
  for p in "${PIDS[@]:-}"; do [[ -n "$p" ]] && { $SUDO kill "$p" 2>/dev/null || kill "$p" 2>/dev/null || true; }; done
  if [[ "$OS" == "windows" ]]; then
    taskkill //F //IM "sing-box-server.exe" >/dev/null 2>&1 || true
    taskkill //F //IM "xray.exe" >/dev/null 2>&1 || true
    taskkill //F //IM "openflux.exe" >/dev/null 2>&1 || true
  fi
  PIDS=()
  sleep 1
}

fail() { echo "::error::[$ENGINE] $*"; echo "--- client log"; tail -40 "$WORK/client.log" 2>/dev/null || true; echo "--- xray/openflux log"; tail -20 "$WORK/xray.log" 2>/dev/null || true; tail -20 "$WORK/exit.log" 2>/dev/null || true; echo "--- server log"; tail -20 "$WORK/server.log" 2>/dev/null || true; exit 1; }

cat > "$WORK/server.json" <<'EOF'
{"log":{"level":"warn"},
 "inbounds":[{"type":"shadowsocks","listen":"127.0.0.1","listen_port":18388,
   "method":"2022-blake3-aes-128-gcm","password":"AAAAAAAAAAAAAAAAAAAAAA=="}],
 "outbounds":[{"type":"direct"}]}
EOF

make_client() { # $1 = mode
  # e2e-<os>-<mode>.json пишет DesktopConfigTest («e2e configs»): тот же DesktopConfig,
  # сервер 127.0.0.1:18388, прокси :12080, clash_api :19090, в TUN — process_name → direct.
  local prefix="e2e"; [[ "$ENGINE" == "xray" ]] && prefix="e2e-xray"; [[ "$ENGINE" == "openflux" ]] && prefix="e2e-openflux"
  local src="$CFG_DIR/$prefix-$OS-$1.json"
  [[ -f "$src" ]] || fail "нет $src — сначала gradle :desktop:test"
  cp "$src" "$WORK/client.json"
  if [[ "$ENGINE" == "xray" ]]; then
    local xsrc="$CFG_DIR/../xray-configs/e2e-xray-$OS-$1.json"
    [[ -f "$xsrc" ]] || fail "нет $xsrc — сначала gradle :desktop:test"
    cp "$xsrc" "$WORK/xray.json"
  fi
}

wait_port() { # $1 = порт; ждём, пока на 127.0.0.1 начнут принимать соединения
  for _ in $(seq 1 60); do (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && return 0; sleep 0.5; done
  return 1
}

start_openflux() { # exit-узел, затем клиент с аргументами приложения
  local key="$WORK/openflux.key"
  printf '%s' 'e2e-key-0123456789abcdef' > "$key"
  "$WORK/openflux$EXE" --role exit --transports direct:100 --direct-listen 127.0.0.1:18500 --mode l4 --encryption-key-file "$key" > "$WORK/exit.log" 2>&1 &
  PIDS+=($!)
  wait_port 18500 || fail "exit-узел OpenFlux не поднялся"
  local args=()
  while IFS= read -r a || [[ -n "$a" ]]; do a="${a%$'\r'}"; [[ "$a" == "@KEYFILE@" ]] && a="$key"; args+=("$a"); done < "$CFG_DIR/../openflux-e2e-client.args"
  "$WORK/openflux$EXE" "${args[@]}" > "$WORK/xray.log" 2>&1 &
  PIDS+=($!)
  wait_port 18091 || fail "клиент OpenFlux не открыл SOCKS5"
}

start_xray() { # мост sing-box без Xray (или клиента OpenFlux) не работает — они первыми
  [[ "$ENGINE" == "openflux" ]] && { start_openflux; return 0; }
  [[ "$ENGINE" == "xray" ]] || return 0
  "$WORK/xray$EXE" run -c "$WORK/xray.json" > "$WORK/xray.log" 2>&1 &
  PIDS+=($!)
  sleep 2
  if grep -qi 'failed' "$WORK/xray.log"; then fail "Xray не поднялся"; fi
  return 0
}

proxy_tag() { if [[ "$ENGINE" == "xray" || "$ENGINE" == "openflux" ]]; then echo 'outbound/socks\[proxy\]'; else echo 'outbound/shadowsocks\[proxy\]'; fi; }

start() { # $1 = sudo-or-empty, $2 = bin, $3 = config, $4 = log
  $1 "$2" run -c "$3" -D "$WORK" > "$4" 2>&1 &
  PIDS+=($!)
}

run_proxy() {
  echo "=== $OS [$ENGINE]: режим PROXY"
  make_client proxy
  start "" "$SERVER_BIN" "$WORK/server.json" "$WORK/server.log"
  start_xray
  start "" "$SB" "$WORK/client.json" "$WORK/client.log"
  sleep 3
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 -x http://127.0.0.1:12080 https://www.gstatic.com/generate_204) || true
  [[ "$code" == "204" ]] || fail "PROXY/HTTP: ожидался 204, получено '$code'"
  echo "    HTTP-прокси: 204"
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 --socks5-hostname 127.0.0.1:12080 https://www.gstatic.com/generate_204) || true
  [[ "$code" == "204" ]] || fail "PROXY/SOCKS5: ожидался 204, получено '$code'"
  echo "    SOCKS5: 204"
  delay=$(curl -s --max-time 15 -H 'Authorization: Bearer secret' \
    'http://127.0.0.1:19090/proxies/proxy/delay?url=https%3A%2F%2Fwww.gstatic.com%2Fgenerate_204&timeout=8000' \
    | grep -o '"delay":[0-9]*' | cut -d: -f2 || true)
  [[ -n "$delay" ]] || fail "clash_api delay не ответил"
  echo "    clash_api delay: ${delay} мс"
  kill_all
}

run_tun() {
  echo "=== $OS [$ENGINE]: режим TUN"
  make_client tun
  start "" "$SERVER_BIN" "$WORK/server.json" "$WORK/server.log"
  start_xray
  start "$SUDO" "$SB" "$WORK/client.json" "$WORK/client.log"
  sleep 6
  grep -qiE 'FATAL|start inbound.*error' "$WORK/client.log" && fail "TUN: ядро не поднялось"
  ok=""
  for _ in 1 2 3; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 20 https://www.gstatic.com/generate_204) || true
    if [[ "$code" == "204" ]] && grep -q 'inbound/tun\[tun-in\].*www.gstatic.com\|inbound/tun\[tun-in\]: inbound connection to' "$WORK/client.log"; then ok=1; break; fi
    sleep 3
  done
  [[ -n "$ok" ]] || fail "TUN: трафик не прошёл через tun (curl=$code)"
  grep -q "$(proxy_tag)" "$WORK/client.log" || fail "TUN: соединение не ушло в outbound proxy"
  echo "    curl без прокси → tun-in → proxy: 204"
  kill_all
}

run_modes() {
  case "$MODES" in
    proxy) run_proxy ;;
    tun) run_tun ;;
    all) run_proxy; run_tun ;;
    *) echo "режим: proxy|tun|all" >&2; exit 2 ;;
  esac
}

run_modes
if [[ -n "$XRAY" ]]; then
  ENGINE="xray"
  cp "$XRAY" "$WORK/xray$EXE"; chmod +x "$WORK/xray$EXE" 2>/dev/null || true
  run_modes
fi
if [[ -n "$OPENFLUX" ]]; then
  ENGINE="openflux"
  cp "$OPENFLUX" "$WORK/openflux$EXE"; chmod +x "$WORK/openflux$EXE" 2>/dev/null || true
  run_modes
fi
echo "=== $OS: сквозная проверка пройдена"
