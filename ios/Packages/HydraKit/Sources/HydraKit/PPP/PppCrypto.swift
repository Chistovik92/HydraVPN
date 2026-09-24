import Foundation
#if canImport(CommonCrypto)
    import CommonCrypto
#endif
#if canImport(CryptoKit)
    import CryptoKit
#endif

/// MD4 (RFC 1320) — нужен MS-CHAPv2 (NtPasswordHash), в CryptoKit его нет. Порт Md4.kt.
public enum Md4 {
    public static func digest(_ message: [UInt8]) -> [UInt8] {
        var a: UInt32 = 0x67452301, b: UInt32 = 0xEFCDAB89, c: UInt32 = 0x98BADCFE, d: UInt32 = 0x10325476
        let len = message.count
        let padLen = len % 64 < 56 ? 56 - len % 64 : 120 - len % 64
        var p = message + [0x80] + [UInt8](repeating: 0, count: padLen - 1)
        let bits = UInt64(len) * 8
        for i in 0..<8 { p.append(UInt8((bits >> (8 * UInt64(i))) & 0xFF)) }

        func rotl(_ v: UInt32, _ s: UInt32) -> UInt32 { (v << s) | (v >> (32 - s)) }
        let r1: [UInt32] = [3, 7, 11, 19], r2: [UInt32] = [3, 5, 9, 13], r3: [UInt32] = [3, 9, 11, 15]
        let o2 = [0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15]
        let o3 = [0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15]

        var i = 0
        while i < p.count {
            var x = [UInt32](repeating: 0, count: 16)
            for j in 0..<16 {
                let o = i + j * 4
                x[j] = UInt32(p[o]) | UInt32(p[o + 1]) << 8 | UInt32(p[o + 2]) << 16 | UInt32(p[o + 3]) << 24
            }
            let (aa, bb, cc, dd) = (a, b, c, d)
            for s in 0..<16 {
                let t = rotl(a &+ ((b & c) | (~b & d)) &+ x[s], r1[s % 4]); a = d; d = c; c = b; b = t
            }
            for s in 0..<16 {
                let t = rotl(a &+ ((b & c) | (b & d) | (c & d)) &+ x[o2[s]] &+ 0x5A827999, r2[s % 4]); a = d; d = c; c = b; b = t
            }
            for s in 0..<16 {
                let t = rotl(a &+ (b ^ c ^ d) &+ x[o3[s]] &+ 0x6ED9EBA1, r3[s % 4]); a = d; d = c; c = b; b = t
            }
            a = a &+ aa; b = b &+ bb; c = c &+ cc; d = d &+ dd
            i += 64
        }
        return [a, b, c, d].flatMap { v in (0..<4).map { UInt8((v >> (8 * UInt32($0))) & 0xFF) } }
    }
}

#if canImport(CommonCrypto) && canImport(CryptoKit)

/// MS-CHAPv2 (RFC 2759) + GetMasterKey (RFC 3079) для SSTP crypto-binding — порт MsChapV2.kt.
public enum MsChapV2 {
    public struct AuthResult: Sendable {
        public let ntResponse: [UInt8]           // 24 байта
        public let masterKey: [UInt8]            // 16 байт — CMK
        public let authenticatorResponse: String // "S=<40 hex>"
    }

    public static func authenticate(user: String, password: String, authenticatorChallenge: [UInt8], peerChallenge: [UInt8]) -> AuthResult {
        let ntHash = ntPasswordHash(password)
        let ntHashHash = Md4.digest(ntHash)
        let ch8 = challengeHash(peerChallenge, authenticatorChallenge, user)
        let resp = challengeResponse(ch8, ntHash)
        return AuthResult(ntResponse: resp, masterKey: masterKey(ntHashHash, resp),
                          authenticatorResponse: authenticatorResponse(password, user, authenticatorChallenge, peerChallenge, resp))
    }

    public static func ntPasswordHash(_ password: String) -> [UInt8] {
        Md4.digest(Array(password.data(using: .utf16LittleEndian) ?? Data()))
    }

    public static func challengeHash(_ peer: [UInt8], _ auth: [UInt8], _ user: String) -> [UInt8] {
        Array(Insecure.SHA1.hash(data: peer + auth + Array(user.utf8))).prefix(8).map { $0 }
    }

    public static func challengeResponse(_ ch8: [UInt8], _ ntHash: [UInt8]) -> [UInt8] {
        let z = ntHash + [UInt8](repeating: 0, count: 21 - ntHash.count)
        return des7(Array(z[0..<7]), ch8) + des7(Array(z[7..<14]), ch8) + des7(Array(z[14..<21]), ch8)
    }

    public static func authenticatorResponse(_ password: String, _ user: String, _ authCh: [UInt8], _ peerCh: [UInt8], _ ntResp: [UInt8]) -> String {
        let hh = Md4.digest(ntPasswordHash(password))
        let d1 = Array(Insecure.SHA1.hash(data: hh + ntResp + Array("Magic server to client signing constant".utf8)))
        let d2 = Array(Insecure.SHA1.hash(data: d1 + challengeHash(peerCh, authCh, user) + Array("Pad to make it do more than one iteration".utf8)))
        return "S=" + d2.map { String(format: "%02X", $0) }.joined()
    }

    public static func masterKey(_ ntHashHash: [UInt8], _ ntResponse: [UInt8]) -> [UInt8] {
        Array(Insecure.SHA1.hash(data: ntHashHash + ntResponse + Array("This is the MPPE Master Key".utf8))).prefix(16).map { $0 }
    }

    public static func hmacSHA1(key: [UInt8], data: [UInt8]) -> [UInt8] {
        Array(HMAC<Insecure.SHA1>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    public static func hmacSHA256(key: [UInt8], data: [UInt8]) -> [UInt8] {
        Array(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    /// DES-ECB с 56-битным ключом, развёрнутым в 8 байт с битами чётности (RFC 2759 §8.6).
    static func des7(_ key7: [UInt8], _ data8: [UInt8]) -> [UInt8] {
        let key8 = expandKey(key7)
        var out = [UInt8](repeating: 0, count: 8)
        var moved = 0
        _ = CCCrypt(CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmDES), CCOptions(kCCOptionECBMode),
                    key8, kCCKeySizeDES, nil, data8, 8, &out, 8, &moved)
        return out
    }

    static func expandKey(_ k: [UInt8]) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: 8)
        var bit = 0
        for i in 0..<8 {
            var b = 0
            for j in stride(from: 7, through: 1, by: -1) {
                b |= ((Int(k[bit / 8]) >> (7 - bit % 8)) & 1) << j
                bit += 1
            }
            let ones = (1...7).filter { (b >> $0) & 1 == 1 }.count
            out[i] = UInt8(ones % 2 == 0 ? b | 1 : b)
        }
        return out
    }
}

#endif
