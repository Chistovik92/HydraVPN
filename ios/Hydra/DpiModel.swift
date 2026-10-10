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

    func start(settings: DpiProbeSettings, sni: String, extra: String, full: Bool) {
        guard !running else { return }
        let sites = DpiProbe.sites(settings: settings, extra: extra)
        // Свои стратегии (если включены и не пусты) заменяют встроенные 60.
        let own = settings.strategyLines()
        let list = (settings.customStrategiesOn && !own.isEmpty) ? own : (full ? DpiStrategies.presets : Array(DpiStrategies.presets.prefix(DpiProbe.quick)))
        running = true; finished = false; error = nil; baseline = nil; results = []; done = 0; total = list.count
        task = Task { [weak self] in
            await self?.run(list, sites, settings, sni)
        }
    }

    func cancel() {
        task?.cancel()
        ByeDpiRunner.shared.stop()
        running = false
    }

    private func run(_ list: [String], _ sites: [(name: String, sites: [String])], _ settings: DpiProbeSettings, _ sni: String) async {
        let totalSites = sites.reduce(0) { $0 + $1.sites.count }
        let basePort = DpiSettings.defaultPort + 1
        let timeout = TimeInterval(settings.timeoutSec)
        func probe(_ strategy: String, args: [String], port: Int, timeout: TimeInterval) async -> DpiProbeResult {
            do { try ByeDpiRunner.shared.start(args: args, port: port) }
            catch { return DpiProbeResult(strategy: strategy, ok: 0, total: totalSites) }
            defer { ByeDpiRunner.shared.stop() }
            var groups: [DpiProbeResult.Group] = []
            for g in sites {
                let ok = await Self.countReachable(g.sites, port: port, timeout: timeout, requests: settings.requests, parallel: settings.parallel)
                groups.append(.init(name: g.name, ok: ok, total: g.sites.count))
            }
            return DpiProbeResult(strategy: strategy, ok: groups.reduce(0) { $0 + $1.ok }, total: totalSites, groups: groups)
        }

        // Прогрев: первое соединение в свежем процессе медленнее остальных и ложно «не открывается».
        baseline = await probe("", args: ["-i", "127.0.0.1", "-p", String(basePort)], port: basePort, timeout: max(7, timeout))
        var all: [DpiProbeResult] = []
        for (i, s) in list.enumerated() {
            if Task.isCancelled { break }
            let port = basePort + 1 + (i % 20)
            let r = await probe(s, args: DpiArgs.build(s, port: port, sni: sni), port: port, timeout: timeout)
            all.append(r)
            done = i + 1
            // Все результаты: в окне их можно просмотреть целиком (0.7.20, как на Android с 0.7.15).
            results = all.sorted { $0.ratio > $1.ratio }
            if r.total > 0 && r.ok == r.total { break }   // нашлась стратегия, открывающая всё
            if settings.delaySec > 0, i + 1 < list.count { try? await Task.sleep(nanoseconds: UInt64(settings.delaySec) * 1_000_000_000) }
        }
        running = false
        finished = !Task.isCancelled
    }

    /// Сколько сайтов открылось: не больше `parallel` запросов одновременно, каждый сайт спрашивается `requests` раз.
    nonisolated static func countReachable(_ sites: [String], port: Int, timeout: TimeInterval, requests: Int, parallel: Int) async -> Int {
        await withTaskGroup(of: Bool.self) { tg -> Int in
            var next = 0, running = 0, ok = 0
            while next < sites.count || running > 0 {
                while next < sites.count && running < max(1, parallel) {
                    let s = sites[next]; next += 1; running += 1
                    tg.addTask { await Self.reachableMajority(s, port: port, timeout: timeout, requests: requests) }
                }
                if let r = await tg.next() { running -= 1; if r { ok += 1 } }
            }
            return ok
        }
    }

    /// Сайт открыт, если ответило большинство из `requests` попыток.
    nonisolated static func reachableMajority(_ site: String, port: Int, timeout: TimeInterval, requests: Int) async -> Bool {
        var good = 0
        for _ in 0..<max(1, requests) {
            if await reachable(site, port: port, timeout: timeout) { good += 1 }
        }
        return good * 2 > max(1, requests)
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
