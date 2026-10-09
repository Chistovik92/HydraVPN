import Foundation
import XCTest
@testable import HydraKit

/// Маршрутизация через несколько выходов, цепочки, группы, обход DPI и geo-слой (0.7.13) — порт тестов Android
/// (`RoutePlanTest`, `GeoStoreTest`): те же сценарии, тот же результат.
final class RoutingTests: XCTestCase {
    private let vless = ServerProfile(id: 1, name: "main", protocolId: "vless", address: "203.0.113.10", port: 443,
                                      uuidOrPassword: "b831381d-6324-4d53-ad4f-8cda48b30811", security: "tls")
    private let trojan = ServerProfile(id: 2, name: "second", protocolId: "trojan", address: "203.0.113.20", port: 443,
                                       uuidOrPassword: "secret", sni: "example.org", security: "tls")

    private func base() throws -> [String: Any] { try SingBoxConfigBuilder.build(vless, SingBoxConfigBuilder.Options()) }
    private func outs(_ r: [String: Any]) -> [[String: Any]] { r["outbounds"] as? [[String: Any]] ?? [] }
    private func rules(_ r: [String: Any]) -> [[String: Any]] { (r["route"] as? [String: Any])?["rules"] as? [[String: Any]] ?? [] }
    private func node(_ p: ServerProfile, _ id: Int64, via: String? = nil) -> RouteNode {
        var o = SingBoxConfigBuilder.nodeOutbound(p, RouteTarget.node(id))!
        o["tag"] = RouteTarget.node(id)
        return RouteNode(tag: RouteTarget.node(id), outbound: o, via: via)
    }

    func testEmptyPlanChangesNothing() throws {
        var cfg = try base()
        let before = NSDictionary(dictionary: cfg)
        XCTAssertTrue(RoutePlanApplier.apply(&cfg, RoutePlan()).isEmpty)
        XCTAssertEqual(before, NSDictionary(dictionary: cfg))
    }

    func testRulesGoToDifferentOutboundsAfterSniffAndHijack() throws {
        var cfg = try base()
        var plan = RoutePlan()
        plan.rules = [
            RouteRule(kind: .suffix, value: ".ru", target: RouteTarget.node(2)),
            RouteRule(kind: .port, value: "6881-6999", target: RouteTarget.block),
            RouteRule(kind: .cidr, value: "198.51.100.0/24", target: RouteTarget.proxy),
        ]
        plan.nodes = [node(trojan, 2)]
        XCTAssertTrue(RoutePlanApplier.apply(&cfg, plan).isEmpty)
        let r = rules(cfg)
        XCTAssertEqual(r[0]["action"] as? String, "sniff")
        XCTAssertEqual(r[1]["action"] as? String, "hijack-dns")
        XCTAssertEqual(r[2]["domain_suffix"] as? [String], ["ru"])
        XCTAssertEqual(r[2]["outbound"] as? String, "node-2")
        XCTAssertEqual(r[3]["port_range"] as? [String], ["6881:6999"])
        XCTAssertEqual(r[3]["action"] as? String, "reject")
        XCTAssertNil(r[3]["outbound"])
        XCTAssertEqual(outs(cfg).compactMap { $0["tag"] as? String }, ["proxy", "direct", "node-2"])
    }

    func testAndGroupsAndNegation() throws {
        var cfg = try base()
        var plan = RoutePlan()
        plan.rules = [
            RouteRule(kind: .suffix, value: "a.com", target: RouteTarget.direct, group: "g"),
            RouteRule(kind: .port, value: "443", target: RouteTarget.direct, group: "g"),
            RouteRule(kind: .network, value: "udp", target: RouteTarget.block, invert: true),
        ]
        XCTAssertTrue(RoutePlanApplier.apply(&cfg, plan).isEmpty)
        let r = rules(cfg)
        XCTAssertEqual(r[2]["domain_suffix"] as? [String], ["a.com"])
        XCTAssertEqual(r[2]["port"] as? [Int], [443])
        XCTAssertEqual(r[3]["type"] as? String, "logical")
        XCTAssertEqual(r[3]["action"] as? String, "reject")
        let parts = r[3]["rules"] as? [[String: Any]] ?? []
        XCTAssertEqual(parts.first?["invert"] as? Bool, true)
    }

