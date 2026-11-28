package ru.gidravpn.hydra.vpn.core

import android.content.Context
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.TrustTunnelConfig
import ru.gidravpn.hydra.data.subscription.TrustTunnelArgs
import java.io.File

/**
 * TrustTunnel (AdGuard, Apache-2.0) — HTTPS-туннель TCP/UDP/ICMP
 * (HTTP/1.1 + HTTP/2 + HTTP/3/QUIC, «как обычный HTTPS», анти-DPI).
 *
 * Схема — [SocksBridgeCore]: нативный `trusttunnel_client` (TrustTunnelClient,
 * Apache-2.0, `libtrusttunnel.so` в `app/libs/trusttunnel/<abi>/`, см.
 * `scripts/build-trusttunnel.sh`) поднимается подпроцессом в режиме
 * **SOCKS5** на `127.0.0.1:$socksPort` (системный TUN и killswitch у него
 * выключены), а sing-box берёт на себя tun/маршрутизацию/DNS/split
 * (`SingBoxConfigBuilder.buildXrayBridge`). Свой Go/Rust-рантайм в своём
 * процессе → нет конфликта с libbox.
 *
 * Авторизация — `proxy-authorization: Basic base64(username:password)`.
 * TLS 1.2+, SNI обязателен, проверка сертификата сервера обязательна
 * (кроме явного `skip_verification`). Конфиг — TOML (`TrustTunnelArgs.buildToml`),
 * пишется во временный файл `0600` и удаляется в [stop]; секреты (password,
 * client_random_auth_key, сертификат) не попадают в журнал.
 *
 * Совместимость: протокол добавлен в 0.8.4, старые профили не затрагиваются.
 */
class TrustTunnelCore : SocksBridgeCore() {
    override val name = "TrustTunnel"
    override val binaryName = TrustTunnelArgs.BINARY_NAME
    override val socksPort = 10811
    override val label = "TrustTunnel"
    override val buildScript = "scripts/build-trusttunnel.sh"
    override val readyTimeoutMs: Long = 60_000

    override fun prepare(ctx: Context, profile: ServerProfile, secrets: MutableList<File>): List<String> {
        val cfg: TrustTunnelConfig = TrustTunnelArgs.toConfig(profile)
        // `certificate` upstream — PEM-строка (не путь); экранируем в TOML. Конфиг → файл 0600, удаляется в stop().
        val toml = TrustTunnelArgs.buildToml(cfg, socksPort, certificatePath = null)
        val configFile = secretFile(ctx, "trusttunnel.client.toml", toml, secrets)
        return TrustTunnelArgs.processArgs(configFile.path)
    }
}
