import XCTest
@testable import HydraKit

final class SingBoxConfigBuilderTests: XCTestCase {
    let reality = ServerProfile(
        name: "R", protocolId: "vless", address: "203.0.113.10", port: 443,
        uuidOrPassword: "b831381d-6324-4d53-ad4f-8cda48b30811", flow: "xtls-rprx-vision",
        sni: "www.microsoft.com", security: "reality", extra: #"{"reality_pbk":"PBK","reality_sid":"ab"}"#)

    private func cfg(_ p: ServerProfile, _ tweak: (inout SingBoxConfigBuilder.Options) -> Void = { _ in }) throws -> [String: Any] {
        var o = SingBoxConfigBuilder.Options()
        tweak(&o)
        return try SingBoxConfigBuilder.build(p, o)
    }

    private func rules(_ c: [String: Any]) -> [[String: Any]] { (c["route"] as? [String: Any])?["rules"] as? [[String: Any]] ?? [] }

    func testRealityOutbound() throws {
        let c = try cfg(reality)
        let proxy = try XCTUnwrap((c["outbounds"] as? [[String: Any]])?.first)
        XCTAssertEqual(proxy["type"] as? String, "vless")
        XCTAssertEqual(proxy["flow"] as? String, "xtls-rprx-vision")
        let tls = try XCTUnwrap(proxy["tls"] as? [String: Any])
        let r = try XCTUnwrap(tls["reality"] as? [String: Any])
        XCTAssertEqual(r["public_key"] as? String, "PBK")
        XCTAssertEqual(tls["server_name"] as? String, "www.microsoft.com")
    }

    func testTunOwnsRoutesOnIOS() throws {
        let tun = try XCTUnwrap((try cfg(reality)["inbounds"] as? [[String: Any]])?.first)
        XCTAssertEqual(tun["auto_route"] as? Bool, true)
        XCTAssertEqual((tun["address"] as? [String])?.count, 2, "IPv6-адрес нужен всегда, иначе IPv6 идёт мимо туннеля")
    }

    func testIpv6BlockRejectsAndDnsIpv4Only() throws {
        let block = try cfg(reality)
        XCTAssertEqual((block["dns"] as? [String: Any])?["strategy"] as? String, "ipv4_only")
        XCTAssertTrue(rules(block).contains { ($0["ip_version"] as? Int) == 6 && ($0["action"] as? String) == "reject" })
        let open = try cfg(reality) { $0.routing.ipv6 = .enable }
        XCTAssertFalse(rules(open).contains { $0["ip_version"] != nil })
        XCTAssertEqual((open["dns"] as? [String: Any])?["strategy"] as? String, "prefer_ipv4")
    }

    func testSplitIncludeMakesFinalDirect() throws {
        let c = try cfg(reality) {
            $0.routing.netMode = .include
            $0.routing.netRules = [NetworkRule(type: .domainSuffix, value: "example.com")]
        }
        XCTAssertEqual((c["route"] as? [String: Any])?["final"] as? String, "direct")
        XCTAssertTrue(rules(c).contains { ($0["domain_suffix"] as? [String]) == ["example.com"] && ($0["outbound"] as? String) == "proxy" })
    }

    func testGeoViaProxy() throws {
        let c = try cfg(reality) {
            $0.routing.geoMode = .viaProxy
            $0.geo = [.init(code: "ru", geoipPath: "/g/ru.srs", geositePath: "/s/ru.srs")]
        }
        let route = try XCTUnwrap(c["route"] as? [String: Any])
        XCTAssertEqual((route["rule_set"] as? [[String: Any]])?.count, 2)
        XCTAssertEqual(route["final"] as? String, "direct")
    }

    func testPrivateDohNeedsBootstrap() throws {
        let c = try cfg(reality) { $0.routing.dnsProvider = .custom; $0.routing.dnsCustomAddress = "https://dns.hydravpn.us/q" }
        let servers = try XCTUnwrap((c["dns"] as? [String: Any])?["servers"] as? [[String: Any]])
        XCTAssertEqual(servers.first?["domain_resolver"] as? String, "bootstrap")
        XCTAssertTrue(servers.contains { $0["tag"] as? String == "bootstrap" })
    }

    func testUnsupportedProtocolThrows() {
        let sstp = ServerProfile(name: "S", protocolId: "sstp", address: "s", port: 443)
        XCTAssertThrowsError(try cfg(sstp))
    }

    func testJSONSerializes() throws {
        let json = try SingBoxConfigBuilder.buildJSON(reality, .init())
        XCTAssertTrue(json.contains("\"hijack-dns\""))
    }
}

final class DisplayTests: XCTestCase {
    func testMask() {
        XCTAssertEqual(
            SecretMask.mask("vless://0f3c1a2b-aaaa-bbbb-cccc-123456789abc@de.example:443?security=reality&pbk=AbC&sid=1a&sni=w.example#DE"),
            "vless://••••@de.example:443?security=reality&pbk=••••&sid=••••&sni=w.example#DE")
        XCTAssertEqual(SecretMask.mask("https://panel.example/sub/Zx81kQpLmN0w"), "https://panel.example/sub/••••")
        XCTAssertEqual(SecretMask.mask("vmess://eyJhZGQiOiIxLjIuMy40IiwicG9ydCI6NDQzfQ=="), "vmess://••••")
    }