    func testGroupsAndChains() throws {
        var cfg = try base()
        var plan = RoutePlan()
        let a = node(trojan, 1, via: RouteTarget.node(2)), b = node(trojan, 2, via: RouteTarget.node(3)), c = node(trojan, 3, via: RouteTarget.node(1))
        plan.nodes = [a, b, c]
        plan.proxyVia = RouteTarget.node(1)
        let g1 = RouteGroup(name: "Fast", type: RouteGroup.urltest, members: [RouteTarget.proxy, RouteTarget.node(1), "node-99"])
        plan.groups = [g1]
        plan.rules = [RouteRule(kind: .suffix, value: "x.com", target: g1.tag)]
        let warn = RoutePlanApplier.apply(&cfg, plan)
        let o = Dictionary(uniqueKeysWithValues: outs(cfg).compactMap { x in (x["tag"] as? String).map { ($0, x) } })
        XCTAssertEqual(o["proxy"]?["detour"] as? String, "node-1")
        XCTAssertEqual(o["node-1"]?["detour"] as? String, "node-2")
        XCTAssertEqual(o["node-2"]?["detour"] as? String, "node-3")
        XCTAssertNil(o["node-3"]?["detour"])                         // замыкание цепочки отсекается
        XCTAssertEqual(warn.count, 1)
        XCTAssertEqual(o[g1.tag]?["type"] as? String, "urltest")
        XCTAssertEqual(o[g1.tag]?["outbounds"] as? [String], ["proxy", "node-1"])
    }

    func testChainThroughDpiAndDirectViaDpi() throws {
        var cfg = try base()
        var plan = RoutePlan()
        plan.dpi = DpiSettings(enabled: true, strategy: "-o 1", port: 10999, directViaDpi: true)
        plan.proxyVia = RouteTarget.dpi
        plan.rules = [RouteRule(kind: .suffix, value: "ya.ru", target: RouteTarget.direct)]
        XCTAssertTrue(plan.needsDpi)
        XCTAssertTrue(RoutePlanApplier.apply(&cfg, plan).isEmpty)
        let o = Dictionary(uniqueKeysWithValues: outs(cfg).compactMap { x in (x["tag"] as? String).map { ($0, x) } })
        XCTAssertEqual(o["proxy"]?["detour"] as? String, "dpi")
        XCTAssertEqual(o["dpi"]?["server_port"] as? Int, 10999)
        XCTAssertEqual(rules(cfg)[2]["outbound"] as? String, "dpi")
        XCTAssertFalse(plan.withoutDpi().needsDpi)
    }

    func testUdpProtocolIsNotChained() throws {
        let hy2 = ServerProfile(id: 3, name: "h", protocolId: "hysteria2", address: "203.0.113.30", port: 443, uuidOrPassword: "p", sni: "x.com")
        var cfg = try SingBoxConfigBuilder.build(hy2, SingBoxConfigBuilder.Options())
        var plan = RoutePlan()
        plan.dpi.enabled = true
        plan.proxyVia = RouteTarget.dpi
        XCTAssertEqual(RoutePlanApplier.apply(&cfg, plan).count, 1)
        XCTAssertNil(outs(cfg).first { ($0["tag"] as? String) == "proxy" }?["detour"])
    }

    func testDomainRulesToDirectGetLocalDnsRuleAndGeoSetsUseJsonSource() throws {
        var cfg = try base()
        var plan = RoutePlan()
        plan.rules = [RouteRule(kind: .geosite, value: "category-ru", target: RouteTarget.direct), RouteRule(kind: .suffix, value: "x.com", target: RouteTarget.proxy)]
        let warn = RoutePlanApplier.apply(&cfg, plan) { _, name in name == "category-ru" ? "/geo/category-ru.json" : nil }
        XCTAssertTrue(warn.isEmpty)
        let sets = (cfg["route"] as? [String: Any])?["rule_set"] as? [[String: Any]] ?? []
        XCTAssertEqual(sets.first?["format"] as? String, "source")
        let dns = ((cfg["dns"] as? [String: Any])?["rules"] as? [[String: Any]]) ?? []
        XCTAssertEqual(dns.count, 1)
        XCTAssertEqual(dns[0]["server"] as? String, "local")
        XCTAssertNil(dns[0]["outbound"])
        var missing = try base()
        XCTAssertEqual(RoutePlanApplier.apply(&missing, plan).count, 1)   // нет базы — правило пропущено
    }

