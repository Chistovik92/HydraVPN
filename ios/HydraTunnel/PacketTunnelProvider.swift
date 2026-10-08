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
    private var boxService: LibboxBoxService?
    private lazy var platform = TunnelPlatformInterface(self)

    override func startTunnel(options: [String: NSObject]?) async throws {
        store.appendLog("Туннель: запуск")
        var setupError: NSError?
        let setup = LibboxSetupOptions()
        setup.basePath = store.directory.path
        setup.workingPath = store.directory.appendingPathComponent("work", isDirectory: true).path
        setup.tempPath = FileManager.default.temporaryDirectory.path
        try? FileManager.default.createDirectory(atPath: setup.workingPath, withIntermediateDirectories: true)
        LibboxSetup(setup, &setupError)
        if let setupError { throw fail("настройка libbox: \(setupError.localizedDescription)") }
        // Лимит памяти Network Extension (~50 МБ): libbox сам подрезает буферы под него.
        LibboxSetMemoryLimit(true)

        let state = store.load()
        let serverId = (options?["serverId"] as? NSNumber)?.int64Value ?? state.app.lastServerId
        guard let server = state.servers.first(where: { $0.id == serverId }) ?? state.selectedServer else {
            throw fail("сервер не выбран")
        }
        let engine = server.serverProtocol?.engine
        guard engine == .singBox || engine == .socksBridge else {
            throw fail("\(server.serverProtocol?.displayName ?? server.protocolId): протокол пока не поддерживается на iOS")
        }

        var o = SingBoxConfigBuilder.Options()
        if engine == .socksBridge {
            // 0.7.10: клиент olcRTC / OpenFlux — внутри этого же расширения, sing-box ходит к нему мостом.
            try startBridgeClient(server, port: Self.bridgePort)
            o.socksBridgePort = Self.bridgePort
        }
        o.routing = state.routing
        o.geo = geoCountries(state.routing)
        if state.app.hotspotEnabled, !state.app.hotspotPassword.isEmpty {
            o.hotspot = (state.app.hotspotPort, state.app.hotspotUser, state.app.hotspotPassword)
        }
        let config: String
        do { config = try SingBoxConfigBuilder.buildJSON(server, o) } catch { throw fail(error.localizedDescription) }
        store.appendLog("Подключение к \(server.address)… (sing-box \(LibboxVersion()))")

        let server0: LibboxCommandServer? = LibboxNewCommandServer(platform, 300)
        commandServer = server0
        do { try server0?.start() } catch { throw fail("командный сервер: \(error.localizedDescription)") }

        var serviceError: NSError?
        guard let service = LibboxNewService(config, platform, &serviceError) else {
            throw fail("создание сервиса: \(serviceError?.localizedDescription ?? "неизвестно")")
        }
        do { try service.start() } catch { throw fail("запуск: \(error.localizedDescription)") }
        server0?.setService(service)
        boxService = service
        store.appendLog("Подключено: \(ServerLocation.label(server))")
    }

    override func stopTunnel(with reason: NEProviderStopReason) async {
        store.appendLog("Туннель: остановка (\(reason.rawValue))")
        try? boxService?.close()
        boxService = nil
        HydraolcStop()
        HydrafluxStop()
        commandServer?.setService(nil)
        try? commandServer?.close()
        commandServer = nil
        platform.reset()
    }

    override func sleep() async { boxService?.pause() }
    override func wake() { boxService?.wake() }

    /// Сообщения от приложения: пока только «пинг» статистики.
    override func handleAppMessage(_ messageData: Data) async -> Data? { messageData }

    func log(_ line: String) { store.appendLog(line) }

    func serviceClosed() {
        store.appendLog("Ошибка: ядро sing-box остановилось")
        boxService = nil
        cancelTunnelWithError(NSError(domain: "Hydra", code: 2, userInfo: [NSLocalizedDescriptionKey: "sing-box stopped"]))
    }

    /// Локальный SOCKS5 клиента olcRTC / OpenFlux (тот же, что у OlcRtcCore на Android).
    private static let bridgePort = 10809

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
            store.appendLog("OpenFlux: запуск транспорта \(s.transport)…")
            _ = HydrafluxStart(s.transport, s.address, s.uuidOrPassword, str("codec"), str("maxToken"), str("maxUid"), port, &err)
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
        let base = Bundle.main.bundleURL
        return r.geoCountries.sorted().compactMap { cc in
            let ip = base.appendingPathComponent("geoip/\(cc).srs")
            guard FileManager.default.fileExists(atPath: ip.path) else { return nil }
            let site = base.appendingPathComponent("geosite/\(cc).srs")
            return .init(code: cc, geoipPath: ip.path,
                         geositePath: FileManager.default.fileExists(atPath: site.path) ? site.path : nil)
        }
    }
}
