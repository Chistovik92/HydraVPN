import XCTest
@testable import HydraKit

/// Ссылки `openflux://v1/` (0.7.11): «замороженные» ссылки из share/compat_test.go ядра OpenFlux 0.4.2 обязаны читаться всегда.
final class OpenFluxShareTests: XCTestCase {
    static let frozen = [
        "openflux://v1/TMpBCsIwEAXQu_y1aTCJm4B4EHEhNehAnAmTaUVK7y6ii771W2B65d5ErSOfF9i7FWSMU-vClbhgh0krMh5mrWfvia3oTOU1fNPwW77SXNwoN-K7P6nI87j_cyGE4GKM0aWUkjtsYL2snwAAAP__",
        "openflux://v1/qlbKS8xNVbJS8k3MzNErKlVIK8pMzUtR0lEqKUrMKy7ILyopVrKKrlYqqSwAKctNzMwpKlXSUSotylGyUnJMck5xTXPPMNT3zPLO8c3zLzBSqo2tBQQAAP__",
        "openflux://v1/rI_BaiwhEEV_pSnonWPb7TweCCFkM-QfQha2FhlJj0pZTkaG_vdgAoHssyw4dc-9d4j2gmDgOV0QBER8SxwsIximigIKOkIGAy4RoePhnKjgsFpmpDYUtnnrjy5FxlsHz8y5mGnyyRXZbPR4k1S_zuka8OOx0vbQ7MGH8n7Idd2CG_XTuJzG5WRXBwKYbCw5ERcwL3fglnvD63cWCKi0_YknU0gUuIGZldrFj8mHvvQX8E8J8MF276K0VHKetfxvjketYX_dPwMAAP__",
        "openflux://v1/JMk7DsIwDAbgu_xz1PB--CqIIdgGhqqJbFdqVPXuDMzfCq6iDMKon8IdCa5sGiDs9ofj6Xy53u7lxaJvJISVyVu1cNBjRfSmIPQyiS5ImG0E4RvRnHKWyj78bbA5L9ie2y8AAP__",
        "openflux://v1/PY0xCwIxDEb_S2a94loQNydHN3Eod1EDvaSkaUWO---2HJox7328BTjMCB4exYoi7MA0cE6ilsHfFrBP6ngsKQtH4q4Uje31MkvZO0dsqJXwPXRp2CwXqeJ-lIn46U4qMh9DuzbeQtdf5UwYJ_CH9f5Hki5YsSUWCJ2sXw"
    ]

    func testFrozenLinksRead() throws {
        let cups = try OpenFluxShare.decode(Self.frozen[0])
        XCTAssertEqual(cups.transports.first?.type, "cupsonline")
        let mail = try OpenFluxShare.decode(Self.frozen[1])
        XCTAssertEqual(mail.name, "Mail.ru friend")
        XCTAssertEqual(mail.transports[0].url, "AbCdEfGh1/IjKlMnOp2")
        let home = try OpenFluxShare.decode(Self.frozen[2])
        XCTAssertTrue(home.negotiate)
        XCTAssertEqual(home.secret, "correct horse battery staple")
        XCTAssertEqual(home.transports.count, 2)
        XCTAssertEqual(home.transports[1].dial, "203.0.113.7:4433")
        XCTAssertEqual(try OpenFluxShare.decode(Self.frozen[3]).codec, "legacy")
        XCTAssertEqual(try OpenFluxShare.decode(Self.frozen[4]).name, "future")
    }

    func testErrorCodesAreTheCoresCodes() {
        func code(_ link: String) -> String? {
            do { _ = try OpenFluxShare.decode(link); return nil } catch let e as OpenFluxShare.ShareError { return e.code } catch { return "?" }
        }
        XCTAssertEqual(code("https://example.com"), "not_link")
        XCTAssertEqual(code("openflux://v2/abc"), "unsupported_version")
        XCTAssertEqual(code("OPENFLUX://V1/abc"), "case_changed")
        XCTAssertEqual(code(String(Self.frozen[0].dropLast(30))), "damaged")
    }

    func testRoundTripAndProfile() throws {
        let home = try OpenFluxShare.decode(Self.frozen[2])
        let again = try OpenFluxShare.decode(try OpenFluxShare.encode(home))
        XCTAssertEqual(home, again)
        let p = LinkParser.openFluxProfile(home)
        XCTAssertEqual(p.transport, "session")
        XCTAssertEqual(p.uuidOrPassword, home.secret)
        XCTAssertEqual(OpenFluxSession.count(p.extraObject), 2)
        XCTAssertNotNil(OpenFluxSession.specsJSON(p.extraObject))
        XCTAssertTrue(LinkBuilder.link(p)?.hasPrefix("openflux://v1/") ?? false)
    }

    func testMangledLinkStillReads() throws {
        let body = String(Self.frozen[2].dropFirst("openflux://v1/".count))
        let std = body.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let c = try OpenFluxShare.decode("  openflux://v1/" + std.prefix(40) + "\r\n " + std.dropFirst(40) + "==\n")
        XCTAssertEqual(c.transports.count, 2)
    }

    func testImportDetectorReportsBrokenLink() {
        XCTAssertEqual(ImportDetector.classify(String(Self.frozen[0].dropLast(30))), .openFluxError(code: "damaged"))
        if case .servers(let p) = ImportDetector.classify(Self.frozen[2]) { XCTAssertEqual(p.count, 1) } else { XCTFail() }
    }
}
