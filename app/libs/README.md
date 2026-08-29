# Нативные ядра

Сюда кладутся `.aar`, собранные через gomobile (см. ../../docs/BUILD.md):

- `libbox.aar`  — sing-box (SagerNet/sing-box, experimental/libbox)
- `libXray.aar` — Xray-core (XTLS/libXray)

Файлы **не коммитятся** (.gitignore) — они большие, и мы не распространяем
чужие бинарники (см. `THIRD_PARTY_NOTICES.md`). Flavor `stub` собирается без них.

> **Не путайте «файла нет в git» с «его нельзя собрать».** `libbox.aar` обычно
> лежит здесь локально, и `assembleNativeDebug` собирается за считанные секунды.
> Прежде чем решить, что native-сборка невозможна, просто посмотрите `ls app/libs/`.
> Ровно на этом в 0.6.0 релиз чуть не уехал со stub-сборкой — см.
> `docs/HANDOFF.md`, «Честные оговорки».
