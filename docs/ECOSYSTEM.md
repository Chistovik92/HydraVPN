# Экосистема: протоколы, апстримы и клиенты

Актуально на **05.10.2026** (аудит движков — первая таблица; остальные разделы — на 19.09.2026). Здесь — что за проекты стоят за «нестандартными» движками Hydra,
в каком они состоянии и что из этого мы используем. Сверять с апстримом перед каждым
обновлением ядра: часть проектов меняется быстро.

## Аудит движков (05.10.2026)

Закреплённые версии сверяет `scripts/check-updates.sh` (запускать перед каждым релизом).

| Движок | Где | Версия в 0.7.8 | Последняя у апстрима | Решение |
|---|---|---|---|---|
| sing-box | Android (libbox.aar), iOS (Libbox.xcframework), ПК (процесс) | **1.12.25** (было 1.12.9) | 1.14.2 (1.15 — альфы) | Патч ветки 1.12 — сразу. Ветка 1.13+ меняет API libbox (сервис через gRPC-`CommandServer`, `BoxService` убран; aar 49 → 85 МБ): перенос — отдельный этап 0.8.4. Все 15 Android- и 94 ПК-конфига уже принимает `sing-box check` 1.14.2 |
| Xray-core | Android (libXray.aar), ПК (процесс) | **26.9.30** везде (ПК было 26.3.27) | 26.9.30 (пре-релиз по метке апстрима; стабильный 26.3.27) | Android уже собран из этого коммита (`b26a91d`); ПК выровнен, все 20 Xray-конфигов проходят `xray run -test`. Панели выдают настройки под свежий Xray, поэтому стабильный (отставание полгода) не берём |
| AmneziaWG | Android (`libwg-go.so`) | amneziawg-go **v3.1.20260828** (клиент `amneziawg-android` тянул v3.1.20260814) | v3.1.20260828 | `scripts/build-awg.sh` закрепляет версию ядра. Профили 1.0 / 1.5 / 2.0 / 3.x — `AwgParams`. На ПК и iOS AWG пока нет (0.8.x) |
| olcRTC | Android, ПК | коммит `f3ad8fb7c0d0` | он же (апстрим заархивирован) | актуально |
| OpenFlux | Android, ПК | клиент v0.3.0 | v0.3.0 (есть узел node-v1.2.0 и ночная сборка) | актуально; узел ставится на сервер отдельно |
| SSTP / L2TP / PPTP | Android | собственный код (`PppSession` и др.) | — | нет апстрима; проверка на живом сервере — TODO №2 |
| WDTT, snolc | — | не интегрируются | см. ниже | без изменений |

Кандидаты в новые движки (после переноса на libbox 1.13+/1.14, 0.8.4): NaiveProxy (1.13), OpenVPN и OpenConnect (клиент, 1.14),
Snell (1.14). До переноса их нет, и мы их не обещаем.

## Meridian (разбор 05.10.2026)

