import Foundation

/// Официальная ссылка OpenFlux `openflux://v1/<base64url(DEFLATE(JSON))>` — порт `OpenFluxShare` (Android/ПК) и
/// пакета `share` ядра (docs/links.md апстрима). Только Foundation: тесты идут и на Linux.
/// Коды ошибок — те же, что у ядра; тексты — свои, в Localizable.strings.
public enum OpenFluxShare {
    public static let prefix = "openflux://v1/"
    static let minSecretChars = 16
    static let maxPayload = 16 << 10
    static let knownTypes: Set<String> = ["yandex", "vyandex", "boards", "mailru", "cupsonline", "direct"]
    static let streamTypes: Set<String> = ["cupsonline", "mailru"]

    public struct Transport: Equatable {
        public var type: String
        public var url = ""
        public var priority = 0
        public var dial = ""
        public var name = ""
        public init(type: String, url: String = "", priority: Int = 0, dial: String = "", name: String = "") {
            self.type = type; self.url = url; self.priority = priority; self.dial = dial; self.name = name
        }
    }

    public struct Config: Equatable {
        public var name = ""
        public var negotiate = false
        public var codec = ""
        public var secret = ""
        public var context = ""
        public var mode = ""
        public var transports: [Transport]
        public init(name: String = "", negotiate: Bool = false, codec: String = "", secret: String = "",
                    context: String = "", mode: String = "", transports: [Transport]) {
            self.name = name; self.negotiate = negotiate; self.codec = codec; self.secret = secret
            self.context = context; self.mode = mode; self.transports = transports
        }
    }

    /// `code` — код ядра (`damaged`, `short_secret`, …), `param` — значение, о котором речь.
    public struct ShareError: Error, Equatable {
        public let code: String
        public let param: String
        init(_ code: String, _ param: String = "") { self.code = code; self.param = param }
    }

    public static func isV1(_ link: String) -> Bool {
        link.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix("openflux://v1/")
    }

    public static func validate(_ c: Config) throws {
        if c.transports.isEmpty { throw ShareError("no_transports") }
        if !c.mode.isEmpty {
            if c.mode != "stream" { throw ShareError("unknown_mode", c.mode) }
            if c.transports.count != 1 { throw ShareError("stream_one_transport") }
            if !streamTypes.contains(c.transports[0].type) { throw ShareError("stream_transport", c.transports[0].type) }
            if c.negotiate || !c.secret.isEmpty { throw ShareError("stream_plain_only") }
        }
        if c.transports.count > 1 && !c.negotiate { throw ShareError("several_need_session") }
        let chars = c.secret.utf16.count // UTF-16, как считает ядро
        if c.negotiate && chars < minSecretChars { throw ShareError("session_secret", String(minSecretChars)) }
        if !c.secret.isEmpty && chars < minSecretChars { throw ShareError("short_secret", String(minSecretChars)) }
        if !c.codec.isEmpty && c.codec != "batched" && c.codec != "legacy" { throw ShareError("unknown_codec", c.codec) }
        for t in c.transports {
            if !knownTypes.contains(t.type) {
                throw ShareError(t.type == "oneme" ? "not_shareable" : "unknown_transport", t.type)
            }
            if t.type == "direct" {
                if t.dial.isEmpty { throw ShareError("direct_no_dial") }
                if !c.negotiate { throw ShareError("direct_needs_session") }
            }
        }
    }

