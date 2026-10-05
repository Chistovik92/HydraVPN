#!/usr/bin/env bash
#
# Сверка закреплённых версий компонентов Hydra с последними у апстрима.
# Ничего не меняет — только печатает таблицу «у нас / последняя / статус». Запускать перед каждым релизом.
#
# Нужен gh (GitHub CLI) с доступом в сеть. Использование:  scripts/check-updates.sh
#
set -uo pipefail
cd "$(dirname "$0")/.."

GH="$(command -v gh || true)"
[[ -n "$GH" ]] || { echo "ОШИБКА: не найден gh (GitHub CLI)" >&2; exit 2; }

# --- что закреплено у нас --------------------------------------------------------------------------------------
pin() { sed -n "$2" "$1" | head -1; }
SINGBOX="$(pin desktop/build.gradle.kts 's/^val singBoxVersion = "\([^"]*\)".*/\1/p')"
XRAY="$(pin desktop/build.gradle.kts 's/^val xrayVersion = "\([^"]*\)".*/\1/p')"
OPENFLUX="$(pin desktop/build.gradle.kts 's/^val openFluxVersion = "\([^"]*\)".*/\1/p')"
OLCRTC="$(pin desktop/build.gradle.kts 's/^val olcRtcCommit = "\([^"]*\)".*/\1/p')"
AWG="$(pin scripts/build-awg.sh 's/^AWG_GO_VERSION="${AWG_GO_VERSION:-\([^}]*\)}".*/\1/p')"
CI_SINGBOX="$(pin .github/workflows/android.yml 's/.*SING_BOX_VERSION: "\([^"]*\)".*/\1/p')"

latest_release() { "$GH" release view -R "$1" --json tagName --jq .tagName 2>/dev/null | tr -d '\r'; }
latest_tag()     { "$GH" api "repos/$1/tags" --jq '.[0].name' 2>/dev/null | tr -d '\r'; }
latest_commit()  { "$GH" api "repos/$1/commits?per_page=1" --jq '.[0].sha' 2>/dev/null | tr -d '\r'; }
# Последний патч той же ветки (1.12.x), если пин — не последняя ветка.
latest_in_branch() { # repo prefix
  "$GH" api "repos/$1/tags?per_page=100" --paginate --jq '.[].name' 2>/dev/null | tr -d '\r' \
    | grep -E "^v?$2\.[0-9]+$" | sed 's/^v//' | sort -V | tail -1
}

row() { # имя  наше  последнее  [примечание]
  local status="актуально"
  [[ -z "$3" ]] && status="не удалось узнать"
  [[ -n "$3" && "${2#v}" != "${3#v}" ]] && status="ЕСТЬ ОБНОВЛЕНИЕ"
  printf '%-22s %-24s %-24s %s %s\n' "$1" "${2:0:24}" "${3:0:24}" "$status" "${4:-}"
}

printf '%-22s %-24s %-24s %s\n' "компонент" "у нас" "последняя" "статус"
printf '%s\n' "------------------------------------------------------------------------------------------------"

SB_LATEST_STABLE="$(latest_release SagerNet/sing-box | sed 's/^v//')"
SB_BRANCH="${SINGBOX%.*}"
SB_BRANCH_LATEST="$(latest_in_branch SagerNet/sing-box "$SB_BRANCH")"
row "sing-box (ветка $SB_BRANCH)" "$SINGBOX" "$SB_BRANCH_LATEST"
row "sing-box (последняя)" "$SINGBOX" "$SB_LATEST_STABLE" "← смена ветки = перенос libbox, см. ROADMAP 0.8.4"
[[ "$CI_SINGBOX" == "$SINGBOX" ]] || echo "  !! CI (android.yml) закрепил sing-box $CI_SINGBOX, а ПК — $SINGBOX"
row "Xray-core" "$XRAY" "$(latest_release XTLS/Xray-core | sed 's/^v//')"
XRAY_PRE="$("$GH" release list -R XTLS/Xray-core --limit 1 --json tagName,isPrerelease --jq '.[0]|select(.isPrerelease)|.tagName' 2>/dev/null | tr -d '')"
[[ -n "$XRAY_PRE" ]] && echo "  (у Xray-core есть более новый ПРЕ-релиз $XRAY_PRE — на стабильную ветку не переходим, пока он не станет релизом)"
row "amneziawg-go" "$AWG" "$(latest_tag amnezia-vpn/amneziawg-go)"
row "OpenFlux (клиент)" "v$OPENFLUX" "$(latest_release p1neappleXpress/OpenFlux)"
OLC_LATEST="$(latest_commit openlibrecommunity/olcrtc)"
row "olcRTC (коммит)" "${OLCRTC:0:12}" "${OLC_LATEST:0:12}" "(апстрим заархивирован)"

echo
printf '%-22s %s\n' "панели (для каталога)" "последняя"
for r in MHSanaei/3x-ui alireza0/s-ui alireza0/x-ui PasarGuard/panel remnawave/panel Nexora-VPN/panel; do
  printf '%-22s %s\n' "${r#*/}" "$(latest_release "$r")"
done
echo
echo "Проверьте также: amneziawg-android (клиент AWG) — $(latest_release amnezia-vpn/amneziawg-android); libXray — $(latest_release XTLS/libXray)"