    func testFactoryPullsNodesGroupsAndTheirChains() {
        var cfg = RouteConfig()
        let g = RouteGroup(name: "Pool", members: [RouteTarget.node(2), RouteTarget.node(4)])
        cfg.rules = [RouteRule(kind: .suffix, value: "a.com", target: g.tag), RouteRule(kind: .app, value: "x.y", target: RouteTarget.direct)]
        cfg.groups = [g, RouteGroup(name: "Unused", members: [RouteTarget.node(9)])]
        let profiles: [Int64: ServerProfile] = [
            2: withVia(trojan, RouteTarget.node(3)),
            3: ServerProfile(id: 3, name: "c", protocolId: "trojan", address: "203.0.113.30", port: 443, uuidOrPassword: "s", security: "tls"),
            4: ServerProfile(id: 4, name: "d", protocolId: "trojan", address: "203.0.113.40", port: 443, uuidOrPassword: "s", security: "tls"),
        ]
        let plan = RoutePlanFactory.build(cfg, profile: withVia(vless, RouteTarget.dpi), supported: [.suffix, .geoip, .geosite, .cidr],
                                          lookup: { profiles[$0] }, nodeOutbound: { p, tag in SingBoxConfigBuilder.nodeOutbound(p, tag) })
        XCTAssertEqual(Set(plan.nodes.map(\.tag)), ["node-2", "node-3", "node-4"])
        XCTAssertEqual(plan.groups.map(\.tag), [g.tag])
        XCTAssertEqual(plan.rules.count, 1)                       // .app на iOS отброшен
        XCTAssertEqual(plan.proxyVia, RouteTarget.dpi)
        XCTAssertEqual(plan.nodes.first { $0.tag == "node-2" }?.via, "node-3")
    }

    func testAndroidJsonRoundTripAndBackup() throws {
        var cfg = RouteConfig()
        cfg.rules = [RouteRule(kind: .proto, value: "tls", target: RouteTarget.dpi, invert: true, group: "x"), RouteRule(kind: .srcCidr, value: "10.0.0.0/8", target: RouteTarget.block)]
        cfg.groups = [RouteGroup(name: "Fast", type: RouteGroup.selector, members: ["proxy", "node-5"], intervalSec: 120)]
        cfg.dpi = DpiSettings(enabled: true, strategy: "-o 2", port: 11000, directViaDpi: false)
        XCTAssertEqual(RouteConfig(androidJSON: cfg.androidJSON()), cfg)
        var geo = GeoSettings()
        geo.autoUpdate = false; geo.intervalHours = 12; geo.ipSource = "sagernet"; geo.extraSite = ["ru-blocked"]
        geo.custom = [CustomGeoSource(kind: .ip, name: "n", url: "https://x.y/z", type: CustomGeoSource.typeList)]
        XCTAssertEqual(GeoSettings(androidJSON: geo.androidJSON()), geo)

        var s = HydraState()
        s.routing.routes = cfg
        s.routing.geo = geo
        let data = try BackupCodec.export(s, appVersion: "t")
        let back = try BackupCodec.import(data, into: HydraState())
        XCTAssertEqual(back.routing.routes, cfg)
        XCTAssertEqual(back.routing.geo, geo)
    }

    func testOldStateWithoutRoutesStillDecodes() throws {
        let json = #"{"servers":[],"subscriptions":[],"routing":{"dnsProvider":"CLOUDFLARE","dnsCustomAddress":"","geoMode":"OFF","geoCountries":["ru"],"mtu":"AUTO","tlsFragment":"OFF","ipv6":"BLOCK","netMode":"OFF","netRules":[]},"app":{"killSwitch":false,"onDemand":false,"appLock":false,"hideSecrets":true,"theme":"AMBIENT","hotspotEnabled":false,"hotspotPort":10808,"hotspotUser":"hydra","hotspotPassword":""},"routingProfiles":[]}"#
        let s = try JSONDecoder().decode(HydraState.self, from: Data(json.utf8))
        XCTAssertNil(s.routing.routes)
        XCTAssertEqual(s.routing.routeConfig, RouteConfig())
        XCTAssertEqual(s.routing.geoSettings, GeoSettings())
    }

    // MARK: - ByeDPI

    func testDpiArgsDropDangerousAndOwnKeys() {
        XCTAssertEqual(DpiArgs.build("ciadpi --ip 0.0.0.0 -p 1 --daemon -D -h -o1 -d3+s -w /tmp/pid -n {sni}", port: 10880),
                       ["-i", "127.0.0.1", "-p", "10880", "-o1", "-d3+s", "-n", "max.ru"])
        XCTAssertEqual(DpiArgs.sanitize("-H 'a b'"), ["-H", "a b"])
        XCTAssertEqual(DpiArgs.build("", port: 1), ["-i", "127.0.0.1", "-p", "1", "-o", "2", "-d", "2"])
    }

