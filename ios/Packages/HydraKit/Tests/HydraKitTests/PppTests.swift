#if canImport(CommonCrypto) && canImport(CryptoKit)
import XCTest
@testable import HydraKit

private func hex(_ s: String) -> [UInt8] {
    var out: [UInt8] = [], chars = Array(s.replacingOccurrences(of: " ", with: ""))
    while chars.count >= 2 { out.append(UInt8(String(chars[0...1]), radix: 16)!); chars.removeFirst(2) }
    return out
}
private func hexString(_ b: [UInt8]) -> String { b.map { String(format: "%02X", $0) }.joined() }

/// Тест-векторы RFC 2759 §9.2 и RFC 3079 §3.5.
final class MsChapV2Tests: XCTestCase {
    let authCh = hex("5B5D7C7D7B3F2F3E3C2C602132262628")
    let peerCh = hex("21402324255E262A28295F2B3A337C7E")

    func testRfc2759Vectors() {
        XCTAssertEqual(hexString(MsChapV2.ntPasswordHash("clientPass")), "44EBBA8D5312B8D611474411F56989AE")
        XCTAssertEqual(hexString(Md4.digest(MsChapV2.ntPasswordHash("clientPass"))), "41C00C584BD2D91C4017A2A12FA59F3F")
        XCTAssertEqual(hexString(MsChapV2.challengeHash(peerCh, authCh, "User")), "D02E4386BCE91226")
        let a = MsChapV2.authenticate(user: "User", password: "clientPass", authenticatorChallenge: authCh, peerChallenge: peerCh)
        XCTAssertEqual(hexString(a.ntResponse), "82309ECD8D708B5EA08FAA3981CD83544233114A3D85D6DF")
        XCTAssertEqual(a.authenticatorResponse, "S=407A5589115FD0D6209F510FE9C04566932CDA56")
        XCTAssertEqual(hexString(a.masterKey), "FDECE3717A8C838CB388E527AE3CDD31")
    }

    func testMd4Rfc1320() {
        XCTAssertEqual(hexString(Md4.digest(Array("abc".utf8))), "A448017AAF21D8525FC10AE87AA6729D")
        XCTAssertEqual(hexString(Md4.digest([])), "31D6CFE0D16AE931B73C59D7E0C089C0")
    }
}

/// Разговор с «сервером»: проверяем, что кадры клиента соответствуют RFC — именно на этом
/// Android-реализация до 0.6.24 и ломалась бы на живом сервере.
final class PppSessionTests: XCTestCase {
    var sent: [[UInt8]] = []
    var session: PppSession!
    let q = DispatchQueue(label: "ppp-test")

    override func setUp() {
        sent = []
        session = PppSession(user: "User", password: "clientPass", queue: q, send: { [unowned self] in sent.append($0) }, log: { _ in })
    }

    private func control(_ f: [UInt8]) -> (Int, Ppp.Control)? {
        guard let (proto, info) = Ppp.parseFrame(f), let c = Ppp.parseControl(info) else { return nil }
        return (proto, c)
    }

    func testMruIsTwoBytes() throws {
        session.start()
        let (_, req) = try XCTUnwrap(control(sent[0]))
        let mru = try XCTUnwrap(Ppp.parseOptions(req.data).first { $0.type == Ppp.optMRU })
        XCTAssertEqual(mru.value.count, 2)
        XCTAssertEqual(mru.intValue, 1400)
        session.close()
    }

