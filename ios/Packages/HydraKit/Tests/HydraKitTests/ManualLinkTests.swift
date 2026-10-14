import XCTest
@testable import HydraKit

/// Ручной ввод (0.8.0-rc4): ссылка из полей разбирается тем же `LinkParser`, что и импорт, в профиль нужного протокола.
final class ManualLinkTests: XCTestCase {
    private func parse(_ id: String, _ v: [String: String], name: String = "N") -> ServerProfile? {
        guard let link = ManualLink.build(id, name: name, values: v) else { return nil }
        return LinkParser.parseLine(link)
    }

    func testAllProtocols() {
        let cases: [String: [String: String]] = [
            "vless": ["address": "203.0.113.5", "port": "443", "uuid": "u", "pbk": "PBK"],
            "vmess": ["address": "h", "port": "443", "uuid": "u"],
            "trojan": ["address": "h", "port": "443", "password": "p@ss word"],
            "ss": ["address": "h", "port": "8388", "password": "pw"],
            "hysteria2": ["address": "h", "port": "443", "password": "pw"],
            "tuic": ["address": "h", "port": "443", "uuid": "u", "password": "pw"],
            "sstp": ["address": "h", "port": "443", "user": "ivan", "password": "pw"],
            "openflux": ["transport": "direct", "url": "203.0.113.5:24680", "key": String(repeating: "k", count: 32)],
            "olcrtc": ["room": "room", "key": String(repeating: "a", count: 64)],
            "byedpi": ["strategy": "-o1"],
        ]
        for (id, v) in cases { XCTAssertEqual(parse(id, v)?.protocolId, id, id) }
        XCTAssertEqual(parse("trojan", cases["trojan"]!)?.uuidOrPassword, "p@ss word")
        XCTAssertEqual(parse("vless", cases["vless"]!)?.security, "reality")
        XCTAssertNil(ManualLink.build("vless", name: "", values: ["address": "h", "port": "443", "uuid": "u"]), "Reality без pbk")
    }

    func testAmneziaFromFields() {
        let conf = ManualLink.build("awg", name: "", values: ["privatekey": "cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5MTI=", "ifaddress": "10.8.1.2/32",
            "publickey": "cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHViMTI=", "endpoint": "203.0.113.5:51820", "awgparams": "Jc = 4\nJmin = 10\nJmax = 50\nH1 = 5"])
        XCTAssertEqual(LinkParser.parseLine(conf!)?.protocolId, "awg")
        XCTAssertFalse(ManualLink.missing("wireguard", [:]).isEmpty)
        XCTAssertTrue(ManualLink.missing("wireguard", ["conf": "[Interface]"]).isEmpty)
    }
}
