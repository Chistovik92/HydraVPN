# Оглавление docs/versions/README.md из таблицы версий docs/HISTORY.md: python scripts/docs-versions-index.py .
import os, re, sys
root = sys.argv[1]
hist = open(os.path.join(root, 'docs/HISTORY.md'), encoding='utf-8').read().split('\n')
cut = next(i for i, l in enumerate(hist) if l.startswith('## Идеи'))
rows = [l for l in hist[:cut] if re.match(r'^\| \d+\.\d+', l)]
notes = set(os.listdir(os.path.join(root, 'docs/versions')))
out = ["# Версии Hydra по порядку", "",
       "Каждая строка — одна версия: дата, суть и где читать подробности. Заметка версии (план, что сделано, как проверено) — ссылка",
       "«заметка»; для версий без заметки подробности — в [CHANGELOG](../../CHANGELOG.md) (раздел с тем же номером). Коммиты и метки —",
       "в [`HISTORY.md`](../HISTORY.md), что впереди — в [`ROADMAP.md`](../ROADMAP.md).", "",
       "| Версия | Дата | Суть | Подробно |", "|---|---|---|---|"]
for r in rows:
    c = [x.strip() for x in r.strip('|').split('|')]
    v, date, what = c[0], c[1], c[4]
    base = v
    link = None
    for cand in (v + '.md', re.sub(r'-(a|b|rc)\d*$', '', v) + '.md'):
        if cand in notes:
            link = cand; break
    more = f"[заметка]({link})" if link else "CHANGELOG"
    out.append(f"| {v} | {date} | {what} | {more} |")
out.append("")
open(os.path.join(root, 'docs/versions/README.md'), 'w', encoding='utf-8').write('\n'.join(out))
print(len(rows))
