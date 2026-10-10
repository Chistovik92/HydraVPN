import Foundation
import XCTest
@testable import HydraKit

/// 0.7.20: TG WS (кодеки), настройки подбора DPI, выход `tgws` в плане - порт тестов Android/ПК.
final class TgWsTests: XCTestCase {
    private func hex(_ s: String) -> [UInt8] {
        stride(from: 0, to: s.count, by: 2).map { UInt8(s[s.index(s.startIndex, offsetBy: $0)...].prefix(2), radix: 16)! }
    }

    func testAes256MatchesFips197Vector() {
        // FIPS-197, приложение C.3.
        let key = (0..<32).map { UInt8($0) }
        let block = hex("00112233445566778899aabbccddeeff")
        let out = Aes256.encrypt(block: block, roundKeys: Aes256.expandKey(key))
        XCTAssertEqual(out, hex("8ea2b7ca516745bfeafc49904b496089"))
    }

    func testCtrIsSymmetricAndIncrementsCounter() {
        let key = (0..<32).map { UInt8($0 &* 7) }, iv = (0..<16).map { UInt8($0 &+ 1) }
        let plain = (0..<100).map { UInt8($0) }
        let enc = AesCtr(key: key, iv: iv).process(plain)
        XCTAssertNotEqual(enc, plain)
        XCTAssertEqual(AesCtr(key: key, iv: iv).process(enc), plain)
        // Перенос счётчика через старшие байты.
        let ff = [UInt8](repeating: 0xFF, count: 16)
        let ks = AesCtr(key: key, iv: ff).keystream(32)
        XCTAssertNotEqual(Array(ks[0..<16]), Array(ks[16..<32]))
    }

    /// Собирает init так, как это делает клиент Telegram: открытые байты 56..<64 зашифрованы потоком, ключ и IV - в самом init.
    private func makeInit(proto: [UInt8] = [0xEF, 0xEF, 0xEF, 0xEF], dc: Int16) -> [UInt8] {
        var b = (0..<64).map { UInt8(($0 &* 37 &+ 11) & 0xFF) }
        let ks = AesCtr(key: Array(b[8..<40]), iv: Array(b[40..<56])).keystream(64)
        let d = UInt16(bitPattern: dc)
        let plain = proto + [UInt8(d & 0xFF), UInt8(d >> 8), 0, 0]
        for i in 0..<8 { b[56 + i] = plain[i] ^ ks[56 + i] }
        return b
    }

    func testDcFromInit() {
        XCTAssertEqual(TgWs.dcFromInit(makeInit(dc: 2))?.dc, 2)
        XCTAssertEqual(TgWs.dcFromInit(makeInit(dc: 2))?.media, false)
        let media = TgWs.dcFromInit(makeInit(dc: -4))
        XCTAssertEqual(media?.dc, 4); XCTAssertEqual(media?.media, true)
        XCTAssertEqual(TgWs.dcFromInit(makeInit(dc: 203))?.dc, 203)
        XCTAssertNil(TgWs.dcFromInit(makeInit(dc: 9)))                                   // нет такого ЦОД
        XCTAssertNil(TgWs.dcFromInit(makeInit(proto: [1, 2, 3, 4], dc: 2)))              // не тег MTProto
        XCTAssertNil(TgWs.dcFromInit([1, 2, 3]))
    }

    func testPatchInitDcRewritesTheDc() {
        let original = makeInit(dc: 1)
        let patched = TgWs.patchInitDc(original, dc: 5)
        XCTAssertEqual(TgWs.dcFromInit(patched)?.dc, 5)
        let media = TgWs.patchInitDc(original, dc: -3)
        XCTAssertEqual(TgWs.dcFromInit(media)?.dc, 3); XCTAssertEqual(TgWs.dcFromInit(media)?.media, true)
        XCTAssertEqual(Array(patched[0..<60]), Array(original[0..<60]))                  // остальное не тронуто
    }

