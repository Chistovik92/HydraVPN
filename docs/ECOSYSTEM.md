# Экосистема: протоколы, апстримы и клиенты

Актуально на **19.09.2026**. Здесь — что за проекты стоят за «нестандартными» движками Hydra,
в каком они состоянии и что из этого мы используем. Сверять с апстримом перед каждым
обновлением ядра: часть проектов меняется быстро.

## olcRTC — TCP поверх WebRTC (Jitsi / Телемост / WB Stream)

| | |
|---|---|
| Апстрим | [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc), WTFPL, Go |
| Статус | **Архивирован 14.09.2026** (read-only). Автор: проект «будет переработан и станет частью другого моего проекта»; PR и issues не принимаются, но клиент «остаётся рабочим» |
| Преемник | [owenewans/snolc](https://github.com/owenewans/snolc) — «в существенно другом виде»; готового olcrtc-модуля в `snolc-modules` пока нет |
| Клиенты | [alananisimov/olcbox](https://github.com/alananisimov/olcbox) (KMP/Compose, MIT, alpha; встраивает olcrtc; deeplink `olcbox://add?url=<URL>`; провайдеры Jazz, Telemost, WB Stream, Jitsi) · [owenewans/owenclave](https://github.com/owenewans/owenclave) (Android, форк exclave: olcrtc + подписки) |
| Формат ссылки | `olcrtc://<Provider>?<Transport>[<k=v&…>]@<RoomID>#<Key>$<MIMO>` (docs/uri.md апстрима, «URI v1») |
| В Hydra | `OlcRtcCore`: клиент `cmd/olcrtc` собран в `libolcrtc.so` (`scripts/build-olcrtc.sh`), запуск подпроцессом, SOCKS5 → sing-box → tun. Ссылки `olcrtc://` импортируются (в т.ч. QR), `olcbox://add?url=` — как подписка |
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
| Клиенты | [p1neappleXpress/OpenFluxAndroid](https://github.com/p1neappleXpress/OpenFluxAndroid) — запускает тот же бинарь подпроцессом (`--role client --inbound socks5`) — именно так сделано у нас · macOS (utun) · iOS (Network Extension, TestFlight) · Linux/Windows (SOCKS5) |
| Формат ссылки | у апстрима нет. В Hydra — **своё соглашение** `openflux://<transport>?url=…&maxToken=…&maxUid=…&codec=…&key=…#имя` (`OpenFluxLink`) |
| В Hydra | `OpenFluxCore` (`libopenflux.so`, `scripts/build-openflux.sh`), BETA |
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
