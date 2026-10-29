#!/usr/bin/env bash
# Полные Git-зеркала ядер, панелей и их нод. По умолчанию только локальные bare-копии в .upstream-mirror.
# Публикация: scripts/mirror-upstreams.sh --publish [--private]. Требуется gh с правами repo.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${MIRROR_DIR:-$ROOT/.upstream-mirror}"
OWNER="${GITHUB_OWNER:-Chistovik92}"
PREFIX="${MIRROR_PREFIX:-hydra-mirror}"
PUBLISH=0
PRIVATE=1
GH="${GH:-$(command -v gh || true)}"
if [[ -z "$GH" && -f "/mnt/c/Program Files/GitHub CLI/gh.exe" ]]; then GH="/mnt/c/Program Files/GitHub CLI/gh.exe"; fi
[[ -n "$GH" ]] || { echo "Не найден gh. Укажите GH=/полный/путь/gh.exe" >&2; exit 2; }
for arg in "$@"; do case "$arg" in --publish) PUBLISH=1;; --public) PRIVATE=0;; --private) PRIVATE=1;; *) echo "Неизвестный аргумент: $arg" >&2; exit 2;; esac; done
mkdir -p "$OUT"
declare -A basenames=()
repos=(
  XTLS/Xray-core XTLS/libXray SagerNet/sing-box p1neappleXpress/OpenFlux
  amnezia-vpn/amneziawg-go amnezia-vpn/amneziawg-android amnezia-vpn/amneziawg-apple amnezia-vpn/amneziawg-tools
  openlibrecommunity/olcrtc hufrea/byedpi sagernet/gomobile
  MHSanaei/3x-ui alireza0/s-ui alireza0/x-ui PasarGuard/panel PasarGuard/scripts
  remnawave/backend remnawave/node Nexora-VPN/panel
)
for upstream in "${repos[@]}"; do basenames[${upstream#*/}]=$(( ${basenames[${upstream#*/}]:-0} + 1 )); done
# GitHub не принимает refs/pull/*, а git push по HTTPS из WSL без credential-хелпера
# виснет в ожидании пароля на закрытом пайпе. Решение: пушить с токеном gh в URL.
TOKEN="$("$GH" auth token 2>/dev/null || true)"
[[ -n "$TOKEN" ]] || { echo "Не удалось получить токен gh (gh auth login). Пуш в GitHub невозможен." >&2; exit 2; }
PUSH_URL="https://x-access-token:${TOKEN}@github.com"
for upstream in "${repos[@]}"; do
  name="$PREFIX-${upstream#*/}"
  [[ ${basenames[${upstream#*/}]} -gt 1 ]] && name="$PREFIX-${upstream//\//-}"
  dir="$OUT/$name.git"
  echo "==> $upstream → $name"
  if [[ -d "$dir" ]]; then
    git -C "$dir" remote set-url origin "https://github.com/$upstream.git"
    git -C "$dir" fetch --prune --no-tags origin '+refs/heads/*:refs/heads/*'
    git -C "$dir" fetch --prune origin '+refs/tags/*:refs/tags/*'
  else
    git clone --mirror "https://github.com/$upstream.git" "$dir"
  fi
  if [[ "$PUBLISH" == 1 ]]; then
    "$GH" repo view "$OWNER/$name" >/dev/null 2>&1 || "$GH" repo create "$OWNER/$name" "$([[ $PRIVATE == 1 ]] && echo --private || echo --public)" --description "Hydra dependency mirror of $upstream (all branches, tags and history)"
    # GitHub запрещает записывать служебные refs/pull/*, но они не относятся к
    # веткам/тегам. Отправляем полную историю веток и тегов, а не --mirror всего refs.
    git -C "$dir" push --force "$PUSH_URL/$OWNER/$name.git" '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'
  fi
done
printf '%s\n' "${repos[@]}" > "$OUT/UPSTREAMS.txt"
echo "Готово: $OUT"
