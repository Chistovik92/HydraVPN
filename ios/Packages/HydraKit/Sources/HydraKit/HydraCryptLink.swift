import Foundation
#if canImport(CryptoKit)
    import CryptoKit
#endif

/// Шифрованные ссылки подписки `hydra://crypt1/<base64url>` (0.8.4-rc1) — порт `HydraCryptLink.kt`
/// (аналог incy-link-encoder). Формат: base64url без паддинга от `IV(12) ‖ ciphertext ‖ GCM-тег(16)`,
/// AES-256-GCM; ключ K1 = SHA-256(соли + два 32-байтовых блоба); открытый текст — компактный JSON
/// `{"n":<имя?>,"url":<url>,"v":1}` с фиксированным порядком ключей: байты совпадают с Android/ПК,
/// поэтому закреплённые тест-векторы идентичны на всех платформах. KDF — чистый Swift (`Sha256`
/// из Geo.swift), AES-GCM — CryptoKit; на Linux (куда собирается HydraKit для тестов) кодек
/// возвращает nil и ссылки не шифрует/не читает.
///
/// Модель угроз — как у апстрима, честно: ключ зашит в клиенты, это защита от автоматических
/// сканеров и ботов модерации, а не от реверсера. При компрометации появится `crypt2/`.
public enum HydraCryptLink {
    public static let prefix = "hydra://crypt1/"
    /// SHA-256(K1) — сверяется тестом; меняется вместе с ключом при `crypt2/`.
    public static let keyFingerprint = "f02a091bc99fb112f9d3395dde9ab6dfbf549d557f7ce969be16d9ebdd6a7d13"

    static let ivLen = 12
    static let tagLen = 16
    static let maxUrl = 8192
    static let maxName = 128

    public struct Decrypted: Equatable, Sendable {
        public let url: String
        public let name: String?
    }

    /// K1 = SHA-256(seed): части соли и блобы — отдельные константы (как у апстрима и в клиентах),
    /// чтобы однострочный grep по бинарнику не выдавал весь seed. Это обфускация, не секретность.
    static let keyBytes: [UInt8] = {
        var seed: [UInt8] = Array(("hydra" + "deep" + "crypt1" + "v2026.11").utf8)
        seed += hexBytes("a29230c77fdd225c84a5cda43b2530501fecb242ae237c9528abac7b06cefd4b")
        seed += hexBytes("5da3a91e54d49b6869f43a1a65390f69eec75494a760b4afeee878a60e4eff48")
        return Sha256.hash(seed)
    }()

    /// Подписка -> `hydra://crypt1/…`. nil — URL не http(s) или длиннее лимита. IV случайный:
    /// две кодировки одной подписки различаются, но обе расшифровываются.
    public static func encode(_ url: String, name: String? = nil) -> String? {
        let u = url.lowercased()
        guard u.hasPrefix("http://") || u.hasPrefix("https://"), url.count <= maxUrl else { return nil }
        #if canImport(CryptoKit)
        let plaintext = Data(plaintextJson(url, trimmed(name)).utf8)
        var rng = SystemRandomNumberGenerator()
        var iv: [UInt8] = []
        iv.reserveCapacity(ivLen)
        for _ in 0..<ivLen { iv.append(UInt8(truncatingIfNeeded: rng.next())) }
        guard let sealed = try? AES.GCM.seal(plaintext, using: key, nonce: AES.GCM.Nonce(data: Data(iv))),
              let wire = sealed.combined else { return nil }
        return prefix + base64Url(wire)
        #else
        return nil
        #endif
    }

