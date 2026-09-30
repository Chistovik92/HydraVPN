import XCTest
@testable import HydraKit

final class RouterLinkTests: XCTestCase {
    private let fp = String(repeating: "ab", count: 32)

    func testPairingLinkWithTlsAndFingerprint() throws {
        let l = try XCTUnwrap(RouterLink.parse("hydravpn-router://192.168.1.1:8088?fp=\(fp)&tls=1&token=abc123"))
        XCTAssertEqual(l.host, "192.168.1.1")
        XCTAssertEqual(l.port, 8088)
        XCTAssertEqual(l.token, "abc123")
        XCTAssertTrue(l.tls)
        XCTAssertEqual(l.fingerprint, fp)
        XCTAssertEqual(l.baseURL, "https://192.168.1.1:8088")
        XCTAssertFalse(l.insecure)
    }

    func testPlainHttpIsInsecureAndDropsFingerprint() throws {
        let l = try XCTUnwrap(RouterLink.parse("hydravpn-router://10.0.0.1:18088?tls=0&token=t0k3n&fp=\(fp)"))
        XCTAssertFalse(l.tls)
        XCTAssertNil(l.fingerprint)
        XCTAssertTrue(l.insecure)
        XCTAssertEqual(l.baseURL, "http://10.0.0.1:18088")
    }

    func testAddressWithSeparateTokenAndDefaultPort() throws {
        let l = try XCTUnwrap(RouterLink.parse("192.168.8.1", token: "secret"))
        XCTAssertEqual(l.port, RouterLink.defaultPort)
        XCTAssertEqual(l.token, "secret")
        XCTAssertEqual(l.name, "192.168.8.1")
    }

    func testRejectsGarbage() {
        XCTAssertNil(RouterLink.parse(""))
        XCTAssertNil(RouterLink.parse("hydravpn-router://192.168.1.1:8088"))                  // нет токена
        XCTAssertNil(RouterLink.parse("hydravpn-router://x:1?token=a b"))                      // пробел в токене
        XCTAssertNil(RouterLink.parse("hydravpn-router://r:8088?tls=1&token=t&fp=zz"))         // плохой отпечаток
        XCTAssertNil(RouterLink.parse("ftp://r:8088?token=t"))
    }

    func testUriRoundTrip() throws {
        let l = RouterLink(host: "router.lan", port: 8443, token: "tok&en%1", tls: true, fingerprint: fp, name: "Home")
        let back = try XCTUnwrap(RouterLink.parse(l.uri))
        XCTAssertEqual(back.host, l.host)
        XCTAssertEqual(back.port, l.port)
        XCTAssertEqual(back.token, l.token)
        XCTAssertEqual(back.fingerprint, fp)
    }
}
