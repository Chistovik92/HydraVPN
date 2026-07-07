# Архитектура

## Слои

```
┌───────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose)                                       │
│  ShadowLinkRoot → MainScreen / ServersScreen / LogsScreen / │
│                   SettingsScreen                            │
└───────────────┬───────────────────────────────────────────┘
                │ StateFlow
┌───────────────▼───────────────────────────────────────────┐
│  MainViewModel (AndroidViewModel)                           │
│  — выбор сервера, connect/disconnect, импорт                │
└───────────────┬───────────────────────────────────────────┘
                │
┌───────────────▼───────────────────────────────────────────┐
│  Data                                                       │
│  ServerRepository ── Room (ServerDao / SubscriptionDao)     │
│                   ── SubscriptionFetcher (OkHttp)           │
│                   ── LinkParser / SingBoxConfigBuilder      │
└───────────────┬───────────────────────────────────────────┘
                │
┌───────────────▼───────────────────────────────────────────┐
│  VPN                                                        │
│  ShadowLinkVpnService (android.net.VpnService)             │
│    ── устанавливает tun, отдаёт fd в VpnCore                │
│  VpnCore (interface)                                        │
│    ├─ SingBoxCore  (flavor native, libbox.aar)             │
│    ├─ XrayCore     (flavor native, libXray.aar)            │
│    └─ NoopCore     (flavor stub)                            │
│  Ikev2Connector (VpnManager) — семейство L2TP/IPsec        │
│  VpnState — общая шина состояния (StateFlow)               │
└───────────────────────────────────────────────────────────┘
```

## Ключевые решения

**Product flavors `stub` / `native`.** Нативные ядра — тяжёлые Go-бинарники
(`.aar`, десятки МБ на ABI). Flavor `stub` собирается без них и симулирует
соединение — удобно для разработки UI и CI. Flavor `native` подключает
`libbox.aar` / `libXray.aar` через `nativeImplementation`. Класс
`CoreFactoryProvider` определён в обоих sourceSet'ах (`src/stub`, `src/native`);
в вариант попадает ровно один.

**Единый профиль сервера.** `ServerProfile` — одна таблица Room. Специфичные
поля протоколов (REALITY pbk/sid, obfs, congestion control и т.п.) лежат в
`extra` как JSON, чтобы не плодить таблицы.

**Конфиг генерируется, а не хранится.** `SingBoxConfigBuilder` строит полный
JSON sing-box (tun-inbound + proxy-outbound + маршрутизация) из `ServerProfile`
в момент подключения. Аналогично `XrayConfigBuilder` для Xray.

**tun принадлежит VpnService.** Дескриптор поднимается в `ShadowLinkVpnService`
и передаётся ядру. Для sing-box `PlatformInterface.openTun()` возвращает этот fd.
Для Xray нужен мост tun2socks (Xray сам tun не обслуживает).

**L2TP → IKEv2.** Проксирование пакетов через наш tun не подходит для IPsec:
IKEv2 на Android — системный (`VpnManager`/`Ikev2VpnProfile`, API 33+), поэтому
`Ikev2Connector` — отдельная ветка, не использующая `ShadowLinkVpnService`.

## Потоки данных

- **Состояние соединения:** `VpnService` пишет в `VpnState.state/logs/stats`,
  `MainViewModel` их проксирует, Compose собирает через `collectAsState()`.
- **Импорт:** deep-link/буфер → `LinkParser.parseLine` → Room; URL подписки →
  `SubscriptionFetcher.fetch` → `LinkParser.parseSubscription` → Room.

## Что доработать до продакшена

- Реализовать полный `PlatformInterface` под целевую версию libbox
  (refernce: `SagerNet/sing-box-for-android`).
- Реальный опрос статистики через libbox `CommandClient`.
- tun2socks-мост для `XrayCore` (например, `hev-socks5-tunnel`).
- Измерение пинга, per-app split tunneling UI, авто-обновление подписок в WorkManager.
- Хранение секретов в EncryptedSharedPreferences / Keystore.
