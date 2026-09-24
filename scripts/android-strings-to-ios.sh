#!/usr/bin/env bash
#
# Локализации iOS-клиента из строк Android (Фаза 12): переводы живут в одном месте —
# app/src/main/res/values*/strings*.xml, — а iOS получает Localizable.strings с теми же ключами.
#   values → en, values-ru → ru, values-uk → uk, values-fa → fa, values-zh-rCN → zh-Hans
# Форматы: %1$s → %1$@, %1$d → %1$ld; экранирование Android (\' \" \n) и XML-сущности.
#
set -euo pipefail
cd "$(dirname "$0")/.."

out_root="ios/Shared/Resources"
converter="$(mktemp)"
trap 'rm -f "$converter"' EXIT
cat > "$converter" <<'PERL'
while (<>) {
    next unless /<string name="([^"]+)">(.*)<\/string>/;
    my ($k, $v) = ($1, $2);
    $v =~ s/\\'/'/g;                 # \'  -> '
    $v =~ s/\\"/"/g;                 # \"  -> "
    $v =~ s/&lt;/</g; $v =~ s/&gt;/>/g; $v =~ s/&amp;/&/g;
    $v =~ s/%(\d+)\$s/%$1\$@/g;      # %1$s -> %1$@
    $v =~ s/%(\d+)\$d/%$1\$ld/g;     # %1$d -> %1$ld
    $v =~ s/"/\\"/g;                 # кавычки для .strings
    print "\"$k\" = \"$v\";\n";
}
PERL

for pair in "values:en" "values-ru:ru" "values-uk:uk" "values-fa:fa" "values-zh-rCN:zh-Hans"; do
  dir="${pair%%:*}"; lang="${pair##*:}"
  mkdir -p "$out_root/$lang.lproj"
  out="$out_root/$lang.lproj/Localizable.strings"
  {
    echo "/* Сгенерировано scripts/android-strings-to-ios.sh из app/src/main/res/$dir — не править вручную. */"
    cat app/src/main/res/"$dir"/strings*.xml | perl -CSD "$converter"
    echo "/* iOS-only (ios/Shared/IOSStrings) */"
    cat "ios/Shared/IOSStrings/$lang.strings"
  } > "$out"
  echo "  $lang: $(grep -c '^"' "$out") строк → $out"
done