    func testFullNegotiationWithMsChapV2() throws {
        var up: String?
        session.onUp = { ip, _ in up = ip }
        session.start()
        // Сервер: ConfReq с MS-CHAPv2 (3 байта: C223 81) и magic — клиент обязан Ack с теми же опциями.
        let serverOpts = Ppp.encode([Ppp.Option(type: Ppp.optAuth, value: [0xC2, 0x23, 0x81]),
                                     Ppp.Option(type: Ppp.optMagic, value: [1, 2, 3, 4])])
        session.onFrame(Ppp.controlFrame(Ppp.protoLCP, Ppp.confReq, 7, serverOpts))
        let (_, ack) = try XCTUnwrap(control(try XCTUnwrap(sent.last)))
        XCTAssertEqual(ack.code, Ppp.confAck, "MS-CHAPv2 не должен получать Nak")
        XCTAssertEqual(ack.data, serverOpts, "Configure-Ack повторяет опции дословно (RFC 1661 §5.2)")
        session.onFrame(Ppp.controlFrame(Ppp.protoLCP, Ppp.confAck, 0, []))
        XCTAssertEqual(session.phase, .auth)

        // Challenge → Response: Value-Size 49 + 49 байт + имя (RFC 2759 §4).
        session.onFrame(Ppp.controlFrame(Ppp.protoCHAP, 1, 9, [16] + [UInt8](repeating: 0xAB, count: 16) + Array("srv".utf8)))
        let (proto, resp) = try XCTUnwrap(control(try XCTUnwrap(sent.last)))
        XCTAssertEqual(proto, Ppp.protoCHAP)
        XCTAssertEqual(resp.code, 2)
        XCTAssertEqual(resp.data.first, 49)
        XCTAssertEqual(resp.data.count, 1 + 49 + 4)
        XCTAssertEqual(Array(resp.data.suffix(4)), Array("User".utf8))

        session.onFrame(Ppp.controlFrame(Ppp.protoCHAP, 3, 9, Array("S=\(session.lastAuth!.authenticatorResponse.dropFirst(2)) M=ok".utf8)))
        XCTAssertEqual(session.phase, .ipcp)

        // IPCP: сервер присылает свой ConfReq и Nak с нашим адресом.
        session.onFrame(Ppp.controlFrame(Ppp.protoIPCP, Ppp.confReq, 1, Ppp.encode([Ppp.Option(type: Ppp.ipcpAddr, value: [10, 0, 0, 1])])))
        session.onFrame(Ppp.controlFrame(Ppp.protoIPCP, Ppp.confNak, 1, Ppp.encode([
            Ppp.Option(type: Ppp.ipcpAddr, value: [10, 0, 0, 7]), Ppp.Option(type: Ppp.ipcpDNS1, value: [1, 1, 1, 1])])))
        session.onFrame(Ppp.controlFrame(Ppp.protoIPCP, Ppp.confAck, 2, []))
        XCTAssertEqual(up, "10.0.0.7")
        XCTAssertEqual(session.dns, ["1.1.1.1"])
        session.close()
    }

    func testAuthenticatorMismatchTerminates() {
        var down: String?
        session.onDown = { down = $0 }
        session.start()
        session.onFrame(Ppp.controlFrame(Ppp.protoLCP, Ppp.confReq, 1, Ppp.encode([Ppp.Option(type: Ppp.optAuth, value: [0xC2, 0x23, 0x81])])))
        session.onFrame(Ppp.controlFrame(Ppp.protoLCP, Ppp.confAck, 0, []))
        session.onFrame(Ppp.controlFrame(Ppp.protoCHAP, 1, 2, [16] + [UInt8](repeating: 1, count: 16)))
        session.onFrame(Ppp.controlFrame(Ppp.protoCHAP, 3, 2, Array("S=0000000000000000000000000000000000000000".utf8)))
        XCTAssertNotNil(down)
        XCTAssertEqual(session.phase, .dead)
    }

    func testEchoReplyCarriesMagic() throws {
        session.start()
        session.onFrame(Ppp.controlFrame(Ppp.protoLCP, Ppp.echoReq, 5, [9, 9, 9, 9]))
        let (_, rep) = try XCTUnwrap(control(try XCTUnwrap(sent.last)))
        XCTAssertEqual(rep.code, Ppp.echoRep)
        XCTAssertEqual(rep.data.count, 4)
        XCTAssertNotEqual(rep.data, [0, 0, 0, 0])
        session.close()
    }
}
#endif
