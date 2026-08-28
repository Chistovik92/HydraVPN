# Архитектура

## Слои

```
┌───────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose)                                       │
│  HydraRoot → MainScreen / ServersScreen / SplitTunnelScreen │
│            / LogsScreen / SettingsScreen (+BetaBadge)       │
└───────────────┬───────────────────────────────────────────┘
                │ StateFlow
┌───────────────▼───────────────────────────────────────────┐
│  MainViewModel (AndroidViewModel)                           │
│  — выбор сервера, connect/disconnect, импорт, split tunnel  │
└───────────────┬───────────────────────────────────────────┘
                │
┌───────────────▼───────────────────────────────────────────┐
│  Data                                                       │
│  ServerRepository ── Room (ServerDao / SubscriptionDao)     │
│                   ── SubscriptionFetcher (OkHttp)           │
│                   ── LinkParser / WireGuardParser           │
│                   ── SingBoxConfigBuilder / WireGuardConfig │
│  SplitTunnelRepository ── DataStore (настройки split)       │
└───────────────┬───────────────────────────────────────────┘
                │
┌───────────────▼───────────────────────────────────────────┐
│  VPN                                                        │
│  HydraVpnService (android.net.VpnService)                   │
│    ── tun (split tunneling правила из DataStore)            │
│    ── SocketGuard (protect: SSTP-TLS / L2TP-UDP сокеты)     │
│  VpnCore (interface, по Protocol.engine)                    │
│    ├─ SingBoxCore   (native, libbox.aar; HydraPlatformInterface) │
│    ├─ XrayCore      (native, libXray.aar + tun2socks)       │
│    ├─ AmneziaWgCore (native, amneziawg-go.aar)              │
│    ├─ SstpCore / L2tpCore (native, userspace PPP)           │
│    ├─ PptpCore      (native, честный отказ GRE)             │
│    ├─ WdttCore / OlcRtcCore (native, BETA-каркасы)          │
│    └─ NoopCore      (flavor stub — симуляция)               │
│  vpn/ppp: Md4, MsChapV2, Ppp, PppSession, TunBridge (SNAT) │
│  VpnState — общая шина состояния (StateFlow)                │
└───────────────────────────────────────────────────────────┘
```

## Ключевые решения

**Product flavors `stub` / `native`.** Нативные ядра — тяжёлые Go-бинарники
(`.aar`, десятки МБ на ABI). Flavor `stub` собирается без них и симулирует
соединение — удобно для разработки UI и CI. Flavor `native` подключает
`libbox.aar` / `libXray.aar` через `nativeImplementation`. Класс
`CoreFactoryProvider` определён в обоих sourceSet'ах (`src/stub`, `src/native`);
в вариант попадает ровно один. Userspace-ядра (SSTP/L2TP/PPTP/WDTT/olcRTC/AWG)
лежат в `src/native`, но не требуют .aar — код на чистом Kotlin.

**Единый профиль сервера.** `ServerProfile` — одна таблица Room. Специфичные
поля протоколов (REALITY pbk/sid, obfs, WG-ключи, AWG-обфускация, PPP-креды)
лежат в `extra` как JSON, чтобы не плодить таблицы.

**Конфиг генерируется, а не хранится.** `SingBoxConfigBuilder` строит полный
JSON sing-box (tun-inbound + proxy-outbound + маршрутизация) из `ServerProfile`
в момент подключения. Аналогично `XrayConfigBuilder` для Xray и
`WireGuardConfigBuilder` (.conf/uapi) для amneziawg-go.

**tun принадлежит VpnService.** Дескриптор поднимается в `HydraVpnService`
и передаётся ядру. Для sing-box `PlatformInterface.openTun()` возвращает этот
fd. Для Xray/olcRTC нужен мост tun2socks. Для PPP-ядер `TunBridge` перекачивает
пакеты между tun и PPP-сессией (SNAT 172.19.0.1 ⇄ IPCP-IP + чек-суммы).

**Userspace-PPP вместо системных стеков.** L2TP/SSTP реализованы на Kotlin:
не нужен root, нет зависимости от удалённых системных стеков Android.
PPTP невозможен принципиально (GRE → raw-сокеты) — ядро сообщает об этом
честно. Transport-сокеты выносятся из-под VPN через `SocketGuard.protect()`.

**Раздельное туннелирование.** `SplitTunnelRepository` (DataStore) → правила
`addAllowed/addDisallowedApplication` в `establishTun()`. Применяется при
следующем подключении.

## Потоки данных

- **Состояние соединения:** `VpnService` пишет в `VpnState.state/logs/stats`,
  `MainViewModel` их проксирует, Compose собирает через `collectAsState()`.
- **Импорт:** deep-link/буфер → `LinkParser.parseLine` → Room; URL подписки →
  `SubscriptionFetcher.fetch` → `LinkParser.parseSubscription` → Room.
- **SSTP/L2TP:** tun → `TunBridge` (SNAT+checksums) → `PppSession` →
  transport (TLS/UDP) → сервер; обратно — тем же путём.

## Что доработать до продакшена

- Реализовать полный `PlatformInterface` под целевую версию libbox
  (reference: `SagerNet/sing-box-for-android`).
- Реальный опрос статистики через libbox `CommandClient`.
- tun2socks-мост для `XrayCore`/`OlcRtcCore` (например, `hev-socks5-tunnel`).
- On-device тесты SSTP/L2TP против SoftEther/Mikrotik (см. HANDOFF).
- Измерение пинга, авто-обновление подписок в WorkManager.
- Хранение секретов в EncryptedSharedPreferences / Keystore.
