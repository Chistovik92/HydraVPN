package ru.gidravpn.hydra.servers

/**
 * Установка **сервера** TrustTunnel (эндпоинт `trusttunnel_endpoint`,
 * upstream `TrustTunnel/TrustTunnel`, Apache-2.0) поверх SSH через [Installer].
 *
 * Релизная сборка HydraVPN должна и «ставить и разворачивать сервер», и работать
 * клиентом на всех платформах. Клиентская часть — `Engine.TRUSTTUNNEL` /
 * `TrustTunnelCore`; этот объект даёт **план установки сервера**.
 *
 * Что делает (Linux + systemd, root):
 *  1. скачивает официальный `scripts/install.sh` и ставит эндпоинт в `/opt/trusttunnel`;
 *  2. открывает порт в firewall ([ServerOps.openPortStep]);
 *  3. (best-effort) инициализирует `vpn.toml` немодальным мастером, если он есть.
 *
 * Честно: полностью неинтерактивный провижининг (TLS-сертификат Let's Encrypt,
 * пользователи, экспорт `tt://`-конфига) в upstream делается мастером
 * `trusttunnel_endpoint --setup-wizard`; здесь это отдельные [Step]'ы, и после
 * установки нужно выполнить мастер + `systemctl enable --now trusttunnel`, либо
 * заполнить `vpn.toml` вручную и поднять сервис. Экспорт клиентской конфигурации —
 * `trusttunnel_endpoint ... --export` → `trusttunnel_client` `[endpoint]` / `tt://`.
 *
 * Сервер совместим с Linux и macOS; клиенты — Android, Apple, Windows, Linux
 * (и HydraVPN на всех платформах).
 */
object TrustTunnelServer {

    const val INSTALL_DIR = "/opt/trusttunnel"
    const val ENDPOINT_BIN = "$INSTALL_DIR/trusttunnel_endpoint"
    const val VPN_TOML = "$INSTALL_DIR/vpn.toml"
    const val INSTALL_SH_URL =
        "https://raw.githubusercontent.com/TrustTunnel/TrustTunnel/refs/heads/master/scripts/install.sh"

    /** Порт эндпоинта по умолчанию (HTTPS-маскировка). */
    const val DEFAULT_PORT = 443

    /**
     * План установки. [port] — порт эндпоинта; [firewall] — из [Preflight.firewall].
     * Секреты (пароли пользователей) сюда НЕ передаются — заводятся отдельно.
     */
    fun installSteps(port: Int = DEFAULT_PORT, firewall: String = "none"): List<Step> = buildList {
        add(Step(
            id = "tt-install",
            title = "Установить trusttunnel_endpoint (TrustTunnel, Apache-2.0) в $INSTALL_DIR",
            command = "mkdir -p ${Installer.shq(INSTALL_DIR)} && " +
                "curl -fsSL ${Installer.shq(INSTALL_SH_URL)} | sh -s - -o ${Installer.shq(INSTALL_DIR)}",
            rollback = null,   // удаление — отдельным wipe; не трогаем чужие файлы
            failHint = "Нет доступа в интернет к github.com / неподдерживаемая архитектура (нужны linux-x86_64 / linux-aarch64)",
        ))
        ServerOps.openPortStep(firewall, port.toString())?.let { add(it) }
        add(Step(
            id = "tt-service",
            title = "Включить systemd-сервис trusttunnel (после заполнения $VPN_TOML)",
            // accept=true: сервис может быть ещё не сконфигурирован; мастер вызывается отдельно
            command = "systemctl daemon-reload 2>/dev/null; systemctl enable trusttunnel 2>/dev/null || true; " +
                "if [ -f ${Installer.shq(VPN_TOML)} ]; then systemctl restart trusttunnel; fi",
            accept = { true },
            failHint = "Сначала заполните $VPN_TOML (мастер trusttunnel_endpoint --setup-wizard)",
        ))
    }

    /** Команда официального мастера настройки эндпоинта (интерактивная / non-interactive upstream). */
    fun setupWizardCommand(): String =
        "${Installer.shq(ENDPOINT_BIN)} --setup-wizard --settings ${Installer.shq(VPN_TOML)}"

    /** Команда запуска эндпоинта (если без systemd). */
    fun runCommand(): String = "${Installer.shq(ENDPOINT_BIN)} --config ${Installer.shq(VPN_TOML)}"

    /** Команда экспорта клиентской конфигурации для TrustTunnelClient (`[endpoint]` TOML / `tt://`). */
    fun exportClientCommand(): String =
        "${Installer.shq(ENDPOINT_BIN)} --setup-wizard --mode non-interactive " +
            "--endpoint_config ${Installer.shq(VPN_TOML)} 2>/dev/null || true"
}
