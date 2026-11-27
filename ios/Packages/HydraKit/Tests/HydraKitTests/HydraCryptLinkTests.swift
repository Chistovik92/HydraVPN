import XCTest
@testable import HydraKit

/// hydra://crypt1/… (0.8.4-rc1): порт формата с Android/ПК. Закреплённые векторы обязаны совпадать
/// байт-в-байт с `HydraCryptLinkTest.kt` — ссылка, сделанная на Android/ПК, открывается на iOS и
/// наоборот. Векторы собраны с детерминированным IV 00..0B; продакшн-encode использует случайный IV.
/// AES-GCM — CryptoKit (Apple-платформы); на Linux кодек — заглушка, эти тесты там пропускаются.
final class HydraCryptLinkTests: XCTestCase {
    private let url = "https://sub.your-provider.example/abc123token"
    private var iv: Data {
        var d = Data()
        for i in 0..<12 { d.append(UInt8(i)) }
        return d
    }

    func testKeyFingerprint() {
        XCTAssertEqual(HydraCryptLink.fingerprintHex(), HydraCryptLink.keyFingerprint)
    }

    func testDeterministicVectors() throws {
        #if canImport(CryptoKit)
        XCTAssertEqual(
            "hydra://crypt1/AAECAwQFBgcICQoLXvSqhkQ4OMak3NAMxV8zhGccCiBpeMMej6bm734-2dL6t_puyw3yDDPthpSuGbapvQxloCKNuVVCRzsYqwQilUhjIaP5yna2IFTQ0HI",
            HydraCryptLink.encodeDeterministic(url, name: nil, iv: iv))
        XCTAssertEqual(
            "hydra://crypt1/AAECAwQFBgcICQoLXvSx1hI4T53s29EelEk-3mYFSjQyf8IY0qWuryckyNWm4PBj2E3yEjm0jpKoWqr_sQJjtSDKuhZWBjAb5Q6C_MJbhfHGrWCqnlBY_mBWzCCjWUgZu-LaDDPy",
            HydraCryptLink.encodeDeterministic(url, name: "My sub", iv: iv))
        #endif
    }

    func testRoundtripWithRandomIv() throws {
        #if canImport(CryptoKit)
        let link = try XCTUnwrap(HydraCryptLink.encode(url, name: "  Провайдер  "))
        let d = try XCTUnwrap(HydraCryptLink.decode(link))
        XCTAssertEqual(d.url, url)
        XCTAssertEqual(d.name, "Провайдер")
        // Случайный IV: две кодировки одной подписки различаются, обе расшифровываются.
        let link2 = try XCTUnwrap(HydraCryptLink.encode(url, name: "Провайдер"))
        XCTAssertNotEqual(link, link2)
        XCTAssertEqual(HydraCryptLink.decode(link2)?.url, url)
        #endif
    }

    func testRejects() throws {
        #if canImport(CryptoKit)
        XCTAssertNil(HydraCryptLink.encode("vless://not-a-subscription"))
        XCTAssertNil(HydraCryptLink.encode(""))
        XCTAssertNil(HydraCryptLink.decode("hydra://crypt1/"))
        XCTAssertNil(HydraCryptLink.decode("hydra://crypt1/AAAA"))
        XCTAssertNil(HydraCryptLink.decode("vless://example.com"))
        // GCM-тег испорчен: последний символ base64url меняет последний байт тега.
        let v1 = try XCTUnwrap(HydraCryptLink.encodeDeterministic(url, name: nil, iv: iv))
        XCTAssertNil(HydraCryptLink.decode(String(v1.dropLast()) + (v1.hasSuffix("A") ? "B" : "A")))
        #endif
    }
}