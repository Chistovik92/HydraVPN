# Hydra для компьютера (Windows · Linux · macOS)

Клиент на Compose Desktop; ядро sing-box (и Xray-core как второе) запускается отдельным процессом. Данные,
парсеры ссылок, конфиг sing-box, клиенты бота и роутера и поиск обновлений — общий модуль [`:shared`](../shared).
Общая стратегия — [docs/MULTIPLATFORM.md](../docs/MULTIPLATFORM.md).

## Что умеет (0.7.2)

- **Подключение:** «Системный прокси» (HTTP+SOCKS5 на 127.0.0.1, без прав администратора) или **TUN** (весь
  трафик ОС; Windows — запуск от администратора, Linux — один раз pkexec, macOS — пароль при подключении).
  Kill switch, автопереподключение, автозапуск (сразу в трей), раздача VPN в локальную сеть.
- **Протоколы:** VLESS (REALITY/Vision), VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard. Движки sing-box
  и Xray переключаются в Настройки → Движки. AmneziaWG, SSTP, L2TP, olcRTC, OpenFlux — пока только Android.
- **Подписки и аккаунт:** импорт из буфера/ссылки, автообновление, **вход через бота «Радар»**
  (Настройки → «Аккаунт Hydra VPN»), срок и трафик на главной, приватный DNS для вошедших.
- **Маршруты:** по программам (процесс/путь), по сайтам и IP, по странам (GeoIP), DNS, фрагментация TLS, MTU,
  IPv6, профили маршрутизации.
- **Роутеры:** вкладка «Роутеры» — сопряжение (`hydravpn-router://…`), статус, узлы, **разделы**
  (включить/выключить, править, добавить, удалить), подписки («отправить на роутер»), **подключение роутера к боту**,
  диагностика, журнал, перезапуск. Раздел с личными ссылками роутер отдаёт замаскированными — такой раздел из
  приложения править нельзя (запись стёрла бы ключи), его правят в веб-интерфейсе роутера.
- **Обновления:** «Обновить» скачивает файл для вашей ОС и архитектуры (MSI/EXE или ZIP, DEB/RPM/AppImage/tar.gz,
  DMG), сверяет SHA-256 из релиза и запускает установщик. AppImage заменяет сам себя.

## Где лежат данные

`hydra.json` (серверы, подписки, настройки, токен бота — файл с правами только для владельца) в:
Windows `%APPDATA%\Hydra`, macOS `~/Library/Application Support/Hydra`, Linux `$XDG_CONFIG_HOME/hydra`.
В резервную копию токен бота не попадает.

## Сборка и запуск

```bash
./gradlew :desktop:run                 # запуск (ядро скачивается и проверяется по SHA-256)
./gradlew :desktop:test                # тесты: конфиги, маршрутизация, хранилище, бот, роутер, обновления, рендер экранов
./gradlew :desktop:packageDistributionForCurrentOS   # пакет для своей ОС
```

Приватный DNS Hydra VPN — секрет сборки: `-PhydraPrivateDns=https://…` или переменная `HYDRA_PRIVATE_DNS`.

### Проверки на живых серверах (по желанию)

- **Роутер:** запустите `hydravpn-router start -c config.yaml` (с `settings.api_listen` и `api_token`), затем
  `HYDRA_ROUTER_URL=http://127.0.0.1:18088 HYDRA_ROUTER_TOKEN=… ./gradlew :desktop:test --tests '*RouterLiveTest*'`.
  Без переменных эти тесты пропускаются.
- **Бот:** `BotLiveTest` поднимает локальную заглушку бота по контракту `docs/API_APPS.md` и проходит весь путь:
  вход по коду, подписки, смена ссылки, выключение, отзыв токена.
- **Скриншоты для README:** `HYDRA_SCREENSHOTS_DIR=docs/screenshots ./gradlew :desktop:test --tests '*ScreenshotsTest*'`.

Релиз всех платформ одним конвейером — [`scripts/release.sh`](../scripts/release.sh) и
[`.github/workflows/release.yml`](../.github/workflows/release.yml).