    func testEveryPresetIsUsable() {
        XCTAssertEqual(DpiStrategies.presets.count, 60)
        for s in DpiStrategies.presets {
            XCTAssertTrue(DpiArgs.isUsable(s), s)
            XCTAssertFalse(DpiArgs.sanitize(s).contains { $0.contains("{sni}") }, s)
        }
        XCTAssertFalse(DpiProbe.sites(groups: [], extra: "").isEmpty)
        XCTAssertEqual(DpiProbe.sites(groups: ["youtube"], extra: "a.com").map(\.name), ["youtube", "custom"])
    }

    func testByeDpiLinkRoundTrip() throws {
        let p = try XCTUnwrap(ByeDpiLink.parse("byedpi://?s=-o%202%20-d%203%2Bs#My%20DPI"))
        XCTAssertEqual(p.name, "My DPI")
        XCTAssertEqual(p.serverProtocol, .byedpi)
        XCTAssertEqual(p.extraObject["strategy"] as? String, "-o 2 -d 3+s")
        XCTAssertEqual(ByeDpiLink.parse(ByeDpiLink.build(p))?.extraObject["strategy"] as? String, "-o 2 -d 3+s")
        XCTAssertEqual(ByeDpiLink.parse("byedpi://")?.extraObject["strategy"] as? String, DpiStrategies.defaultStrategy)
        XCTAssertEqual(ServerProtocol.byedpi.engine, .byeDpi)
        XCTAssertNotNil(LinkParser.parseLine("byedpi://?s=-o1"))
    }
}

