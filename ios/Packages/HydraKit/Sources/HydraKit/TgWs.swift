import Foundation

/// «Telegram через WebSocket» - чистая часть (кодеки, таблицы адресов), порт `TgWsProxy` с Android/ПК (0.7.14), на iOS с 0.7.20.
/// Сам SOCKS5-сервер на Network.framework живёт в расширении VPN (`ios/HydraTunnel/TgWsServer.swift`): здесь только то, что
/// проверяется тестами на любой ОС. Идея и протокол - Flowseal/tg-ws-proxy и DmitryKafturov/tg-ws-proxy (MIT).
public enum TgWs {
    /// Запасные адреса ЦОД для WebSocket; `203` - служебный, ходит как ЦОД 2.
    public static let defaultDcIps: [Int: String] = [
        1: "149.154.175.205", 2: "149.154.167.220", 3: "149.154.175.100",
        4: "149.154.167.220", 5: "149.154.171.5", 203: "149.154.167.220",
    ]

    /// Подсети Telegram - их же использует готовое правило «Telegram → TG WS».
    public static let telegramCidrs = ["185.76.151.0/24", "149.154.160.0/20", "91.105.192.0/23", "91.108.0.0/16", "95.161.64.0/20"]

    private static let ranges: [(UInt32, UInt32)] = telegramCidrs.compactMap { c in
        let parts = c.split(separator: "/")
        guard parts.count == 2, let base = ipToInt(String(parts[0])), let bits = Int(parts[1]) else { return nil }
        let size = UInt64(1) << UInt64(32 - bits)
        return (base, UInt32(truncatingIfNeeded: UInt64(base) + size - 1))
    }

    private static let validProtos: Set<UInt32> = [0xEFEF_EFEF, 0xEEEE_EEEE, 0xDDDD_DDDD]

    /// IP → (ЦОД, media). Таблица из tg-ws-proxy; для клиентов без секрета, у которых байты ЦОД в init случайные.
    public static let ipToDc: [String: (dc: Int, media: Bool)] = {
        var m: [String: (Int, Bool)] = [:]
        func add(_ dc: Int, _ media: Bool, _ ips: [String]) { ips.forEach { m[$0] = (dc, media) } }
        add(1, false, ["149.154.175.50", "149.154.175.51", "149.154.175.53", "149.154.175.54"]); add(1, true, ["149.154.175.52"])
        add(2, false, ["149.154.167.41", "149.154.167.50", "149.154.167.51", "149.154.167.220", "95.161.76.100"])
        add(2, true, ["149.154.167.151", "149.154.167.222", "149.154.167.223", "149.154.162.123"])
        add(3, false, ["149.154.175.100", "149.154.175.101"]); add(3, true, ["149.154.175.102"])
        add(4, false, ["149.154.167.91", "149.154.167.92"])
        add(4, true, ["149.154.164.250", "149.154.166.120", "149.154.166.121", "149.154.167.118", "149.154.165.111"])
        add(5, false, ["91.108.56.100", "91.108.56.101", "91.108.56.116", "91.108.56.126", "149.154.171.5"])
        add(5, true, ["91.108.56.102", "91.108.56.128", "91.108.56.151"])
        add(203, false, ["91.105.192.100"])
        return m.mapValues { (dc: $0.0, media: $0.1) }
    }()

    public static func wsDomains(dc: Int, media: Bool) -> [String] {
        let d = dc == 203 ? 2 : dc
        return media ? ["kws\(d)-1.web.telegram.org", "kws\(d).web.telegram.org"]
                     : ["kws\(d).web.telegram.org", "kws\(d)-1.web.telegram.org"]
    }

    public static func ipToInt(_ ip: String) -> UInt32? {
        let p = ip.split(separator: ".", omittingEmptySubsequences: false)
        guard p.count == 4 else { return nil }
        var v: UInt32 = 0
        for x in p { guard let b = UInt8(x) else { return nil }; v = (v << 8) | UInt32(b) }
        return v
    }

    public static func isTelegramIp(_ ip: String) -> Bool {
        guard let n = ipToInt(ip) else { return false }
        return ranges.contains { n >= $0.0 && n <= $0.1 }
    }

