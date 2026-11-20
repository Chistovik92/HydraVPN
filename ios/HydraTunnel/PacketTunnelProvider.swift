import Foundation
import HydraKit
import Libbox
import NetworkExtension

/// Network Extension Hydra (Фаза 12) — аналог HydraVpnService на Android. Конфиг sing-box
/// собирается здесь же, из общего состояния в App Group: выбранный сервер, маршрутизация,
/// geo-базы из бандла. Поднимает Libbox, пишет журнал в App Group для экрана «Логи».
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let store = HydraStore.shared()
    private var commandServer: LibboxCommandServer?
    /// Telegram через WebSocket (0.7.20): локальный SOCKS5, живёт столько же, сколько туннель.
    private var tgWs: TgWsServer?
    private lazy var platform = TunnelPlatformInterface(self)

    override func startTunnel(options: [String: NSObject]?) async throws {
        store.appendLog("Туннель: запуск")
        var setupError: NSError?
        let setup = LibboxSetupOptions()
        setup.basePath = store.directory.path
        setup.workingPath = store.directory.appendingPathComponent("work", isDirectory: true).path
        setup.tempPath = FileManager.default.temporaryDirectory.path
        setup.logMaxLines = 300
        // На iOS лимит памяти Network Extension теперь обслуживает oom-killer libbox.
        setup.oomKillerEnabled = true
        try? FileManager.default.createDirectory(atPath: setup.workingPath, withIntermediateDirectories: true)
        LibboxSetup(setup, &setupError)
        if let setupError { throw fail("настройка libbox: \(setupError.localizedDescription)") }

        let state = store.load()
        let serverId = (options?["serverId"] as? NSNumber)?.int64Value ?? state.app.lastServerId
        guard let server = state.servers.first(where: { $0.id == serverId }) ?? state.selectedServer else {
            throw fail("сервер не выбран")
        }
        let engine = server.serverProtocol?.engine
        guard engine == .singBox || engine == .socksBridge || engine == .byeDpi else {
            throw fail("\(server.serverProtocol?.displayName ?? server.protocolId): протокол пока не поддерживается на iOS")
        }

        var o = SingBoxConfigBuilder.Options()
        if engine == .socksBridge {
            // 0.7.10: клиент olcRTC / OpenFlux — внутри этого же расширения, sing-box ходит к нему мостом.
            try startBridgeClient(server, port: Self.bridgePort)
            o.socksBridgePort = Self.bridgePort
        }
        if engine == .byeDpi {
            // Профиль «Обход DPI»: ByeDPI — основной клиент, sing-box ходит к нему мостом (tun → socks → интернет напрямую).
            let strategy = (server.extraObject["strategy"] as? String) ?? ""
            do {
                try ByeDpiRunner.shared.start(args: DpiArgs.build(strategy, port: Self.byeDpiPort), port: Self.byeDpiPort)
            } catch { throw fail("ByeDPI: \(error.localizedDescription)") }
            store.appendLog("ByeDPI: запущен, SOCKS5 127.0.0.1:\(Self.byeDpiPort)")
            o.socksBridgePort = Self.byeDpiPort
        }
        o.routing = state.routing
        o.geo = geoCountries(state.routing)
        if state.app.hotspotEnabled, !state.app.hotspotPassword.isEmpty {
            o.hotspot = (state.app.hotspotPort, state.app.hotspotUser, state.app.hotspotPassword)
        }
        let config = try buildConfig(server, o, state: state, engine: engine)
        store.appendLog("Подключение к \(server.address)… (sing-box \(LibboxVersion()))")

        // В libbox 1.14 CommandServer создаёт и владеет sing-box service.
        var serverError: NSError?
        let createdServer = LibboxNewCommandServer(platform, platform, &serverError)
        if let serverError {
            throw fail("создание командного сервера: \(serverError.localizedDescription)")
        }
        guard let server0 = createdServer else {
            throw fail("создание командного сервера: неизвестная ошибка")
        }
        do {
            try server0.start()
            try server0.startOrReloadService(config, options: LibboxOverrideOptions())
            commandServer = server0
        } catch {
            try? server0.closeService()
            server0.close()
            throw fail("запуск: \(error.localizedDescription)")
        }
        store.appendLog("Подключено: \(ServerLocation.label(server))")
    }

    override func stopTunnel(with reason: NEProviderStopReason) async {
        store.appendLog("Туннель: остановка (\(reason.rawValue))")
        if let server = commandServer {
            do {
                try server.closeService()
            } catch {
                store.appendLog("Ошибка остановки sing-box: \(error.localizedDescription)")
            }
            server.close()
        }
        commandServer = nil
        tgWs?.stop()
        tgWs = nil
        HydraolcStop()
        HydrafluxStop()
        ByeDpiRunner.shared.stop()
        platform.reset()
    }

    override func sleep() async { commandServer?.pause() }
    // Wake() в libbox 1.14 на iOS не снимает pause: wakeNow() сохраняет реальное пробуждение.
    override func wake() { commandServer?.wakeNow() }

    /// Сообщения от приложения: пока только «пинг» статистики.
    override func handleAppMessage(_ messageData: Data) async -> Data? { messageData }

    func log(_ line: String) { store.appendLog(line) }

    func serviceClosed() {
        store.appendLog("Ошибка: ядро sing-box остановилось")
        cancelTunnelWithError(NSError(domain: "Hydra", code: 2, userInfo: [NSLocalizedDescriptionKey: "sing-box stopped"]))
    }

    /// Локальный SOCKS5 клиента olcRTC / OpenFlux (тот же, что у OlcRtcCore на Android).
    private static let bridgePort = 10809
    /// SOCKS5 ByeDPI в профиле «Обход DPI» (как ByeDpiCore на Android).
    private static let byeDpiPort = DpiSettings.defaultPort + 2

    /// Конфиг sing-box с планом маршрутизации (0.7.13): правила, группы, цепочки, обход DPI, скачанные geo-базы.
    private func buildConfig(_ server: ServerProfile, _ o: SingBoxConfigBuilder.Options, state: HydraState, engine: Engine?) throws -> String {
        let cfg = state.routing.routeConfig
        // На iOS нет правил по приложениям и процессам (система не сообщает, какое приложение открыло соединение).
        let kinds = Set(RouteKind.allCases).subtracting([.app, .process])
        let fragment = state.routing.tlsFragment
        var plan = RoutePlanFactory.build(cfg, profile: server, supported: kinds,
                                          lookup: { id in state.servers.first { $0.id == id } },
                                          nodeOutbound: { p, tag in
                                              let n = SingBoxConfigBuilder.nodeOutbound(p, tag, fragment: fragment)
                                              if n == nil { self.store.appendLog("Маршрутизация: «\(p.name)» нельзя использовать как выход на iOS (нужен протокол sing-box)") }
                                              return n
                                          })
        if plan.needsDpi {
            if engine == .byeDpi {
                // В процессе один экземпляр ByeDPI: профиль «Обход DPI» уже занял его.
                store.appendLog("ByeDPI: профиль «Обход DPI» уже использует движок — дополнительный обход отключён")
                plan = plan.withoutDpi()
            } else {
                do {
                    try ByeDpiRunner.shared.start(args: DpiArgs.build(plan.dpi.strategy, port: plan.dpi.port, sni: plan.dpi.fakeSni), port: plan.dpi.port)
                    store.appendLog("ByeDPI: запущен (\(plan.dpi.strategy))")
                } catch {
                    // Без обхода DPI соединение лучше, чем никакого.
                    store.appendLog("ByeDPI: не запущен, продолжаем без него — \(error.localizedDescription)")
                    plan = plan.withoutDpi()
                }
            }
        }
        if plan.needsTgWs {
            // 0.7.20: Telegram через WebSocket - внутри расширения (подпроцессы iOS запрещает, как и для ByeDPI).
            let tg = TgWsServer { [store] in store.appendLog($0) }
            do {
                try tg.start(port: UInt16(RouteTarget.tgwsPort))
                // MTProto-прокси рядом со SOCKS5: ошибка порта - не повод отказываться от TG WS.
                do { try tg.startMtProto(port: UInt16(RouteTarget.tgwsMtPort)) }
                catch { store.appendLog("TG WS: MTProto-прокси не запущен (\(error.localizedDescription)) - работает только SOCKS5") }
                tgWs = tg
            } catch {
                store.appendLog("TG WS: не запущен (\(error.localizedDescription)) - продолжаем без него")
                tg.stop()
                plan = plan.withoutTgWs()
            }
        }
        let geoStore = GeoStore(directory: store.geoDirectory)
        let geoPath: (RouteKind, String) -> String? = { kind, name in
            let gk: GeoKind = kind == .geosite ? .site : .ip
            return Self.geoFile(gk, name, geoStore)
        }
        do {
            let r = try SingBoxConfigBuilder.buildJSON(server, o, plan: plan, geoPath: geoPath)
            r.warnings.forEach { store.appendLog("Маршрутизация: \($0)") }
            return r.json
        } catch { throw fail(error.localizedDescription) }
    }

    /// База: сначала скачанная (App Group), потом вшитая в бандл расширения.
    private static func geoFile(_ kind: GeoKind, _ name: String, _ store: GeoStore) -> String? {
        if let u = store.resolve(kind, name) { return u.path }
        let p = Bundle.main.bundleURL.appendingPathComponent("\(kind.rawValue)/\(name).srs").path
        return FileManager.default.fileExists(atPath: p) ? p : nil
    }

    /// Поднимает клиент olcRTC или OpenFlux (Libbox.xcframework, пакеты hydraolc/hydraflux — ios/Bridge).
    private func startBridgeClient(_ s: ServerProfile, port: Int) throws {
        let extra = s.extraObject
        func str(_ k: String) -> String { (extra[k] as? String) ?? "" }
        var err: NSError?
        switch s.serverProtocol {
        case .olcrtc:
            let provider = str("provider").isEmpty ? "telemost" : str("provider")
            let transport = s.transport.isEmpty ? "datachannel" : s.transport
            store.appendLog("olcRTC: подключение к комнате (\(provider), \(transport))…")
            // Как на Android: явный резолвер — системного у Go-клиента в расширении может не оказаться.
            _ = HydraolcStart(provider, transport, s.address, s.uuidOrPassword, "1.1.1.1:53", port, 30_000, &err)
            if let err { throw fail("olcRTC: \(err.localizedDescription)") }
        case .openflux:
            if let specs = OpenFluxSession.specsJSON(extra) {
                // Профиль из официальной ссылки openflux://v1/ — сессия из нескольких транспортов.
                store.appendLog("OpenFlux: сессия, транспортов — \(OpenFluxSession.count(extra))…")
                _ = HydrafluxStartSession(specs, s.uuidOrPassword, port, &err)
            } else {
                store.appendLog("OpenFlux: запуск транспорта \(s.transport)…")
                _ = HydrafluxStart(s.transport, s.address, s.uuidOrPassword, str("codec"), str("maxToken"), str("maxUid"), port, &err)
            }
            if let err {
                let tail = HydrafluxLogs().split(separator: "\n").suffix(3).joined(separator: " · ")
                throw fail("OpenFlux: \(err.localizedDescription)" + (tail.isEmpty ? "" : " (\(tail))"))
            }
        default:
            throw fail("\(s.protocolId): нет клиента-моста")
        }
        store.appendLog("\(s.serverProtocol?.displayName ?? s.protocolId): клиент запущен, SOCKS5 127.0.0.1:\(port)")
    }

    private func fail(_ message: String) -> NSError {
        store.appendLog("Ошибка: \(message)")
        return NSError(domain: "Hydra", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }

    /// geo-базы (.srs) лежат в бандле расширения: папки geoip/ и geosite/ из Android-ассетов.
    private func geoCountries(_ r: RoutingSettings) -> [SingBoxConfigBuilder.GeoCountry] {
        guard r.geoMode != .off else { return [] }
        let geoStore = GeoStore(directory: store.geoDirectory)
        return r.geoCountries.sorted().compactMap { cc in
            guard let ip = Self.geoFile(.ip, cc, geoStore) else { return nil }
            return .init(code: cc, geoipPath: ip, geositePath: Self.geoFile(.site, cc, geoStore))
        }
    }
}