final class GeoTests: XCTestCase {
    private func srs(version: UInt8 = 2, size: Int = 64) -> Data {
        var b = [UInt8](repeating: 1, count: size)
        b[0] = 0x53; b[1] = 0x52; b[2] = 0x53; b[3] = version
        return Data(b)
    }
    private func store() -> GeoStore {
        GeoStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent("geo-\(UUID().uuidString)"))
    }

    func testSha256KnownVectors() {
        XCTAssertEqual(Sha256.hex(Data()), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(Sha256.hex(Data("abc".utf8)), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testInstallResolveUnchangedRejectAndShrink() {
        let s = store()
        XCTAssertNil(s.resolve(.ip, "ru"))
        XCTAssertEqual(s.install(.ip, "ru", srs(size: 10_000), source: false, from: "a").outcome, .installed)
        XCTAssertNotNil(s.resolve(.ip, "ru"))
        XCTAssertEqual(s.install(.ip, "ru", srs(size: 10_000), source: false, from: "a").outcome, .unchanged)
        XCTAssertEqual(s.install(.ip, "ru", srs(size: 500), source: false, from: "a").outcome, .rejected)       // подозрительно мала
        XCTAssertEqual(s.install(.ip, "x", Data("<html>blocked</html>".utf8), source: false, from: "a").outcome, .rejected)
        XCTAssertEqual(s.install(.ip, "x", srs(version: 9), source: false, from: "a").outcome, .rejected)
        XCTAssertEqual(s.entries().count, 1)
    }

    func testRollbackAndCorruption() throws {
        let s = store()
        var v2 = srs(size: 1200); v2[10] = 77
        _ = s.install(.ip, "ru", srs(size: 1000), source: false, from: "a")
        _ = s.install(.ip, "ru", v2, source: false, from: "a")
        XCTAssertTrue(try XCTUnwrap(s.entries().first).hasPrev)
        XCTAssertTrue(s.rollback(.ip, "ru"))
        XCTAssertEqual(try Data(contentsOf: try XCTUnwrap(s.resolve(.ip, "ru"))).count, 1000)
        XCTAssertFalse(s.rollback(.ip, "ru"))
        let f = try XCTUnwrap(s.resolve(.ip, "ru"))
        try (try Data(contentsOf: f) + Data([0])).write(to: f)        // размер перестал совпадать с записью
        XCTAssertNil(s.resolve(.ip, "ru"))
    }

    func testListToSourceAndTraversalIsHarmless() throws {
        let j = try XCTUnwrap(try JSONSerialization.jsonObject(with: GeoStore.listToSource("# c\n10.0.0.0/8\n1.2.3.4\n", .ip)) as? [String: Any])
        let rule = try XCTUnwrap((j["rules"] as? [[String: Any]])?.first)
        XCTAssertEqual(rule["ip_cidr"] as? [String], ["10.0.0.0/8", "1.2.3.4/32"])
        let s = store()
        XCTAssertEqual(s.install(.site, "../../evil", srs(), source: false, from: "a").outcome, .installed)
        XCTAssertEqual(s.entries().first?.path.contains(".."), false)
    }

    // protobuf .dat
    private func varint(_ v: UInt64) -> [UInt8] { var x = v; var o: [UInt8] = []; while x >= 0x80 { o.append(UInt8(x & 0x7f) | 0x80); x >>= 7 }; o.append(UInt8(x)); return o }
    private func ld(_ num: Int, _ b: [UInt8]) -> [UInt8] { varint(UInt64(num << 3 | 2)) + varint(UInt64(b.count)) + b }
    private func vi(_ num: Int, _ v: UInt64) -> [UInt8] { varint(UInt64(num << 3)) + varint(v) }

    func testDatConverter() throws {
        func cidr(_ ip: [UInt8], _ p: UInt64) -> [UInt8] { ld(2, ld(1, ip) + vi(2, p)) }
        let ru = ld(1, Array("RU".utf8)) + cidr([5, 8, 0, 0], 16) + cidr([77, 88, 0, 0], 18)
        let us = ld(1, Array("US".utf8)) + cidr([8, 8, 8, 0], 24)
        let dat = Data(ld(1, us) + ld(1, ru))
        let out = try XCTUnwrap(DatConverter.extract(dat, kind: .ip, name: "ru"))
        let rule = try XCTUnwrap(((try JSONSerialization.jsonObject(with: out) as? [String: Any])?["rules"] as? [[String: Any]])?.first)
        XCTAssertEqual(rule["ip_cidr"] as? [String], ["5.8.0.0/16", "77.88.0.0/18"])
        XCTAssertNil(DatConverter.extract(dat, kind: .ip, name: "zz"))

        func dom(_ t: UInt64, _ v: String) -> [UInt8] { ld(2, vi(1, t) + ld(2, Array(v.utf8))) }
        let yt = ld(1, Array("YOUTUBE".utf8)) + dom(2, "youtube.com") + dom(3, "youtu.be") + dom(0, "ytimg")
        let site = try XCTUnwrap(DatConverter.extract(Data(ld(1, yt)), kind: .site, name: "youtube"))
        let sr = try XCTUnwrap(((try JSONSerialization.jsonObject(with: site) as? [String: Any])?["rules"] as? [[String: Any]])?.first)
        XCTAssertEqual(sr["domain_suffix"] as? [String], ["youtube.com"])
        XCTAssertEqual(sr["domain"] as? [String], ["youtu.be"])
        XCTAssertEqual(sr["domain_keyword"] as? [String], ["ytimg"])
    }

    private struct FakeHttp: GeoHttp {
        let files: [String: Data]
        func get(_ url: String, maxBytes: Int) async throws -> Data {
            guard let d = files[url] else { throw URLError(.fileDoesNotExist) }
            return d
        }
    }

    func testUpdaterUsesMirrorAndKeepsOldBaseOnFailure() async {
        let s = store()
        let mirror = GeoSources.metacubex.ip[1].replacingOccurrences(of: "{name}", with: "ru")
        let rep = await GeoUpdater(store: s, http: FakeHttp(files: [mirror: srs(size: 500)])).update(GeoSettings(), wanted: [(.ip, "ru")])
        XCTAssertEqual(rep.installed, 1)
        XCTAssertEqual(rep.failed, 0)
        let bad = await GeoUpdater(store: s, http: FakeHttp(files: [:])).update(GeoSettings(), wanted: [(.ip, "ru"), (.site, "youtube")])
        XCTAssertEqual(bad.failed, 2)
        XCTAssertNotNil(s.resolve(.ip, "ru"))
    }

    func testUpdaterConvertsDatOncePerFile() async throws {
        func cidr(_ ip: [UInt8], _ p: UInt64) -> [UInt8] { ld(2, ld(1, ip) + vi(2, p)) }
        let dat = Data(ld(1, ld(1, Array("RU".utf8)) + cidr([5, 8, 0, 0], 16)) + ld(1, ld(1, Array("US".utf8)) + cidr([8, 8, 8, 0], 24)))
        var set = GeoSettings()
        set.ipSource = GeoSources.v2flyDat.id
        let s = store()
        let rep = await GeoUpdater(store: s, http: FakeHttp(files: [GeoSources.v2flyDat.ip[0]: dat])).update(set, wanted: [(.ip, "ru"), (.ip, "us")])
        XCTAssertEqual(rep.installed, 2)
        XCTAssertTrue(try XCTUnwrap(s.resolve(.ip, "us")).lastPathComponent.hasSuffix(".json"))
    }

    func testCatalogTemplatesHaveName() {
        for src in GeoSources.all where src.format == .srs {
            for k in GeoKind.allCases { XCTAssertTrue(src.templates(k).allSatisfy { $0.contains("{name}") }, src.id) }
        }
    }
}