    public static func decode(_ raw: String) throws -> Config {
        let link = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard link.hasPrefix(prefix) else {
            if link.lowercased().hasPrefix("openflux://") && !link.hasPrefix("openflux://") { throw ShareError("case_changed") }
            if link.hasPrefix("openflux://") { throw ShareError("unsupported_version") }
            throw ShareError("not_link")
        }
        let skip: Set<Unicode.Scalar> = [" ", "\t", "\r", "\n", "\u{00A0}", "\u{200B}"]
        var body = String(String.UnicodeScalarView(link.dropFirst(prefix.count).unicodeScalars.filter { !skip.contains($0) }))
        body = body.replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
        while body.hasSuffix("=") { body.removeLast() }
        var std = body.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while std.count % 4 != 0 { std += "=" }
        guard let packed = Data(base64Encoded: std) else { throw ShareError("damaged") }
        let json: Data
        do {
            json = try Inflate.raw([UInt8](packed), limit: maxPayload)
        } catch let e as Inflate.Failure {
            throw ShareError(e == .tooLarge ? "too_large" : "damaged")
        }
        guard let obj = (try? JSONSerialization.jsonObject(with: json)) as? [String: Any] else { throw ShareError("bad_payload") }
        var ts: [Transport] = []
        if let arr = obj["transports"] as? [[String: Any]] {
            for t in arr {
                ts.append(Transport(type: t["type"] as? String ?? "", url: t["url"] as? String ?? "",
                                    priority: (t["priority"] as? NSNumber)?.intValue ?? 0,
                                    dial: t["dial"] as? String ?? "", name: t["name"] as? String ?? ""))
            }
        } else if obj["transports"] != nil {
            throw ShareError("bad_payload")
        }
        let c = Config(name: obj["name"] as? String ?? "", negotiate: (obj["negotiate"] as? Bool) ?? false,
                       codec: obj["codec"] as? String ?? "", secret: obj["secret"] as? String ?? "",
                       context: obj["context"] as? String ?? "", mode: obj["mode"] as? String ?? "", transports: ts)
        try validate(c)
        return c
    }

    private static func quote(_ s: String) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: [s]), var t = String(data: d, encoding: .utf8) else { return "\"\"" }
        t.removeFirst(); t.removeLast()
        return t.replacingOccurrences(of: "\\/", with: "/")
    }

    /// JSON в порядке полей Go (`share.Config`), с теми же omitempty.
    static func toJSON(_ c: Config) -> String {
        var parts: [String] = []
        if !c.name.isEmpty { parts.append("\"name\":\(quote(c.name))") }
        if c.negotiate { parts.append("\"negotiate\":true") }
        if !c.codec.isEmpty { parts.append("\"codec\":\(quote(c.codec))") }
        if !c.secret.isEmpty { parts.append("\"secret\":\(quote(c.secret))") }
        if !c.context.isEmpty { parts.append("\"context\":\(quote(c.context))") }
        if !c.mode.isEmpty { parts.append("\"mode\":\(quote(c.mode))") }
        let ts = c.transports.map { t -> String in
            var f = ["\"type\":\(quote(t.type))"]
            if !t.name.isEmpty { f.append("\"name\":\(quote(t.name))") }
            if !t.url.isEmpty { f.append("\"url\":\(quote(t.url))") }
            if t.priority != 0 { f.append("\"priority\":\(t.priority)") }
            if !t.dial.isEmpty { f.append("\"dial\":\(quote(t.dial))") }
            return "{" + f.joined(separator: ",") + "}"
        }
        parts.append("\"transports\":[" + ts.joined(separator: ",") + "]")
        return "{" + parts.joined(separator: ",") + "}"
    }

    /// Ссылка для конфигурации. DEFLATE — «хранимыми» блоками (допустимый поток, ядро и официальные клиенты читают его
    /// так же): без сжатия ссылка на треть длиннее, зато одинакова на любой платформе без внешних библиотек.
    public static func encode(_ c: Config) throws -> String {
        try validate(c)
        let raw = [UInt8](toJSON(c).utf8)
        var out: [UInt8] = []
        var i = 0
        repeat {
            let n = min(65535, raw.count - i)
            let last: UInt8 = (i + n >= raw.count) ? 1 : 0
            out += [last, UInt8(n & 0xFF), UInt8(n >> 8), UInt8(~n & 0xFF), UInt8((~n >> 8) & 0xFF)]
            out += raw[i..<(i + n)]
            i += n
        } while i < raw.count
        let b64 = Data(out).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return prefix + b64
    }
}