    func testSplitterCutsGluedMessages() {
        let initBytes = makeInit(dc: 2)
        func msg(_ words: Int) -> [UInt8] { [UInt8(words)] + (0..<(words * 4)).map { UInt8($0 & 0xFF) } }
        let plain = msg(2) + msg(3)                                    // два сообщения в одной записи
        let cipher = AesCtr(key: Array(initBytes[8..<40]), iv: Array(initBytes[40..<56]))
        _ = cipher.keystream(64)
        let chunk = cipher.process(plain)
        let parts = TgWs.MsgSplitter(initBytes: initBytes).split(chunk)
        XCTAssertEqual(parts.map(\.count), [9, 13])
        XCTAssertEqual(parts.flatMap { $0 }, chunk)
        // Одно сообщение - без разрезания.
        let one = TgWs.MsgSplitter(initBytes: initBytes)
        let c2 = AesCtr(key: Array(initBytes[8..<40]), iv: Array(initBytes[40..<56]))
        _ = c2.keystream(64)
        XCTAssertEqual(one.split(c2.process(msg(2))).count, 1)
    }


    func testOwnInitIsReadableAndStreamsInterop() {
        for dc in [2, -4, 203] {
            let obf = TgWs.ObfClient(tag: TgWs.tagAbridged, dc: dc)
            let parsed = TgWs.dcFromInit(obf.initBytes)
            XCTAssertEqual(parsed?.dc, abs(dc)); XCTAssertEqual(parsed?.media, dc < 0)
            // Ретранслятор: ключ и IV из init, поток после 64 байт init.
            let rx = AesCtr(key: Array(obf.initBytes[8..<40]), iv: Array(obf.initBytes[40..<56]))
            _ = rx.keystream(64)
            let msg = (0..<40).map { UInt8($0) }
            XCTAssertEqual(rx.process(obf.encrypt(msg)), msg)
            // Обратный поток: ключ - развёрнутые байты 8..56 исходного (расшифрованного) init.
            let ks = AesCtr(key: Array(obf.initBytes[8..<40]), iv: Array(obf.initBytes[40..<56])).keystream(64)
            var plain = obf.initBytes
            for i in 56..<64 { plain[i] ^= ks[i] }
            let rev = Array(plain[8..<56].reversed())
            let tx = AesCtr(key: Array(rev[0..<32]), iv: Array(rev[32..<48]))
            let reply = (0..<24).map { UInt8($0 &* 3) }
            XCTAssertEqual(obf.decrypt(tx.process(reply)), reply)
        }
    }

    func testFramerCutsAbridgedAndIntermediate() throws {
        func m(_ words: Int) -> [UInt8] { [UInt8(words)] + [UInt8](repeating: 7, count: words * 4) }
        var fr = TgWs.PlainFramer(tag: TgWs.tagAbridged)
        let all = m(2) + m(3)
        XCTAssertEqual(try fr.feed(Array(all[0..<11])).map(\.count), [9])        // вторая - ещё неполная
        XCTAssertEqual(try fr.feed(Array(all[11...])).map(\.count), [13])        // хвост достроен
        var big = TgWs.PlainFramer(tag: TgWs.tagAbridged)
        XCTAssertEqual(try big.feed([0x7F, 0x40, 0, 0] + [UInt8](repeating: 0, count: 256)).map(\.count), [260])
        func im(_ n: Int) -> [UInt8] { [UInt8(n), 0, 0, 0] + [UInt8](repeating: 1, count: n) }
        var fi = TgWs.PlainFramer(tag: TgWs.tagIntermediate)
        XCTAssertEqual(try fi.feed(im(8) + im(20)).map(\.count), [12, 24])
    }