    /// `hydra://crypt1/…` -> подписка. nil — ссылка не наша, повреждена, обрезана (GCM-тег не
    /// сошёлся) или внутри нет корректного http(s)-URL. Префикс — без учёта регистра.
    public static func decode(_ link: String) -> Decrypted? {
        #if canImport(CryptoKit)
        let t = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.lowercased().hasPrefix(prefix) else { return nil }
        var payload = String(t.dropFirst(prefix.count))
        while payload.hasSuffix("/") { payload.removeLast() }
        guard !payload.isEmpty, payload.count <= 64 * 1024, let wire = base64UrlDecode(payload) else { return nil }
        guard wire.count >= ivLen + tagLen + 1 else { return nil }
        guard let nonce = try? AES.GCM.Nonce(data: wire.prefix(ivLen)),
              let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: wire.dropFirst(ivLen).dropLast(tagLen), tag: wire.suffix(tagLen)),
              let plain = try? AES.GCM.open(box, using: key) else { return nil }
        guard let obj = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let url = obj["url"] as? String,
              url.lowercased().hasPrefix("http://") || url.lowercased().hasPrefix("https://"), url.count <= maxUrl else { return nil }
        let name = (obj["n"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return Decrypted(url: url, name: name == nil || name!.isEmpty ? nil : String(name!.prefix(maxName)))
        #else
        return nil
        #endif
    }

    /// Детерминированная кодировка с заданным IV — только для закреплённых тест-векторов
    /// (public: тесты — отдельный модуль). В проде IV всегда случайный.
    public static func encodeDeterministic(_ url: String, name: String?, iv: Data) -> String? {
        #if canImport(CryptoKit)
        guard iv.count == ivLen else { return nil }
        guard let sealed = try? AES.GCM.seal(Data(plaintextJson(url, name).utf8), using: key, nonce: AES.GCM.Nonce(data: iv)),
              let wire = sealed.combined else { return nil }
        return prefix + base64Url(wire)
        #else
        return nil
        #endif
    }

    /// SHA-256(K1) в hex — сверяется с [keyFingerprint] тестом (как SHA-256(K1) в `HydraCryptLinkTest.kt`).
    static func fingerprintHex() -> String {
        Sha256.hex(Data(keyBytes))
    }

    #if canImport(CryptoKit)
    private static let key = SymmetricKey(data: Data(keyBytes))
    #endif

    /// Компактный JSON с фиксированным порядком ключей `n`, `url`, `v` — руками, не JSONEncoder:
    /// байты plaintext обязаны совпадать с Android/ПК (закреплённые векторы). Так же устроен апстрим.
    static func plaintextJson(_ url: String, _ name: String?) -> String {
        var sb = "{"
        if let n = name, !n.isEmpty { sb += "\"n\":\(jsonString(String(n.prefix(maxName))))," }
        sb += "\"url\":\(jsonString(url)),\"v\":1}"
        return sb
    }

    private static func trimmed(_ name: String?) -> String? {
        name?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Экранирование — как у Kotlin-версии: `"`, `\`, \n, \r, \t, контрольные символы как \u00xx;
    /// не-ASCII остаётся как есть (UTF-8-байты совпадают с Kotlin на валидных строках).
    private static func jsonString(_ s: String) -> String {
        var sb = "\""
        for ch in s.unicodeScalars {
            if ch == "\"" { sb += "\\\"" }
            else if ch == "\\" { sb += "\\\\" }
            else if ch == "\n" { sb += "\\n" }
            else if ch == "\r" { sb += "\\r" }
            else if ch == "\t" { sb += "\\t" }
            else if ch.value < 0x20 { sb += String(format: "\\u%04x", ch.value) }
            else { sb.unicodeScalars.append(ch) }
        }
        return sb + "\""
    }

    private static func base64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func base64UrlDecode(_ s: String) -> Data? {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        return Data(base64Encoded: b)
    }

    private static func hexBytes(_ s: String) -> [UInt8] {
        let chars = Array(s)
        var r: [UInt8] = []
        r.reserveCapacity(chars.count / 2)
        var i = 0
        while i + 1 < chars.count {
            if let h = chars[i].hexDigitValue, let l = chars[i + 1].hexDigitValue {
                r.append(UInt8(h * 16 + l))
            }
            i += 2
        }
        return r
    }
}