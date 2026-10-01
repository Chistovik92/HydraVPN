import XCTest
@testable import HydraKit

final class BotTests: XCTestCase {
    private func item(_ panel: String, url: String? = nil, enabled: Bool = true, state: String = "ok") -> BotSubscription {
        BotSubscription(panel: panel, title: "T\(panel)", state: state, enabled: enabled, url: url ?? "https://p/\(panel)")
    }

    private func sub(_ id: Int64, _ url: String, panel: String? = nil, lastError: String = "") -> Subscription {
        Subscription(id: id, name: "s\(id)", url: url, lastError: lastError, botPanel: panel)
    }

    func testServerAddressNormalization() {
        XCTAssertEqual(BotJSON.normalizeServer("radar.example.org/"), "https://radar.example.org")
        XCTAssertEqual(BotJSON.normalizeServer("http://192.168.1.5:8080"), "http://192.168.1.5:8080")
        XCTAssertNil(BotJSON.normalizeServer("http://radar.example.org"))
        XCTAssertNil(BotJSON.normalizeServer("https://user:pw@radar.example.org"))
        XCTAssertNil(BotJSON.normalizeServer("ftp://x"))
    }

    func testParsesSubscriptionsAndProfile() throws {
        let subs = try BotJSON.subscriptions(Data(#"{"subscriptions":[{"panel":"3","title":"","link_kind":"key","state":"ok","enabled":true,"url":"u"}]}"#.utf8))
        XCTAssertEqual(subs.first?.title, "#3")
        XCTAssertFalse(subs[0].importable)   // одиночный ключ не подписка
        let p = try BotJSON.profile(Data(#"{"user_id":"7","username":"vasya","vpn":{"state":"active","panels":2}}"#.utf8))
        XCTAssertEqual(p.username, "vasya")
        XCTAssertEqual(p.panels, 2)
    }

    func testPlannerFollowsPanelWhenUrlChanges() {
        let steps = BotSyncPlanner.plan(existing: [sub(5, "https://old", panel: "1")], items: [item("1", url: "https://new")])
        XCTAssertEqual(steps, [.update(subId: 5, item("1", url: "https://new"))])
    }

    func testPlannerAddsAdoptsDisablesOnce() {
        XCTAssertEqual(BotSyncPlanner.plan(existing: [], items: [item("1")]), [.add(item("1"))])
        XCTAssertEqual(BotSyncPlanner.plan(existing: [sub(5, "https://p/1")], items: [item("1")]), [.update(subId: 5, item("1"))])
        let off = item("1", enabled: false)
        XCTAssertEqual(BotSyncPlanner.plan(existing: [sub(5, "u", panel: "1")], items: [off]), [.disable(subId: 5)])
        XCTAssertTrue(BotSyncPlanner.plan(existing: [sub(5, "u", panel: "1", lastError: BotSyncPlanner.disabledMark)], items: [off]).isEmpty)
        XCTAssertTrue(BotSyncPlanner.plan(existing: [sub(5, "u", panel: "1")], items: [item("1", state: "panel_error")]).isEmpty)
    }

    func testPrivateDnsOnlyWithValidHttpsUrl() {
        XCTAssertNil(BotJSON.validPrivateDns(nil))
        XCTAssertNil(BotJSON.validPrivateDns("${HYDRA_PRIVATE_DNS}"))
        XCTAssertNotNil(BotJSON.validPrivateDns("https://dns.hydravpn.us/dns-query/0123456789abcdef"))
        var r = RoutingSettings()
        r.dnsProvider = .hydra
        XCTAssertEqual(r.resolvedDns(), DnsEndpoint.doh("1.1.1.1"))   // без адреса — Cloudflare
        r.hydraDnsUrl = "https://dns.hydravpn.us/dns-query/0123456789abcdef"
        XCTAssertEqual(r.resolvedDns()?.host, "dns.hydravpn.us")
    }

    func testOldStateWithoutBotFieldsStillDecodes() throws {
        let json = #"{"servers":[],"subscriptions":[{"id":1,"name":"a","url":"u","userAgent":"","lastUpdated":0,"autoUpdateHours":12,"serverTitle":"","uploadBytes":0,"downloadBytes":0,"totalBytes":0,"expireAt":0,"supportUrl":"","autoUpdate":true,"collapsed":false,"lastError":""}],"routing":{"dnsProvider":"CLOUDFLARE","dnsCustomAddress":"","geoMode":"OFF","geoCountries":["ru"],"mtu":"AUTO","tlsFragment":"OFF","ipv6":"BLOCK","netMode":"OFF","netRules":[]},"app":{"killSwitch":false,"onDemand":false,"appLock":false,"hideSecrets":true,"theme":"AMBIENT","hotspotEnabled":false,"hotspotPort":10808,"hotspotUser":"hydra","hotspotPassword":""},"routingProfiles":[]}"#
        let s = try JSONDecoder().decode(HydraState.self, from: Data(json.utf8))
        XCTAssertNil(s.bot)
        XCTAssertNil(s.subscriptions[0].botPanel)
    }
}
