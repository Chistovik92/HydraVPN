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

## Исполняемые ядра и AmneziaWG по ABI

Каждое — в своём подкаталоге `app/libs/<движок>/<abi>/lib*.so` (`abi`: arm64-v8a, armeabi-v7a, x86_64, x86), тоже вне git:

| Каталог | Файл | Чем собрать |
|---|---|---|
| `awg/` | `libwg-go.so` (AmneziaWG) | `scripts/build-awg.sh` |
| `byedpi/` | `libciadpi.so` (обход DPI) | `scripts/build-byedpi.sh` |
| `olcrtc/` | `libolcrtc.so` | `scripts/build-olcrtc.sh` |
| `openflux/` | `libopenflux.so` | `scripts/build-openflux.sh` |

`scripts/release.sh` отказывается публиковать full-APK, в котором нет любого из них (с 0.8.0-rc4).
Причина: с 0.7.17 по 0.8.0-rc3 каталога `byedpi/` не было в рабочей копии, из которой собирался релиз,
и «Обход DPI» в выпущенных APK не запускался — сборка проходила молча.