    // MARK: MTProto: ЦОД из init

    /// Клиентский init (64 байта): AES-256-CTR, ключ `init[8..<40]`, IV `init[40..<56]`; открытые байты 56..<64 - тег протокола и номер ЦОД.
    public static func dcFromInit(_ initBytes: [UInt8]) -> (dc: Int, media: Bool)? {
        guard initBytes.count == 64 else { return nil }
        let ks = AesCtr(key: Array(initBytes[8..<40]), iv: Array(initBytes[40..<56])).keystream(64)
        let plain = (56..<64).map { initBytes[$0] ^ ks[$0] }
        let proto = (UInt32(plain[0]) << 24) | (UInt32(plain[1]) << 16) | (UInt32(plain[2]) << 8) | UInt32(plain[3])
        let raw = Int16(bitPattern: UInt16(plain[4]) | (UInt16(plain[5]) << 8))
        let dc = abs(Int(raw))
        guard validProtos.contains(proto), (1...5).contains(dc) || dc == 203 else { return nil }
        return (dc, raw < 0)
    }

    /// Переписывает номер ЦОД в init (клиенты без секрета оставляют там случайные байты).
    public static func patchInitDc(_ initBytes: [UInt8], dc: Int) -> [UInt8] {
        let ks = AesCtr(key: Array(initBytes[8..<40]), iv: Array(initBytes[40..<56])).keystream(64)
        var p = initBytes
        p[60] = ks[60] ^ UInt8(truncatingIfNeeded: dc)
        p[61] = ks[61] ^ UInt8(truncatingIfNeeded: dc >> 8)
        return p
    }

    /// Реле Telegram разбирает по одному MTProto-сообщению на кадр WebSocket, а клиент может склеить несколько в одну запись:
    /// режем поток по границам сообщений (abridged), расшифровав его тем же потоком AES-CTR.
    public final class MsgSplitter {
        private let dec: AesCtr
        public init(initBytes: [UInt8]) {
            dec = AesCtr(key: Array(initBytes[8..<40]), iv: Array(initBytes[40..<56]))
            _ = dec.keystream(64)      // первые 64 байта потока ушли на сам init
        }

        public func split(_ chunk: [UInt8]) -> [[UInt8]] {
            let plain = dec.process(chunk)
            var ends: [Int] = []
            var pos = 0
            while pos < plain.count {
                let first = Int(plain[pos])
                var len: Int
                if first == 0x7F {
                    if pos + 4 > plain.count { break }
                    len = (Int(plain[pos + 1]) | (Int(plain[pos + 2]) << 8) | (Int(plain[pos + 3]) << 16)) * 4
                    pos += 4
                } else { len = first * 4; pos += 1 }
                if len == 0 || pos + len > plain.count { break }
                pos += len
                ends.append(pos)
            }
            if ends.count <= 1 { return [chunk] }
            var parts: [[UInt8]] = []
            var prev = 0
            for e in ends { parts.append(Array(chunk[prev..<e])); prev = e }
            if prev < chunk.count { parts.append(Array(chunk[prev...])) }
            return parts
        }
    }
}

/// Готовое правило «Telegram → TG WS»: подсети серверов Telegram на выход `tgws`.
public enum TgWsPreset {
    public static func rules() -> [RouteRule] {
        TgWs.telegramCidrs.map { RouteRule(kind: .cidr, value: $0, target: RouteTarget.tgws) }
    }
    public static func isApplied(_ rules: [RouteRule]) -> Bool {
        let have = Set(rules.map { "\($0.kind.rawValue)|\($0.value)|\($0.target)" })
        return self.rules().allSatisfy { have.contains("\($0.kind.rawValue)|\($0.value)|\($0.target)") }
    }
}

// MARK: AES-256 и CTR (чистый Swift: CommonCrypto/CryptoKit есть не на всех платформах, а AES-CTR нет в CryptoKit)