/// Сессия OpenFlux в `extra.session` профиля, импортированного из ссылки v1 (поля — как у `OpenFluxLink` на Android).
public enum OpenFluxSession {
    /// Описание сессии для `Hydraflux.StartSession`: `{"context":…,"transports":[{type,url,priority}]}`; nil — обычный профиль.
    /// Для direct в `url` — адрес узла (так хранит профиль ядро).
    public static func specsJSON(_ extra: [String: Any]) -> String? {
        guard let s = extra["session"] as? [String: Any], let arr = s["transports"] as? [[String: Any]], !arr.isEmpty else { return nil }
        let specs: [[String: Any]] = arr.map { t in
            let type = t["type"] as? String ?? ""
            let url = type == "direct" ? (t["dial"] as? String ?? "") : (t["url"] as? String ?? "")
            return ["type": type, "url": url, "priority": (t["priority"] as? NSNumber)?.intValue ?? 0]
        }
        let obj: [String: Any] = ["context": s["context"] as? String ?? "", "transports": specs]
        guard let d = try? JSONSerialization.data(withJSONObject: obj), let str = String(data: d, encoding: .utf8) else { return nil }
        return str
    }

    public static func count(_ extra: [String: Any]) -> Int {
        ((extra["session"] as? [String: Any])?["transports"] as? [Any])?.count ?? 0
    }
}

/// Сырой DEFLATE (RFC 1951) — порт `puff.c`; размер результата ограничен.
enum Inflate {
    enum Failure: Error { case damaged, tooLarge }

    private struct Huffman {
        var count = [Int](repeating: 0, count: 16)
        var symbol: [Int]

        /// Возвращает таблицу и «остаток» кодового пространства (<0 — переполнено, >0 — неполный код).
        static func make(_ lengths: [Int]) -> (Huffman, Int) {
            var h = Huffman(symbol: [Int](repeating: 0, count: lengths.count))
            for l in lengths { h.count[l] += 1 }
            if h.count[0] == lengths.count { return (h, 0) }
            var left = 1
            for len in 1...15 {
                left <<= 1
                left -= h.count[len]
                if left < 0 { return (h, left) }
            }
            var offs = [Int](repeating: 0, count: 16)
            for len in 1..<15 { offs[len + 1] = offs[len] + h.count[len] }
            for (sym, l) in lengths.enumerated() where l != 0 {
                h.symbol[offs[l]] = sym
                offs[l] += 1
            }
            return (h, left)
        }
    }

