# Живые тесты панелей и AWG ARM

## Единый запуск панелей

`scripts/live-panels.sh` собирает существующие живые тесты `:desktop` в один сценарий.
Без параметров и с `--dry-run` он не подключается к серверам и ничего не меняет:

```bash
scripts/live-panels.sh --list
scripts/live-panels.sh --dry-run xui xui-connections users-sui
```

Для реального прогона нужен выделенный тестовый Linux-сервер с SSH и JDK 17 на машине запуска:

```bash
export HYDRA_SSH_TEST='192.0.2.10:22:root:пароль'
export HYDRA_XRAY="$PWD/dist/xray/xray"
export HYDRA_SINGBOX="$PWD/dist/sing-box/sing-box"
scripts/live-panels.sh xui xui-connections
```

`xui` оставляет 3x-ui для следующего `xui-connections`. Тесты `panels`, `xui-alireza`,
`sui`, `users-*`, `pasarguard` и `remnawave` сами ставят и обычно удаляют свои панели.
`--continue-on-error` продолжает остальные сценарии и в конце возвращает ошибку;
`all` запускает полный набор.

Тесты нод требуют второй свободный сервер:

```bash
export HYDRA_NODE_SSH='192.0.2.11:22:root:пароль'
scripts/live-panels.sh nodes-xui nodes-pg nodes-rw
```

> Тесты выполняют установку/удаление служб, меняют firewall и очищают сервер.
> Не используйте рабочий VPS или сервер с нужными панелями. Публичный IP не нужен для
> локального стенда; ACME/Let's Encrypt на приватном стенде не проверяется.
> Пути к ядрам нужны для проверок трафика; отдельные тесты могут требовать Xray обязательно.

## AWG arm64/armv7 в QEMU

Workflow `.github/workflows/awg-qemu.yml` собирает серверные пакеты AmneziaWG с теми же
закреплёнными версиями, что и `scripts/build-awg-server.sh`, проверяет ELF-архитектуру и
запускает `amneziawg-go --version` и `awg --version` через `qemu-user-static`.
Собранные ZIP и SHA-256 сохраняются как артефакты. Это smoke-тест исполняемых файлов,
не тест VPN-туннеля: для handshake и трафика по-прежнему нужен ARM-сервер или отдельный стенд.

Локально на Ubuntu повторяются те же шаги:

```bash
sudo apt-get install build-essential gcc-aarch64-linux-gnu gcc-arm-linux-gnueabihf qemu-user-static file zip
scripts/build-awg-server.sh arm64 armv7
qemu-aarch64-static dist/awg-server/arm64/amneziawg-go --version
qemu-arm-static dist/awg-server/armv7/amneziawg-go --version
```