    func testLocation() {
        let names = ["de": "Германия", "nl": "Нидерланды"]
        func label(_ n: String, flag: String = "🌐") -> String {
            ServerLocation.label(ServerProfile(name: n, protocolId: "vless", address: "1.2.3.4", port: 1, flag: flag)) { names[$0] ?? $0 }
        }
        XCTAssertEqual(label("🇩🇪 Frankfurt-1"), "🇩🇪 Германия · Frankfurt-1")
        XCTAssertEqual(label("🇳🇱 Нидерланды"), "🇳🇱 Нидерланды")
        XCTAssertEqual(label("DE-1", flag: "🇩🇪"), "🇩🇪 Германия · DE-1")
        XCTAssertEqual(label("My server"), "My server")
        XCTAssertEqual(ServerLocation.isoCode("🇩🇪"), "de")
    }
}

final class BackupTests: XCTestCase {
    /// Копия, сделанная Android-версией (формат BackupCodec.kt): должна открываться на iOS.
    let androidBackup = #"""
    {"format":"hydra-backup","version":1,"appVersion":"0.6.23","createdAt":1,
     "prefs":{"routing_settings":{"dns_provider":{"t":"s","v":"ADGUARD"},"ipv6_mode":{"t":"s","v":"ENABLE"},
                                   "geo_routing_mode":{"t":"s","v":"RU_DIRECT"}},
              "settings":{"split_net_mode":{"t":"s","v":"EXCLUDE"},
                          "split_net_rules":{"t":"s","v":"[{\"type\":\"DOMAIN\",\"value\":\"bank.ru\"}]"},
                          "split_mode":{"t":"s","v":"INCLUDE"}},
              "vpn_settings":{"kill_switch":{"t":"b","v":true},"last_server_id":{"t":"l","v":5}},
              "theme_settings":{"theme_mode":{"t":"s","v":"STEALTH"}}},
     "servers":[{"id":5,"name":"🇩🇪 DE","protocolId":"vless","address":"de.example","port":443,
                 "uuidOrPassword":"u","extra":"{}","subscriptionId":null,"pingMs":12,"flag":"🌐"}],
     "subscriptions":[{"id":2,"name":"S","url":"https://p.example/s","autoUpdateHours":6}]}
    """#

    func testReadsAndroidBackup() throws {
        let s = try BackupCodec.import(Data(androidBackup.utf8), into: HydraState())
        XCTAssertEqual(s.servers.first?.id, 5)
        XCTAssertNil(s.servers.first?.subscriptionId)
        XCTAssertEqual(s.subscriptions.first?.autoUpdateHours, 6)
        XCTAssertEqual(s.routing.dnsProvider, .adguard)
        XCTAssertEqual(s.routing.ipv6, .enable)
        XCTAssertEqual(s.routing.geoMode, .direct, "старое значение Android RU_DIRECT")
        XCTAssertEqual(s.routing.netRules, [NetworkRule(type: .domain, value: "bank.ru")])
        XCTAssertTrue(s.app.killSwitch)
        XCTAssertEqual(s.app.lastServerId, 5)
    }

    func testRoundTrip() throws {
        var s = try BackupCodec.import(Data(androidBackup.utf8), into: HydraState())
        s.routing.tlsFragment = .record
        let again = try BackupCodec.import(try BackupCodec.export(s, appVersion: "ios-test"), into: HydraState())
        XCTAssertEqual(again.servers, s.servers)
        XCTAssertEqual(again.routing, s.routing)
        XCTAssertEqual(again.app.lastServerId, 5)
    }

    func testRejectsForeignFile() {
        XCTAssertThrowsError(try BackupCodec.import(Data("{}".utf8), into: HydraState()))
        XCTAssertThrowsError(try BackupCodec.import(Data(#"{"format":"hydra-backup","version":99,"servers":[]}"#.utf8), into: HydraState()))
    }
}
