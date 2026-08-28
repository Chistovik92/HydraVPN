# HANDOFF — состояние проекта Hydra

Документ для продолжения работы (в т.ч. под другим аккаунтом/у другого разработчика).
Приложите к нему архив `Hydra.zip` — он самодостаточен и содержит весь код, git-историю
и документацию.

## Что это

**Hydra** — мультипротокольный VPN-клиент для Android.
- Пакет / appId: `ru.gidravpn.hydra`
- Стек: Kotlin + Jetpack Compose, minSdk 26, compileSdk 35
- Лицензия: **GPL-3.0** (`LICENSE`), сторонние компоненты — `THIRD_PARTY_NOTICES.md`
- Сайт: https://gidravpn.ru · Telegram: https://t.me/+WWJFBZVhxBs4ZmNi
- Текущая версия: **0.5.1** (`app/build.gradle.kts` → `versionName`)
- Флейворы сборки: `stub` (симуляция, без нативных `.aar`, собирается и в CI) и
  `native` (реальные ядра, требует `.aar`/`.so`).

## Быстрый старт

```bash
# stub-сборка (без нативных ядер, для UI/логики/CI):
./gradlew :app:assembleStubDebug
# native-сборка — сначала положите .aar/.so (см. docs/BUILD.md), затем:
./gradlew :app:assembleNativeRelease
```
`gradle-wrapper.jar` в архив не кладётся (бинарник) — CI генерирует его сам
(`.github/workflows/android.yml`), локально: `gradle wrapper`.

## Статус по сервисам (полностью — в `docs/SERVICES.md`)

| Сервис | Движок | Статус |
|---|---|---|
| VLESS/VMess/Trojan/SS/Hysteria2/TUIC/WireGuard | sing-box | нужен `libbox.aar`; конфиг под схему 1.12+; `HydraPlatformInterface` расширен |
| AmneziaWG 1.0/1.5/2.0 | amneziawg-go | нужен `amneziawg-go.aar`; генерация `.conf`/uapi готова |
| Xray (альт. ядро) | Xray-core | нужен `libXray.aar` + tun2socks (`hev-socks5-tunnel`); `XrayCore` — каркас |
| **SSTP** | userspace PPP/TLS | **готово на Kotlin** (LCP/PAP/MS-CHAPv2/IPCP + crypto-binding); нужен on-device тест |
| **L2TP** | userspace PPP/UDP | **готово на Kotlin** (без IPsec/ESP); нужен on-device тест |
| PPTP | — | честно недоступно (данные в GRE → нужен root; стек удалён из Android 12/13) |
| **WDTT** (beta) | нативный `libclient.so` | WG через TURN ВК; нужен нативный клиент + VK-auth |
| **olcRTC** (beta) | gomobile + tun2socks | TCP-over-WebRTC; нужен `olcrtc.aar` + tun2socks |

Ознакомительные/экспериментальные (WDTT, olcRTC) помечены в UI плашкой **BETA-доступ**
(`Protocol.beta = true`, компонент `BetaBadge`).

## Открытые задачи (TODO)

1. **Собрать нативные ядра** и положить как `.aar`/`.so` — `docs/BUILD.md`:
   `libbox.aar` (sing-box), `libXray.aar` (Xray), `amneziawg-go.aar` (AWG),
   `olcrtc.aar` (gomobile), `libclient.so` (WDTT). В оффлайн-среде это не делалось.
2. **On-device проверка SSTP/L2TP**: согласование PPP, MS-CHAPv2, IPCP, SNAT/чек-суммы
   в `TunBridge`, SSTP crypto-binding против реального SoftEther/Mikrotik.
3. **sing-box PlatformInterface**: доопределить версионно-зависимые методы под
   конкретную версию libbox (возвращающие libbox-типы: `getInterfaces`,
   `startDefaultInterfaceMonitor`, `readWIFIState`, `systemCertificates`).
   Референс: SagerNet/sing-box-for-android → `PlatformInterfaceWrapper`.
4. **Xray**: мост tun2socks (`hev-socks5-tunnel`) + доводка `streamSettings`
   (reality/ws/grpc) в `XrayConfigBuilder`.
5. **olcRTC**: gomobile-биндинг (`cnc` → локальный SOCKS5) + tun2socks в `OlcRtcCore`.
6. **WDTT**: JNI к `libclient.so` + поток VK-авторизации (WebView) в `WdttCore`;
   проверить лицензию upstream перед включением бинарника.
7. (Опц.) UI-переключатель движка Xray↔sing-box для vless/vmess.
8. (Опц.) статистика sing-box через `CommandClient` (`SingBoxRuntime`, сейчас каркас).

## Карта кода

```
app/src/main/java/ru/gidravpn/hydra/
  data/model/         Protocol.kt (все протоколы + флаг beta), ServerProfile, SplitTunnel
  data/subscription/  LinkParser, WireGuardParser/ConfigBuilder, SingBoxConfigBuilder
  data/repository/     ServerRepository
  vpn/                HydraVpnService, SocketGuard, VpnState
  vpn/ppp/            Md4, MsChapV2, Ppp, PppSession, TunBridge  (общий userspace-PPP)
  vpn/core/           VpnCore (интерфейс)
  ui/                 экраны (Main/Servers/Settings/SplitTunnel/Logs), components/Common (BetaBadge)
app/src/native/.../vpn/core/   SingBoxCore(+Runtime), XrayCore(+ConfigBuilder),
                               AmneziaWgCore, SstpCore, L2tpCore(+Transport), PptpCore,
                               WdttCore, OlcRtcCore, NativeCoreFactory
app/src/stub/.../vpn/core/     StubCore (NoopCore — симуляция для stub/CI)
docs/   PROTOCOLS · SECURITY · BUILD · ARCHITECTURE · SERVICES · PANELS · CONTRIBUTING
CHANGELOG.md   — детальный лог по версиям 0.1.0 → 0.5.1 (главный источник контекста)
```

## Честные оговорки

- Сборка/тесты в исходной среде не выполнялись (нет сети и Android SDK) — правки
  проверялись статически. Пункты «готово на Kotlin» (SSTP, L2TP, PPP-стек) не требуют
  нативных `.aar`, но нуждаются в проверке на устройстве.
- Бинарники/код сторонних проектов не распространяются — поддержаны форматы ссылок и
  точки интеграции. Лицензии: см. `THIRD_PARTY_NOTICES.md`.
- Самый подробный контекст «что и почему» — в `CHANGELOG.md` и `docs/PROTOCOLS.md`.
