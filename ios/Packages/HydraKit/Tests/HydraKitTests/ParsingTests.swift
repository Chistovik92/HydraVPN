import XCTest
@testable import HydraKit

final class LinkParserTests: XCTestCase {
    func testVlessReality() throws {
        let p = try XCTUnwrap(LinkParser.parseLine(
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@de.example:8443?type=tcp&security=reality&pbk=PUBKEY&sid=ab12&sni=www.microsoft.com&fp=firefox&flow=xtls-rprx-vision#%F0%9F%87%A9%F0%9F%87%AA%20Frankfurt"))
        XCTAssertEqual(p.protocolId, "vless")
        XCTAssertEqual(p.address, "de.example")
        XCTAssertEqual(p.port, 8443)
        XCTAssertEqual(p.uuidOrPassword, "b831381d-6324-4d53-ad4f-8cda48b30811")
        XCTAssertEqual(p.security, "reality")
        XCTAssertEqual(p.flow, "xtls-rprx-vision")
        XCTAssertEqual(p.fingerprint, "firefox")
        XCTAssertEqual(p.name, "🇩🇪 Frankfurt")
        XCTAssertEqual(p.extraObject["reality_pbk"] as? String, "PUBKEY")
        XCTAssertEqual(p.extraObject["reality_sid"] as? String, "ab12")
    }

    func testShadowsocksSip002AndLegacy() throws {
        // SIP002: base64(method:password)@host:port
        let creds = Data("chacha20-ietf-poly1305:secret".utf8).base64EncodedString()
        let a = try XCTUnwrap(LinkParser.parseLine("ss://\(creds)@1.2.3.4:8388#A"))
        XCTAssertEqual(a.address, "1.2.3.4"); XCTAssertEqual(a.port, 8388); XCTAssertEqual(a.uuidOrPassword, "secret")
        XCTAssertEqual(a.extraObject["method"] as? String, "chacha20-ietf-poly1305")
        // legacy: base64(method:password@host:port)
        let all = Data("aes-256-gcm:pw@example.org:443".utf8).base64EncodedString()
        let b = try XCTUnwrap(LinkParser.parseLine("ss://\(all)#B"))
        XCTAssertEqual(b.address, "example.org"); XCTAssertEqual(b.port, 443); XCTAssertEqual(b.uuidOrPassword, "pw")
    }

    func testVmessBase64Json() throws {
        let json = #"{"v":"2","ps":"VM-1","add":"vm.example","port":"443","id":"uuid-1","aid":"0","net":"ws","path":"/ws","tls":"tls","sni":"cdn.example"}"#
        let p = try XCTUnwrap(LinkParser.parseLine("vmess://" + Data(json.utf8).base64EncodedString()))
        XCTAssertEqual(p.name, "VM-1"); XCTAssertEqual(p.port, 443); XCTAssertEqual(p.transport, "ws")
        XCTAssertEqual(p.security, "tls"); XCTAssertEqual(p.sni, "cdn.example")
    }

    func testHysteria2AndTuic() throws {
        let h = try XCTUnwrap(LinkParser.parseLine("hy2://pass@h.example:443?sni=h.example&obfs=salamander&obfs-password=ob#H"))
        XCTAssertEqual(h.protocolId, "hysteria2")
        XCTAssertEqual(h.extraObject["obfs_password"] as? String, "ob")
        let t = try XCTUnwrap(LinkParser.parseLine("tuic://uuid-2:pw2@t.example:443?congestion_control=cubic#T"))
        XCTAssertEqual(t.uuidOrPassword, "uuid-2")
        XCTAssertEqual(t.extraObject["password"] as? String, "pw2")
        XCTAssertEqual(t.extraObject["congestion_control"] as? String, "cubic")
    }

    func testWireGuardConfAndAmneziaDetection() throws {
        let conf = """
        [Interface]
        PrivateKey = PRIV=
        Address = 10.8.0.2/32
        Jc = 4
        S3 = 10
        [Peer]
        PublicKey = PUB=
        Endpoint = 203.0.113.5:51820
        AllowedIPs = 0.0.0.0/0
        """
        let p = try XCTUnwrap(LinkParser.parseLine(conf))
        XCTAssertEqual(p.protocolId, "awg")
        XCTAssertEqual(p.address, "203.0.113.5"); XCTAssertEqual(p.port, 51820)
        XCTAssertEqual(p.uuidOrPassword, "PRIV=")
        XCTAssertEqual(p.extraObject["awg_version"] as? String, "2.0")
        let plain = try XCTUnwrap(LinkParser.parseLine(conf.replacingOccurrences(of: "Jc = 4\n", with: "")
            .replacingOccurrences(of: "S3 = 10\n", with: "")))
        XCTAssertEqual(plain.protocolId, "wireguard")
    }

    func testBase64SubscriptionSkipsGarbage() {
        let list = "vless://u@a.example:443#A\nnot a link\ntrojan://p@b.example:443#B\n"
        let profiles = LinkParser.parseSubscription(Data(list.utf8).base64EncodedString(), subscriptionId: 7)
        XCTAssertEqual(profiles.map(\.name), ["A", "B"])
        XCTAssertTrue(profiles.allSatisfy { $0.subscriptionId == 7 })
    }
}

final class SubscriptionTests: XCTestCase {
    func testHeaders() {
        let h = ["subscription-userinfo": "upload=1; download=2.0; total=30; expire=1767225600",
                 "profile-title": "base64:" + Data("Hydra VPN".utf8).base64EncodedString(),
                 "profile-update-interval": "6"]
        let info = SubscriptionHeaders.parse { h[$0] }
        XCTAssertEqual(info.title, "Hydra VPN")
        XCTAssertEqual(info.download, 2); XCTAssertEqual(info.total, 30); XCTAssertEqual(info.updateHours, 6)
    }

    func testSyncKeepsIdsAndPing() {
        var s = HydraState()
        s.subscriptions = [Subscription(id: 1, name: "S", url: "https://x")]
        s.servers = [
            ServerProfile(id: 10, name: "old A", protocolId: "vless", address: "a", port: 1, uuidOrPassword: "u", subscriptionId: 1, pingMs: 42),
            ServerProfile(id: 11, name: "gone", protocolId: "vless", address: "g", port: 1, uuidOrPassword: "u", subscriptionId: 1),
        ]
        let incoming = [
            ServerProfile(name: "new A name", protocolId: "vless", address: "A", port: 1, uuidOrPassword: "u"),
            ServerProfile(name: "fresh", protocolId: "trojan", address: "f", port: 2, uuidOrPassword: "p"),
        ]
        let result = SubscriptionClient.Result(profiles: incoming, info: .init())
        XCTAssertNoThrow(try SubscriptionClient.apply(result, subscriptionId: 1, to: &s))
        XCTAssertEqual(Set(s.servers.map(\.name)), ["new A name", "fresh"])
        let a = s.servers.first { $0.name == "new A name" }
        XCTAssertEqual(a?.id, 10); XCTAssertEqual(a?.pingMs, 42)
    }

    func testEmptyResponseKeepsServers() {
        var s = HydraState()
        s.subscriptions = [Subscription(id: 1, name: "S", url: "https://x")]
        s.servers = [ServerProfile(id: 1, name: "keep", protocolId: "vless", address: "a", port: 1, subscriptionId: 1)]
        XCTAssertThrowsError(try SubscriptionClient.apply(.init(profiles: [], info: .init()), subscriptionId: 1, to: &s))
        XCTAssertEqual(s.servers.count, 1)
        XCTAssertFalse(s.subscriptions[0].lastError.isEmpty)
    }

    func testImportDetector() {
        XCTAssertEqual(ImportDetector.classify("https://panel.example/sub/abc#My"),
                       .subscriptionURL(url: "https://panel.example/sub/abc", nameHint: "My"))
        XCTAssertEqual(ImportDetector.classify("sing-box://import-remote-profile?url=https%3A%2F%2Fp.example%2Fs"),
                       .subscriptionURL(url: "https://p.example/s", nameHint: "p.example"))
        XCTAssertEqual(ImportDetector.classify(#"{"outbounds":[]}"#), .unsupportedJSON)
        XCTAssertEqual(ImportDetector.classify("   "), .empty)
        if case .servers(let p) = ImportDetector.classify("vless://u@a:1#A") { XCTAssertEqual(p.count, 1) } else { XCTFail() }
    }

    func testDnsEndpoint() {
        XCTAssertEqual(DnsEndpoint.parse("1.1.1.1"), DnsEndpoint(type: "https", host: "1.1.1.1"))
        XCTAssertEqual(DnsEndpoint.parse("https://dns.example/dns-query/tok"),
                       DnsEndpoint(type: "https", host: "dns.example", path: "/dns-query/tok"))
        XCTAssertEqual(DnsEndpoint.parse("tls://dns.example:853"), DnsEndpoint(type: "tls", host: "dns.example", port: 853))
        XCTAssertNil(DnsEndpoint.parse("ftp://x"))
        XCTAssertNil(DnsEndpoint.parse("bad host!"))
    }
}