/// AES-256, только шифрование блока. Таблица подстановок вычисляется (алгоритм из описания Rijndael), а не набирается руками.
enum Aes256 {
    private static let sbox: [UInt8] = {
        var s = [UInt8](repeating: 0, count: 256)
        var p: UInt8 = 1, q: UInt8 = 1
        func rotl(_ x: UInt8, _ n: UInt8) -> UInt8 { (x << n) | (x >> (8 - n)) }
        repeat {
            let hi: UInt8 = (p & 0x80) != 0 ? 0x1B : 0
            p = p ^ (p << 1) ^ hi                                    // p *= 3
            q ^= q << 1; q ^= q << 2; q ^= q << 4                    // q /= 3
            if q & 0x80 != 0 { q ^= 0x09 }
            var x: UInt8 = q
            x ^= rotl(q, 1)
            x ^= rotl(q, 2)
            x ^= rotl(q, 3)
            x ^= rotl(q, 4)
            s[Int(p)] = x ^ 0x63
        } while p != 1
        s[0] = 0x63
        return s
    }()

    /// 15 раундовых ключей по 16 байт.
    static func expandKey(_ key: [UInt8]) -> [[UInt8]] {
        precondition(key.count == 32)
        var w = [[UInt8]](repeating: [0, 0, 0, 0], count: 60)
        for i in 0..<8 { w[i] = Array(key[4 * i..<4 * i + 4]) }
        var rcon: UInt8 = 1
        for i in 8..<60 {
            var t = w[i - 1]
            if i % 8 == 0 {
                t = [sbox[Int(t[1])] ^ rcon, sbox[Int(t[2])], sbox[Int(t[3])], sbox[Int(t[0])]]
                rcon = xtime(rcon)
            } else if i % 8 == 4 {
                t = t.map { sbox[Int($0)] }
            }
            var word = [UInt8](repeating: 0, count: 4)
            for j in 0..<4 { word[j] = w[i - 8][j] ^ t[j] }
            w[i] = word
        }
        var keys: [[UInt8]] = []
        for r in 0..<15 {
            var k: [UInt8] = []
            for j in 0..<4 { k.append(contentsOf: w[4 * r + j]) }
            keys.append(k)
        }
        return keys
    }

    private static func xtime(_ x: UInt8) -> UInt8 {
        let hi: UInt8 = (x & 0x80) != 0 ? 0x1B : 0
        return (x << 1) ^ hi
    }

    static func encrypt(block: [UInt8], roundKeys rk: [[UInt8]]) -> [UInt8] {
        var s = [UInt8](repeating: 0, count: 16)
        for i in 0..<16 { s[i] = block[i] ^ rk[0][i] }
        for round in 1...14 {
            s = s.map { sbox[Int($0)] }
            // ShiftRows (состояние - по столбцам: s[4*c + r]).
            var t = s
            for c in 0..<4 { for r in 0..<4 { t[4 * c + r] = s[4 * ((c + r) % 4) + r] } }
            s = t
            if round != 14 {   // MixColumns
                for c in 0..<4 {
                    let a = Array(s[4 * c..<4 * c + 4])
                    let all = a[0] ^ a[1] ^ a[2] ^ a[3]
                    for r in 0..<4 { s[4 * c + r] = a[r] ^ all ^ xtime(a[r] ^ a[(r + 1) % 4]) }
                }
            }
            for i in 0..<16 { s[i] ^= rk[round][i] }
        }
        return s
    }
}

/// AES-256-CTR с 128-битным счётчиком (big-endian), как в обфускации MTProto.
public final class AesCtr {
    private let roundKeys: [[UInt8]]
    private var counter: [UInt8]
    private var pad: [UInt8] = []
    private var padPos = 16

    public init(key: [UInt8], iv: [UInt8]) {
        precondition(key.count == 32 && iv.count == 16)
        roundKeys = Aes256.expandKey(key)
        counter = iv
    }

    private func nextByte() -> UInt8 {
        if padPos == 16 {
            pad = Aes256.encrypt(block: counter, roundKeys: roundKeys)
            padPos = 0
            var i = 15
            while i >= 0 { counter[i] = counter[i] &+ 1; if counter[i] != 0 { break }; i -= 1 }
        }
        defer { padPos += 1 }
        return pad[padPos]
    }

    public func keystream(_ n: Int) -> [UInt8] { (0..<n).map { _ in nextByte() } }

    public func process(_ data: [UInt8]) -> [UInt8] { data.map { $0 ^ nextByte() } }
}
