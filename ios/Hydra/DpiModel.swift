import Foundation
import HydraKit
import Network

/// Мастер подбора обхода DPI (0.7.13) — порт `DpiProbe` с Android. Для каждой стратегии поднимается ByeDPI (в этом же процессе),
/// сайты открываются через него по HTTPS, выигрывает та, где открылось больше. Сначала — проверка «без обхода».
@MainActor
final class DpiWizardModel: ObservableObject {
    @Published var running = false
    @Published var done = 0
    @Published var total = 0
    @Published var baseline: DpiProbeResult?
    @Published var results: [DpiProbeResult] = []
    @Published var finished = false
    @Published var error: String?

    private var task: Task<Void, Never>?

    func start(groups: Set<String>, extra: String, full: Bool) {
        guard !running else { return }
        let sites = DpiProbe.sites(groups: groups, extra: extra)
        let list = full ? DpiStrategies.presets : Array(DpiStrategies.presets.prefix(DpiProbe.quick))
        running = true; finished = false; error = nil; baseline = nil; results = []; done = 0; total = list.count
        task = Task { [weak self] in
            await self?.run(list, sites)
        }
    }

    func cancel() {
        task?.cancel()
        ByeDpiRunner.shared.stop()
        running = false
    }

    private func run(_ list: [String], _ sites: [(name: String, sites: [String])]) async {
        let totalSites = sites.reduce(0) { $0 + $1.sites.count }
        let basePort = DpiSettings.defaultPort + 1
        func probe(_ strategy: String, args: [String], port: Int, timeout: TimeInterval) async -> DpiProbeResult {
            do { try ByeDpiRunner.shared.start(args: args, port: port) }
            catch { return DpiProbeResult(strategy: strategy, ok: 0, total: totalSites) }
            defer { ByeDpiRunner.shared.stop() }
            var groups: [DpiProbeResult.Group] = []
            for g in sites {
                let ok = await withTaskGroup(of: Bool.self) { tg -> Int in
                    for s in g.sites { tg.addTask { await Self.reachable(s, port: port, timeout: timeout) } }
                    var n = 0
                    for await r in tg where r { n += 1 }
                    return n
                }
                groups.append(.init(name: g.name, ok: ok, total: g.sites.count))
            }
            return DpiProbeResult(strategy: strategy, ok: groups.reduce(0) { $0 + $1.ok }, total: totalSites, groups: groups)
        }

        // Прогрев: первое соединение в свежем процессе медленнее остальных и ложно «не открывается».
        baseline = await probe("", args: ["-i", "127.0.0.1", "-p", String(basePort)], port: basePort, timeout: 7)
        var all: [DpiProbeResult] = []
        for (i, s) in list.enumerated() {
            if Task.isCancelled { break }
            let port = basePort + 1 + (i % 20)
            let r = await probe(s, args: DpiArgs.build(s, port: port), port: port, timeout: 3.5)
            all.append(r)
            done = i + 1
            results = Array(all.sorted { $0.ratio > $1.ratio }.prefix(3))
            if r.total > 0 && r.ok == r.total { break }   // нашлась стратегия, открывающая всё
        }
        running = false
        finished = !Task.isCancelled
    }

    /// Любой HTTP-ответ (даже 403/404) — сайт достижим: ClientHello дошёл.
    nonisolated static func reachable(_ site: String, port: Int, timeout: TimeInterval) async -> Bool {
        guard let url = URL(string: site.hasPrefix("http") ? site : "https://\(site)"),
              let p = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return false }
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = timeout
        cfg.timeoutIntervalForResource = timeout
        cfg.proxyConfigurations = [ProxyConfiguration(socksv5Proxy: .hostPort(host: "127.0.0.1", port: p))]
        let session = URLSession(configuration: cfg)
        defer { session.invalidateAndCancel() }
        var req = URLRequest(url: url)
        req.httpMethod = "HEAD"
        do {
            let (_, resp) = try await session.data(for: req)
            return resp is HTTPURLResponse
        } catch { return false }
    }
}

extension AppModel {
    /// Какие базы нужны сейчас: страны режима geo, правила «страна → выход», дополнительные наборы и свои источники.
    func geoWanted() -> [(GeoKind, String)] {
        let r = state.routing
        var out: [(GeoKind, String)] = []
        if r.geoMode != .off {
            for cc in r.geoCountries {
                out.append((.ip, cc))
                if Bundle.main.url(forResource: cc, withExtension: "srs", subdirectory: "geosite") != nil
                    || GeoStore(directory: store.geoDirectory).resolve(.site, cc) != nil { out.append((.site, cc)) }
            }
        }
        for rule in r.routeConfig.rules {
            if rule.kind == .geoip { out.append((.ip, rule.value.lowercased())) }
            if rule.kind == .geosite { out.append((.site, rule.value.lowercased())) }
        }
        let g = r.geoSettings
        out += g.extraIp.map { (.ip, $0) } + g.extraSite.map { (.site, $0) } + g.custom.map { ($0.kind, $0.name) }
        return out.filter { !$0.1.isEmpty }
    }

    /// Обновить geo-базы. `manual` — по кнопке (всегда); иначе по расписанию. Возвращает краткий итог для показа.
    @discardableResult
    func updateGeo(manual: Bool) async -> String? {
        let g = state.routing.geoSettings
        let geo = GeoStore(directory: store.geoDirectory)
        if !manual && (!g.autoUpdate || Date().timeIntervalSince(geo.lastChecked()) < Double(g.intervalHours) * 3600) { return nil }
        let wanted = geoWanted()
        if wanted.isEmpty { return nil }
        let report = await GeoUpdater(store: geo).update(g, wanted: wanted) { [store] in store.appendLog($0) }
        let text = "+\(report.installed) / =\(report.unchanged) / ✕\(report.failed)"
        store.appendLog("Geo-базы: обновлено \(report.installed), без изменений \(report.unchanged), ошибок \(report.failed)")
        if report.installed > 0 { applyTunnelSettings() }   // sing-box читает базы при запуске — переподключаемся
        geoVersion += 1
        return text
    }
}