    func testIpv6DatacenterAddresses() {
        func v6(_ groups: [UInt16]) -> [UInt8] { groups.flatMap { [UInt8($0 >> 8), UInt8($0 & 0xFF)] } }
        XCTAssertEqual(TgWs.v6Dc(v6([0x2001, 0x067C, 0x04E8, 0xF002, 0, 0, 0, 0x0A]))?.dc, 2)
        XCTAssertEqual(TgWs.v6Dc(v6([0x2001, 0x067C, 0x04E8, 0xF002, 0, 0, 0, 0x0B]))?.media, true)
        XCTAssertEqual(TgWs.v6Dc(v6([0x2001, 0x0B28, 0xF23F, 0xF005, 0, 0, 0, 0x0A]))?.dc, 5)
        XCTAssertNil(TgWs.v6Dc(v6([0x2001, 0x0DB8, 0, 0, 0, 0, 0, 1])))
        XCTAssertNil(TgWs.v6Dc(v6([0x2001, 0x067C, 0x04E8, 0xF009, 0, 0, 0, 0x0A])))
    }


    func testSha256KnownVectors() {
        func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }
        XCTAssertEqual(hex(Sha256.hash(Array("abc".utf8))), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(hex(Sha256.hash([])), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(hex(Sha256.hash(Array("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".utf8))),
                       "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
    }

    func testMtProxyHandshakeAndStreams() {
        // Клиент Telegram: init с ключом SHA-256(init[8..<40] + секрет), тег dd (padded intermediate), ЦОД -4 (media).
        var r = (0..<64).map { UInt8(($0 &* 29 &+ 5) & 0xFF) }
        r[0] = 0x12; r[4] = 1
        let enc = AesCtr(key: Sha256.hash(Array(r[8..<40]) + TgWs.mtSecret), iv: Array(r[40..<56]))
        let ks = enc.keystream(64)
        let tail: [UInt8] = [0xDD, 0xDD, 0xDD, 0xDD, 0xFC, 0xFF, 1, 2]       // ЦОД -4 как int16 LE
        var hs = r
        for i in 0..<8 { hs[56 + i] = tail[i] ^ ks[56 + i] }
        guard let s = TgWs.MtSession(handshake: hs) else { return XCTFail("рукопожатие не принято") }
        XCTAssertEqual(s.tag, TgWs.tagPadded)
        XCTAssertEqual(s.dcRaw, -4)
        // Поток клиента → прокси.
        let msg = (0..<32).map { UInt8($0 &+ 1) }
        XCTAssertEqual(s.clientDec.process(enc.process(msg)), msg)
        // Поток прокси → клиент: клиент расшифровывает ключом из развёрнутых байт init.
        let rev = Array(r[8..<56].reversed())
        let clientRx = AesCtr(key: Sha256.hash(Array(rev[0..<32]) + TgWs.mtSecret), iv: Array(rev[32..<48]))
        let reply = (0..<20).map { UInt8($0 &* 5) }
        XCTAssertEqual(clientRx.process(s.clientEnc.process(reply)), reply)
        // Чужой секрет или тег - отказ.
        var bad = hs; bad[60] ^= 0xFF; bad[56] ^= 0xFF
        XCTAssertNil(TgWs.MtSession(handshake: bad))
        XCTAssertNil(TgWs.MtSession(handshake: [1, 2, 3]))
        XCTAssertTrue(TgWs.mtProxyLink().hasPrefix("tg://proxy?server=127.0.0.1&port=10870&secret=dd"))
        XCTAssertEqual(TgWs.mtSecretHex.count, 32)
    }

    func testTelegramAddresses() {
        XCTAssertTrue(TgWs.isTelegramIp("149.154.167.51"))
        XCTAssertTrue(TgWs.isTelegramIp("91.108.4.1"))
        XCTAssertFalse(TgWs.isTelegramIp("8.8.8.8"))
        XCTAssertFalse(TgWs.isTelegramIp("example.org"))
        XCTAssertEqual(TgWs.ipToDc["149.154.167.51"]?.dc, 2)
        XCTAssertEqual(TgWs.wsDomains(dc: 203, media: false).first, "kws2.web.telegram.org")
        XCTAssertEqual(TgWs.wsDomains(dc: 4, media: true).first, "kws4-1.web.telegram.org")
    }

    func testPresetAppliedAndRemoved() {
        var rules: [RouteRule] = []
        XCTAssertFalse(TgWsPreset.isApplied(rules))
        rules += TgWsPreset.rules()
        XCTAssertTrue(TgWsPreset.isApplied(rules))
        XCTAssertEqual(TgWsPreset.rules().count, TgWs.telegramCidrs.count)
    }

    func testPlanAddsTgWsOutboundAndCanDropIt() throws {
        let vless = ServerProfile(id: 1, name: "main", protocolId: "vless", address: "203.0.113.10", port: 443,
                                  uuidOrPassword: "b831381d-6324-4d53-ad4f-8cda48b30811", security: "tls")
        var cfg = try SingBoxConfigBuilder.build(vless, SingBoxConfigBuilder.Options())
        var plan = RoutePlan()
        plan.rules = TgWsPreset.rules()
        XCTAssertTrue(plan.needsTgWs)
        XCTAssertTrue(RoutePlanApplier.apply(&cfg, plan).isEmpty)
        let outs = cfg["outbounds"] as? [[String: Any]] ?? []
        let tg = outs.first { ($0["tag"] as? String) == "tgws" }
        XCTAssertEqual(tg?["server_port"] as? Int, RouteTarget.tgwsPort)
        XCTAssertEqual(tg?["type"] as? String, "socks")
        let rules = (cfg["route"] as? [String: Any])?["rules"] as? [[String: Any]] ?? []
        XCTAssertTrue(rules.contains { ($0["outbound"] as? String) == "tgws" && ($0["ip_cidr"] as? [String])?.contains("91.108.0.0/16") == true })
        XCTAssertFalse(plan.withoutTgWs().needsTgWs)
    }

    func testProbeSettingsJsonRoundTripAndLimits() {
        var s = DpiProbeSettings(delaySec: 3, requests: 2, parallel: 8, timeoutSec: 6, groups: ["youtube", "mine"],
                                 custom: [CustomSiteList(name: "mine", domains: ["a.example", "b.example"])],
                                 customStrategiesOn: true, customStrategies: "-o 1\n# note\n-o 1\n-d 2")
        let back = DpiProbeSettings(androidObject: s.androidObject())
        XCTAssertEqual(back, s)
        XCTAssertEqual(s.strategyLines(), ["-o 1", "-d 2"])
        XCTAssertEqual(s.selectedSites().map(\.name), ["youtube", "mine"])
        s = DpiProbeSettings(androidObject: ["delay": 99, "requests": 0, "parallel": 500, "timeout": -1])
        XCTAssertEqual([s.delaySec, s.requests, s.parallel, s.timeoutSec], [30, 1, 50, 1])
    }

    func testDpiSettingsKeepsOldStateAndNewFields() throws {
        let old = #"{"enabled":true,"strategy":"-o 2","port":10999,"directViaDpi":false}"#
        let d = try JSONDecoder().decode(DpiSettings.self, from: Data(old.utf8))
        XCTAssertEqual(d.fakeSni, DpiStrategies.fakeSni)
        XCTAssertEqual(d.probe, DpiProbeSettings())
        var cfg = RouteConfig()
        cfg.dpi = DpiSettings(enabled: true, fakeSni: "example.org", probe: DpiProbeSettings(groups: ["social"]))
        let again = RouteConfig(androidJSON: cfg.androidJSON())
        XCTAssertEqual(again.dpi.fakeSni, "example.org")
        XCTAssertEqual(again.dpi.probe.groups, ["social"])
        XCTAssertTrue(DpiArgs.build("-f {sni}", sni: "x.example").contains("x.example"))
        XCTAssertEqual(DpiStrategies.groupOrder.count, 8)
        XCTAssertTrue(DpiStrategies.groupOrder.allSatisfy { DpiStrategies.sites[$0] != nil })
    }
}