    private static let lbase = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258]
    private static let lext = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0]
    private static let dbase = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073,
                                4097, 6145, 8193, 12289, 16385, 24577]
    private static let dext = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13]

    private struct State {
        let src: [UInt8]
        let limit: Int
        var pos = 0
        var bitBuf = 0
        var bitCnt = 0
        var out: [UInt8] = []

        init(src: [UInt8], limit: Int) { self.src = src; self.limit = limit }

        mutating func bits(_ need: Int) throws -> Int {
            var val = bitBuf
            while bitCnt < need {
                guard pos < src.count else { throw Failure.damaged }
                val |= Int(src[pos]) << bitCnt
                pos += 1
                bitCnt += 8
            }
            bitBuf = val >> need
            bitCnt -= need
            return val & ((1 << need) - 1)
        }

        mutating func decode(_ h: Huffman) throws -> Int {
            var code = 0, first = 0, index = 0
            for len in 1...15 {
                code |= try bits(1)
                let count = h.count[len]
                if code - count < first { return h.symbol[index + (code - first)] }
                index += count
                first += count
                first <<= 1
                code <<= 1
            }
            throw Failure.damaged
        }

        mutating func codes(_ lit: Huffman, _ dist: Huffman) throws {
            while true {
                var sym = try decode(lit)
                if sym < 256 {
                    out.append(UInt8(sym))
                } else if sym == 256 {
                    return
                } else {
                    sym -= 257
                    guard sym < 29 else { throw Failure.damaged }
                    let extraLen = try bits(lext[sym])
                    let len = lbase[sym] + extraLen
                    let ds = try decode(dist)
                    guard ds < 30 else { throw Failure.damaged }
                    let extraDist = try bits(dext[ds])
                    let d = dbase[ds] + extraDist
                    guard d <= out.count else { throw Failure.damaged }
                    for _ in 0..<len { out.append(out[out.count - d]) }
                }
                if out.count > limit { throw Failure.tooLarge }
            }
        }

        mutating func stored() throws {
            bitBuf = 0
            bitCnt = 0
            guard pos + 4 <= src.count else { throw Failure.damaged }
            let len = Int(src[pos]) | (Int(src[pos + 1]) << 8)
            let nlen = Int(src[pos + 2]) | (Int(src[pos + 3]) << 8)
            pos += 4
            guard len == (~nlen & 0xFFFF), pos + len <= src.count else { throw Failure.damaged }
            out += src[pos..<(pos + len)]
            pos += len
            if out.count > limit { throw Failure.tooLarge }
        }

        mutating func fixed() throws {
            var l = [Int](repeating: 8, count: 288)
            for i in 144..<256 { l[i] = 9 }
            for i in 256..<280 { l[i] = 7 }
            let (lit, _) = Huffman.make(l)
            let (dist, _) = Huffman.make([Int](repeating: 5, count: 30))
            try codes(lit, dist)
        }

        mutating func dynamic() throws {
            let nlen = try bits(5) + 257
            let ndist = try bits(5) + 1
            let ncode = try bits(4) + 4
            guard nlen <= 286, ndist <= 30 else { throw Failure.damaged }
            let order = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]
            var lengths = [Int](repeating: 0, count: 320)
            for i in 0..<ncode { lengths[order[i]] = try bits(3) }
            let (lencode, left) = Huffman.make(Array(lengths[0..<19]))
            guard left == 0 else { throw Failure.damaged }
            lengths = [Int](repeating: 0, count: 320)
            var index = 0
            while index < nlen + ndist {
                let sym = try decode(lencode)
                if sym < 16 {
                    lengths[index] = sym
                    index += 1
                    continue
                }
                var len = 0
                var rep = 0
                if sym == 16 {
                    guard index > 0 else { throw Failure.damaged }
                    len = lengths[index - 1]
                    rep = 3 + (try bits(2))
                } else if sym == 17 {
                    rep = 3 + (try bits(3))
                } else {
                    rep = 11 + (try bits(7))
                }
                guard index + rep <= nlen + ndist else { throw Failure.damaged }
                for _ in 0..<rep {
                    lengths[index] = len
                    index += 1
                }
            }
            guard lengths[256] != 0 else { throw Failure.damaged }
            let (lit, l1) = Huffman.make(Array(lengths[0..<nlen]))
            guard l1 >= 0, l1 == 0 || nlen - lit.count[0] == 1 else { throw Failure.damaged }
            let (dist, l2) = Huffman.make(Array(lengths[nlen..<(nlen + ndist)]))
            guard l2 >= 0, l2 == 0 || ndist - dist.count[0] == 1 else { throw Failure.damaged }
            try codes(lit, dist)
        }
    }

    static func raw(_ src: [UInt8], limit: Int) throws -> Data {
        var st = State(src: src, limit: limit)
        var last = 0
        repeat {
            last = try st.bits(1)
            switch try st.bits(2) {
            case 0: try st.stored()
            case 1: try st.fixed()
            case 2: try st.dynamic()
            default: throw Failure.damaged
            }
        } while last == 0
        return Data(st.out)
    }
}
