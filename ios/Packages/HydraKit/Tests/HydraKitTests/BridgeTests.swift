import XCTest
@testable import HydraKit

/// olcRTC и OpenFlux на iOS (0.7.10): разбор ссылок (тот же формат, что на Android и ПК) и конфиг
/// sing-box-моста к локальному SOCKS5 клиента.
final class BridgeTests: XCTestCase {
    func testOlcRtcLink() throws {
        let key = String(repeating: "ab", count: 32)
        let p = try XCTUnwrap(LinkParser.parseLine("olcrtc://telemost?vp8channel<vp8-fps=25&vp8-batch=32>@room42#\(key)$Мой узел"))
        XCTAssertEqual(p.protocolId, "olcrtc")
        XCTAssertEqual(p.address, "room42")
        XCTAssertEqual(p.uuidOrPassword, key)
        XCTAssertEqual(p.transport, "vp8channel")
        XCTAssertEqual(p.name, "Мой узел")
        XCTAssertEqual(p.extraObject["provider"] as? String, "telemost")
        XCTAssertEqual((p.extraObject["params"] as? [String: Any])?["vp8-fps"] as? String, "25")
        XCTAssertEqual(p.serverProtocol?.engine, .socksBridge)
        XCTAssertTrue(p.serverProtocol?.supportedOnIOS == true)

        let plain = try XCTUnwrap(LinkParser.parseLine("olcrtc://jitsi@meet-room#\(key)"))
        XCTAssertEqual(plain.transport, "datachannel")
        XCTAssertNil(LinkParser.parseLine("olcrtc://telemost?datachannel@room42"))   // без ключа
    }

    func testOpenFluxLinks() throws {
        let d = try XCTUnwrap(LinkParser.parseLine("openflux://direct?url=10.0.0.5%3A9000&key=s3cret#Узел"))
        XCTAssertEqual(d.protocolId, "openflux")
        XCTAssertEqual(d.transport, "direct")
        XCTAssertEqual(d.address, "10.0.0.5:9000")
        XCTAssertEqual(d.uuidOrPassword, "s3cret")
        XCTAssertEqual(d.name, "Узел")
        XCTAssertEqual(d.extraObject["codec"] as? String, "batched")

        let max = try XCTUnwrap(LinkParser.parseLine("openflux://oneme?maxToken=T&maxUid=7"))
        XCTAssertEqual(max.extraObject["maxToken"] as? String, "T")
        XCTAssertNil(LinkParser.parseLine("openflux://direct?url=1.2.3.4%3A1"))   // direct без ключа
        XCTAssertNil(LinkParser.parseLine("openflux://unknown?url=x"))
    }

    func testBridgeConfig() throws {
        let p = try XCTUnwrap(LinkParser.parseLine("openflux://yandex?url=https%3A%2F%2Fdisk.example%2Fdoc&key=k"))
        var o = SingBoxConfigBuilder.Options()
        o.socksBridgePort = 10809
        let cfg = try SingBoxConfigBuilder.build(p, o)
        let proxy = try XCTUnwrap((cfg["outbounds"] as? [[String: Any]])?.first)
        XCTAssertEqual(proxy["type"] as? String, "socks")
        XCTAssertEqual(proxy["server"] as? String, "127.0.0.1")
        XCTAssertEqual(proxy["server_port"] as? Int, 10809)
    }
}