[meridian-android](https://github.com/JinComputers/meridian-android) и
[meridian-desktop-releases](https://github.com/JinComputers/meridian-desktop-releases) — гибридный клиент (Kotlin + Go-движок),
доступ по ключу от Telegram-бота. **Лицензии нет** (приложение закрыто, открыты сборки, Go-движок и несколько документов), поэтому
код не берём — только идеи, реализуем своими силами. Что именно — таблица в `docs/ROADMAP.md`, раздел «Разбор Meridian».

## Панели управления (05.10.2026)

Версии для каталога установщика (этап 0.8.1+, `docs/ROADMAP.md`): 3x-ui v3.9.0 (встроен AmneziaWG на userspace-стеке; безвопросная
установка `XUI_NONINTERACTIVE=1`, итог в `/etc/x-ui/install-result.env`), s-ui v1.6.3, x-ui (alireza0) v1.12.0, PasarGuard v5.4.1,
Remnawave 3.4.4, Nexora v0.0.2 (исходники закрыты, лицензии нет — только установка и вход через браузер). Marzban — последний релиз
01.2025, не предлагаем.

## olcRTC — TCP поверх WebRTC (Jitsi / Телемост / WB Stream)

| | |
|---|---|
| Апстрим | [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc), WTFPL, Go |
| Статус | **Архивирован 14.09.2026** (read-only). Автор: проект «будет переработан и станет частью другого моего проекта»; PR и issues не принимаются, но клиент «остаётся рабочим» |
| Преемник | [owenewans/snolc](https://github.com/owenewans/snolc) — «в существенно другом виде»; готового olcrtc-модуля в `snolc-modules` пока нет |
| Клиенты | [alananisimov/olcbox](https://github.com/alananisimov/olcbox) (KMP/Compose, MIT, alpha; встраивает olcrtc; deeplink `olcbox://add?url=<URL>`; провайдеры Jazz, Telemost, WB Stream, Jitsi) · [owenewans/owenclave](https://github.com/owenewans/owenclave) (Android, форк exclave: olcrtc + подписки) |
| Формат ссылки | `olcrtc://<Provider>?<Transport>[<k=v&…>]@<RoomID>#<Key>$<MIMO>` (docs/uri.md апстрима, «URI v1») |
| В Hydra | **ПК (0.7.4):** тот же клиент, собранный из закреплённого коммита, запуск подпроцессом (`Sidecar` в `CoreRunner`). Android — `OlcRtcCore`: клиент `cmd/olcrtc` собран в `libolcrtc.so` (`scripts/build-olcrtc.sh`), запуск подпроцессом, SOCKS5 → sing-box → tun. Ссылки `olcrtc://` импортируются (в т.ч. QR), `olcbox://add?url=` — как подписка |
| Риск | Апстрим заморожен: чинить его нельзя, а Jitsi/Телемост/WB могут менять протоколы. Собираем из последнего состояния; следим за snolc |

## snolc — модульный сетевой движок (Rust)

[owenewans/snolc](https://github.com/owenewans/snolc) (Unlicense, Rust 1.98.1, v0.0.x): цепочка
`adapter → smoltcp → policy → yamux → protection → carrier`, модули — нативные плагины
(`snolc-modules`: adapter-socks5/http-connect/tun, protection-noise/dummy, carrier-tcp/ssh,
policy-local/dummy), установщик `snolpkg`, клиент `snolcNG` (десктоп + Android).
**В Hydra не интегрирован:** нет olcrtc-совместимого carrier, модули грузятся в процесс как
нативные библиотеки (вопрос доверия и сборки под Android), конфиг — `snolc.toml`. Пересмотреть,
когда появится carrier для видеозвонков и стабильный Android-клиент.

## OpenFlux — TCP-туннель с подключаемыми транспортами

| | |
|---|---|
| Апстрим | [p1neappleXpress/OpenFlux](https://github.com/p1neappleXpress/OpenFlux), GPL-3.0, Go; активен (~1,7 тыс. звёзд) |
| Транспорты | `yandex` (Яндекс.Документы, WS), `vyandex` (Volga: HTTP-релей + WS), `oneme` (MAX, WebRTC DataChannel; `--maxToken`, `--maxUid`), `cupsonline` (Centrifugo-комнаты), `mailru` (Mail.ru Документы) |
| Узел выхода | `--role exit --mode l3` (raw SNAT/DNAT, Linux + root) или `--mode l4` (gVisor, любая ОС); режим выбирает узел, не клиент |
| Опции | кодек `batched` (zstd) / `legacy` (LZ4), шифрование AES-256-GCM (`--encryption-key-file`) |
| Клиенты | Официальные (09.10.2026, v2.3.2, GPL-3.0, код UI — от meepo161/OpenFluxClient): [OpenFluxAndroid](https://github.com/p1neappleXpress/OpenFluxAndroid) — ядро gomobile-библиотекой, [OpenFluxDesktop](https://github.com/p1neappleXpress/OpenFluxDesktop) — ядро подпроцессом; общий модуль OpenFluxClientShared. Умеют: сессии из нескольких транспортов с автопереключением, проверку SmartCaptcha во встроенном браузере, мастер «Своя нода» / «Без сервера», ссылки и QR `openflux://v1/`, JS-транспорты (эксперимент). iOS — бета в TestFlight: <https://testflight.apple.com/join/BwnAcdus> (см. docs/IOS_TESTING.md) |
| Формат ссылки | **Официальный с ядра 0.4.x:** `openflux://v1/<base64url(DEFLATE(JSON))>` (`docs/links.md` апстрима; имя, ключ, контекст, кодек, режим, список транспортов), читает и строит только ядро. В Hydra до 0.7.10 — своё соглашение `openflux://<transport>?url=…&key=…#имя`; переход на официальный — план 0.7.11, блок B (старые ссылки остаются читаемыми) |
| В Hydra | Android — `OpenFluxCore` (`libopenflux.so`, `scripts/build-openflux.sh`), BETA. **ПК (0.7.4)** — готовый бинарь релиза v0.3.0, подпроцесс + SOCKS5 → sing-box; транспорт `direct` (свой exit-узел) проверен вживую |
| Риск | Транспорты — чужие сервисы, которые не задумывались как канал данных: могут в любой момент закрыть такой доступ. Автор снимает с себя ответственность за применение — у пользователя ответственность за соблюдение правил сервисов |

## AmneziaWG

[amnezia-vpn/amneziawg-go](https://github.com/amnezia-vpn/amneziawg-go) (ядро, Go) и
[amnezia-vpn/amneziawg-android](https://github.com/amnezia-vpn/amneziawg-android) (Android-клиент
и `tunnel/tools/libwg-go` — готовая C-shared сборка с JNI). В Hydra — `libwg-go.so`
(`scripts/build-awg.sh`). Ключи UAPI сверены по `device/uapi.go`.

## WDTT — WireGuard поверх TURN ВК (не интегрируется)

[amurcanov/proxy-turn-vk-android](https://github.com/amurcanov/proxy-turn-vk-android) (GPL-3.0),
**заархивирован 11.09.2026**, преемник — [amurcanov/csqtt](https://github.com/amurcanov/csqtt).
Клиент получает TURN-учётки через звонок VK и **автоматически решает VK Smart Captcha**
(`captcha_v2*.go`). Это обход защиты стороннего сервиса от ботов — Hydra такого функционала не
включает. Возможный безопасный вариант на будущее: капчу проходит сам пользователь в WebView,
без автоматического решателя (отдельная задача, не сделана).
