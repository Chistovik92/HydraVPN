#!/usr/bin/env bash
# Единый запуск живых тестов панелей. По умолчанию только показывает сценарии;
# реальные команды выполняются после явного выбора сценария и HYDRA_SSH_TEST.
# Запуск из Windows: bash scripts/live-panels.sh ...
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
  cat <<'EOF'
Использование:
  scripts/live-panels.sh --list
  scripts/live-panels.sh --dry-run <сценарий...>
  scripts/live-panels.sh [--continue-on-error] <сценарий...|all>

Сценарии: xui, xui-connections, panels, xui-alireza, sui, users-xui, users-sui,
          pasarguard, remnawave, nodes-xui, nodes-pg, nodes-rw.

Обязательно для реального запуска:
  HYDRA_SSH_TEST='host:port:user:password'
  HYDRA_XRAY=/путь/к/xray HYDRA_SINGBOX=/путь/к/sing-box (для трафика)
Для nodes-* дополнительно HYDRA_NODE_SSH='host:port:user:password'.

Тесты меняют и обычно очищают выбранные серверы. Не запускайте на рабочем VPS.
EOF
}

list_suites() {
  cat <<'EOF'
xui               установка 3x-ui, подписка, базовые подключения (панель сохраняется)
xui-connections   все подключения 3x-ui на панели, оставленной xui
panels            последовательно 3x-ui, s-ui, x-ui, вход/HTTPS/туннель/очистка
xui-alireza       все подключения x-ui (alireza0)
sui               все подключения s-ui
users-xui         пользователи, подписка и восстановление базы x-ui
users-sui         пользователи, подписка и восстановление базы s-ui
pasarguard        PasarGuard: установка, подключения, трафик, удаление
remnawave         Remnawave: установка, подключения, трафик, удаление
nodes-xui         нода 3x-ui на втором сервере
nodes-pg          нода PasarGuard на втором сервере
nodes-rw          нода Remnawave на втором сервере
EOF
}

dry_run=0
continue_on_error=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --list) list_suites; exit 0 ;;
    --dry-run) dry_run=1; shift ;;
    --continue-on-error) continue_on_error=1; shift ;;
    --) shift; break ;;
    -*) echo "Неизвестный параметр: $1" >&2; usage >&2; exit 2 ;;
    *) break ;;
  esac
done

[[ $# -gt 0 ]] || { usage; exit 0; }
suites=("$@")
[[ " ${suites[*]} " == *" all "* ]] && suites=(xui xui-connections panels xui-alireza sui users-xui users-sui pasarguard remnawave nodes-xui nodes-pg nodes-rw)

if [[ $dry_run -eq 0 && -z ${HYDRA_SSH_TEST:-} ]]; then
  echo "Задайте HYDRA_SSH_TEST (или сначала проверьте команду через --dry-run)" >&2
  exit 2
fi

if [[ -x ./gradlew ]]; then
  gradle=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  gradle=(gradle)
else
  echo "Не найден ./gradlew или gradle" >&2
  exit 2
fi

passed=()
failed=()
run_suite() {
  local name="$1" class="$2"
  shift 2
  local -a overrides=("$@")
  local printable=""
  local item
  for item in "${overrides[@]}"; do printable+="$item "; done
  printf '\n==> %s: %s%s\n' "$name" "$printable" "${gradle[*]} :desktop:test --tests *.${class}"
  if [[ $dry_run -eq 1 ]]; then return 0; fi
  if env "${overrides[@]}" "${gradle[@]}" :desktop:test --tests "*.${class}"; then
    passed+=("$name")
  else
    failed+=("$name")
    [[ $continue_on_error -eq 1 ]] || return 1
  fi
}

for suite in "${suites[@]}"; do
  case "$suite" in
    xui) run_suite xui XuiLiveTest HYDRA_XUI_LIVE=1 HYDRA_XUI_KEEP=1 || exit $? ;;
    xui-connections) run_suite xui-connections XuiConnectionsLiveTest HYDRA_XUI_CONN_LIVE=1 || exit $? ;;
    panels) run_suite panels OtherPanelsTest HYDRA_PANELS_LIVE=1 || exit $? ;;
    xui-alireza) run_suite xui-alireza XuiAlirezaConnectionsLiveTest HYDRA_XUIA_LIVE=1 HYDRA_PANEL=XUI || exit $? ;;
    sui) run_suite sui XuiAlirezaConnectionsLiveTest HYDRA_XUIA_LIVE=1 HYDRA_PANEL=SUI || exit $? ;;
    users-xui) run_suite users-xui OtherPanelUsersLiveTest HYDRA_XUIA_LIVE=1 HYDRA_PANEL=XUI || exit $? ;;
    users-sui) run_suite users-sui OtherPanelUsersLiveTest HYDRA_XUIA_LIVE=1 HYDRA_PANEL=SUI || exit $? ;;
    pasarguard) run_suite pasarguard PasarGuardLiveTest HYDRA_PG_LIVE=1 HYDRA_PANEL=PG || exit $? ;;
    remnawave) run_suite remnawave PasarGuardLiveTest HYDRA_PG_LIVE=1 HYDRA_PANEL=RW || exit $? ;;
    nodes-xui|nodes-pg|nodes-rw)
      if [[ $dry_run -eq 0 && -z ${HYDRA_NODE_SSH:-} ]]; then
        echo "$suite: задайте HYDRA_NODE_SSH" >&2
        exit 2
      fi
      panel=${suite#nodes-}; panel=${panel^^}
      run_suite "$suite" PanelNodesLiveTest "HYDRA_NODE_PANEL=$panel" || exit $?
      ;;
    *) echo "Неизвестный сценарий: $suite (см. --list)" >&2; exit 2 ;;
  esac
done

printf '\nЖивые тесты завершены.\n'
((${#passed[@]})) && printf 'OK: %s\n' "${passed[*]}"
((${#failed[@]})) && { printf 'FAILED: %s\n' "${failed[*]}" >&2; exit 1; }
exit 0
