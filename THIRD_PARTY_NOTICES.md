# Third-Party Notices

Hydra распространяется под GPL-3.0 (см. [LICENSE](../LICENSE)). Ниже —
лицензии встраиваемых/поддерживаемых компонентов. **Важно:** бинарники
сторонних ядер (`.aar`/`.so`) НЕ распространяются в составе исходников —
каждый собирает их сам (docs/BUILD.md); при дистрибуции готовых APK
соблюдайте лицензии ниже.

## Нативные ядра (не в репозитории, собираются вручную)

| Компонент | Источник | Лицензия | Назначение |
|---|---|---|---|
| sing-box / libbox | github.com/SagerNet/sing-box | **GPL-3.0** | движок VLESS/VMess/Trojan/SS/Hysteria2/TUIC/WireGuard |
| Xray-core / libXray | github.com/XTLS/Xray-core | **MPL-2.0** | альтернативный движок (XTLS Vision) |
| amneziawg-go | github.com/amnezia-extensions/amneziawg-go | MIT (форк wireguard-go) | AmneziaWG 1.0/1.5/2.0 |
| wireguard-go | git.zx2c4.com/wireguard-go | MIT | референс WG-стека (входит в amneziawg-go) |
| hev-socks5-tunnel | github.com/heiyehack/hev-socks5-tunnel | MIT — сверьте upstream | tun2socks-мост для Xray/olcRTC |
| WDTT libclient (beta) | upstream WDTT | **проверить перед включением** | WG через TURN ВК |
| olcRTC (beta) | github.com/openlibrecommunity/olcrtc | WTFPL (апстрим заархивирован) | TCP over WebRTC; Android — libolcrtc.so, ПК — собирается из закреплённого коммита |
| OpenFlux (beta) | github.com/p1neappleXpress/OpenFlux | GPL-3.0 | TCP-туннель с транспортами; Android — libopenflux.so, ПК — бинарь релиза v0.3.0 (SHA-256 закреплён) |

## ByeDPI и стратегии обхода DPI (0.7.13)

| Компонент | Лицензия | Где |
|---|---|---|
| ByeDPI (`ciadpi`, github.com/hufrea/byedpi, v0.17.3) | MIT | подпроцесс на Android/Windows/Linux/macOS; статическая библиотека `ios/Packages/CiaDPI` (исходники без изменений, кроме переименования `main` флагом компиляции) |
| Список из 60 стратегий и сайты проверки (ByeByeDPI, github.com/romanvht/ByeByeDPI) | GPL-3.0 (как Hydra) | `DpiStrategies.kt` / `DpiStrategies.swift` |
| Источники geo-баз: MetaCubeX/meta-rules-dat, SagerNet/sing-geoip и sing-geosite, runetfreedom/russia-v2ray-rules-dat, v2fly/geoip и domain-list-community | данные, скачиваются приложением у источников; сами файлы в репозиторий не входят (вшитые `.srs` — из meta-rules-dat) | `data/geo` |

## Библиотеки приложения (через Gradle/Maven)

| Компонент | Лицензия |
|---|---|
| Kotlin, Kotlin Coroutines | Apache-2.0 |
| Jetpack Compose / Material 3 | Apache-2.0 |
| AndroidX (core/lifecycle/navigation/activity) | Apache-2.0 |
| Room | Apache-2.0 |
| DataStore Preferences | Apache-2.0 |
| OkHttp | Apache-2.0 |
| kotlinx.serialization | Apache-2.0 |

## Спецификации, использованные при реализации

- RFC 1661 (PPP), RFC 1332 (IPCP), RFC 1878 (IPCP DNS-опции)
- RFC 1994 (CHAP), RFC 2759 (MS-CHAPv2), RFC 3078–3079 (MPPE/ключи — GetMasterKey)
- RFC 2661 (L2TP), RFC 1624 (инкрементальные чек-суммы), RFC 1320 (MD4)
- MS-SSTP (Microsoft Open Specifications) — SSTP framing и crypto-binding

Реализация PPP-стека (vpn/ppp) написана с нуля по этим открытым
спецификациям и не содержит стороннего кода.
