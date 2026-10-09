import Foundation
#if canImport(FoundationNetworking)
    import FoundationNetworking
#endif

/// Geo-базы как динамический слой (0.7.13) — порт `data/geo` с Android: источники, проверка, атомарная замена, откат,
/// обновление с зеркалами, конвертация `.dat`. Вшитые базы остаются запасными (бандл расширения).
public enum GeoKind: String, Codable, CaseIterable, Sendable {
    case ip = "geoip", site = "geosite"
}

public enum GeoFormat: String, Codable, Sendable { case srs, dat }

public struct GeoSource: Hashable, Sendable {
    public var id: String
    public var title: String
    public var format: GeoFormat
    public var ip: [String]
    public var site: [String]
    public func templates(_ k: GeoKind) -> [String] { k == .ip ? ip : site }
}

public enum GeoSources {
    private static let meta = "MetaCubeX/meta-rules-dat"
    private static let runet = "runetfreedom/russia-v2ray-rules-dat"

    public static let metacubex = GeoSource(
        id: "metacubex", title: "MetaCubeX (meta-rules-dat)", format: .srs,
        ip: ["https://raw.githubusercontent.com/\(meta)/sing/geo/geoip/{name}.srs", "https://cdn.jsdelivr.net/gh/\(meta)@sing/geo/geoip/{name}.srs"],
        site: ["https://raw.githubusercontent.com/\(meta)/sing/geo/geosite/{name}.srs", "https://cdn.jsdelivr.net/gh/\(meta)@sing/geo/geosite/{name}.srs"])
    public static let sagernet = GeoSource(
        id: "sagernet", title: "SagerNet (sing-geoip / sing-geosite)", format: .srs,
        ip: ["https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-{name}.srs", "https://cdn.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-{name}.srs"],
        site: ["https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-{name}.srs", "https://cdn.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-{name}.srs"])
    public static let runetfreedom = GeoSource(
        id: "runetfreedom", title: "runetfreedom (russia-v2ray-rules-dat)", format: .srs,
        ip: ["https://raw.githubusercontent.com/\(runet)/release/sing-box/rule-set-geoip/geoip-{name}.srs", "https://cdn.jsdelivr.net/gh/\(runet)@release/sing-box/rule-set-geoip/geoip-{name}.srs"],
        site: ["https://raw.githubusercontent.com/\(runet)/release/sing-box/rule-set-geosite/geosite-{name}.srs", "https://cdn.jsdelivr.net/gh/\(runet)@release/sing-box/rule-set-geosite/geosite-{name}.srs"])
    public static let v2flyDat = GeoSource(
        id: "v2fly-dat", title: "v2fly (geoip.dat / dlc.dat)", format: .dat,
        ip: ["https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"],
        site: ["https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"])
    public static let runetfreedomDat = GeoSource(
        id: "runetfreedom-dat", title: "runetfreedom (geoip.dat / geosite.dat)", format: .dat,
        ip: ["https://github.com/\(runet)/releases/latest/download/geoip.dat"],
        site: ["https://github.com/\(runet)/releases/latest/download/geosite.dat"])

    public static let all = [metacubex, sagernet, runetfreedom, v2flyDat, runetfreedomDat]
    public static func byId(_ id: String?) -> GeoSource { all.first { $0.id == id } ?? metacubex }
}

public struct CustomGeoSource: Codable, Hashable, Sendable {
    public static let typeSrs = "srs", typeList = "list", typeDat = "dat"
    public var kind: GeoKind
    public var name: String
    public var url: String
    public var type: String
    public init(kind: GeoKind, name: String, url: String, type: String = CustomGeoSource.typeSrs) {
        self.kind = kind; self.name = name; self.url = url; self.type = type
    }
}

public struct GeoSettings: Codable, Hashable, Sendable {
    public var autoUpdate = true
    public var intervalHours = 24
    public var ipSource = GeoSources.metacubex.id
    public var siteSource = GeoSources.metacubex.id
    public var extraIp: [String] = []
    public var extraSite: [String] = []
    public var custom: [CustomGeoSource] = []
    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        autoUpdate = try c.decodeIfPresent(Bool.self, forKey: .autoUpdate) ?? true
        intervalHours = min(max(try c.decodeIfPresent(Int.self, forKey: .intervalHours) ?? 24, 1), 24 * 30)
        ipSource = try c.decodeIfPresent(String.self, forKey: .ipSource) ?? GeoSources.metacubex.id
        siteSource = try c.decodeIfPresent(String.self, forKey: .siteSource) ?? GeoSources.metacubex.id
        extraIp = try c.decodeIfPresent([String].self, forKey: .extraIp) ?? []
        extraSite = try c.decodeIfPresent([String].self, forKey: .extraSite) ?? []
        custom = try c.decodeIfPresent([CustomGeoSource].self, forKey: .custom) ?? []
    }
}

/// SHA-256 без CryptoKit — HydraKit собирается и тестируется ещё и на Linux.
enum Sha256 {
    private static let k: [UInt32] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ]

    static func hex(_ data: Data) -> String {
        var h: [UInt32] = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
        var msg = [UInt8](data)
        let bitLen = UInt64(msg.count) * 8
        msg.append(0x80)
        while msg.count % 64 != 56 { msg.append(0) }
        for i in (0..<8).reversed() { msg.append(UInt8((bitLen >> (UInt64(i) * 8)) & 0xff)) }
        func rotr(_ x: UInt32, _ n: UInt32) -> UInt32 { (x >> n) | (x << (32 - n)) }
        for chunk in stride(from: 0, to: msg.count, by: 64) {
            var w = [UInt32](repeating: 0, count: 64)
            for i in 0..<16 {
                let j = chunk + i * 4
                w[i] = UInt32(msg[j]) << 24 | UInt32(msg[j + 1]) << 16 | UInt32(msg[j + 2]) << 8 | UInt32(msg[j + 3])
            }
            for i in 16..<64 {
                let s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3)
                let s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10)
                w[i] = w[i - 16] &+ s0 &+ w[i - 7] &+ s1
            }
            var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7]
            for i in 0..<64 {
                let s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
                let ch = (e & f) ^ (~e & g)
                let t1 = hh &+ s1 &+ ch &+ k[i] &+ w[i]
                let s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
                let maj = (a & b) ^ (a & c) ^ (b & c)
                let t2 = s0 &+ maj
                hh = g; g = f; f = e; e = d &+ t1; d = c; c = b; b = a; a = t1 &+ t2
            }
            h[0] = h[0] &+ a; h[1] = h[1] &+ b; h[2] = h[2] &+ c; h[3] = h[3] &+ d
            h[4] = h[4] &+ e; h[5] = h[5] &+ f; h[6] = h[6] &+ g; h[7] = h[7] &+ hh
        }
        return h.map { String(format: "%08x", $0) }.joined()
    }
}

/// Хранилище скачанных баз: `<dir>/geoip|geosite/<имя>.srs` (бинарный rule-set) или `.json` (source), рядом `.prev` для отката
/// и `meta.json`. Каталог — `HydraStore.geoDirectory` (App Group): пишет приложение, читает расширение туннеля.
public final class GeoStore: @unchecked Sendable {
    public struct Entry: Hashable, Sendable, Identifiable {
        public var kind: GeoKind, name: String, path: String, sha256: String
        public var size: Int, updatedAt: Date, source: String, hasPrev: Bool
        public var id: String { "\(kind.rawValue)/\(name)" }
    }
    public enum Outcome: Sendable { case installed, unchanged, rejected }
    public struct Installed: Sendable { public var outcome: Outcome; public var reason: String = "" }

    public static let maxBytes = 40 * 1024 * 1024
    private let dir: URL
    /// Хранилище открывают из нескольких мест (экран, фоновая задача, подключение): блокировка одна на процесс.
    private let lock = GeoStore.globalLock
    private static let globalLock = NSLock()
    private var metaURL: URL { dir.appendingPathComponent("meta.json") }

    public init(directory: URL) { dir = directory }

    private func meta() -> [String: [String: Any]] {
        guard let d = try? Data(contentsOf: metaURL), let o = try? JSONSerialization.jsonObject(with: d) as? [String: [String: Any]] else { return [:] }
        return o
    }
    private func saveMeta(_ m: [String: [String: Any]]) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let d = try? JSONSerialization.data(withJSONObject: m, options: [.sortedKeys]) { try? d.write(to: metaURL, options: .atomic) }
    }
    private func key(_ k: GeoKind, _ n: String) -> String { "\(k.rawValue)/\(n)" }
    private static func safe(_ n: String) -> String {
        let s = String(n.filter { $0.isLetter || $0.isNumber || "-_.!".contains($0) })
        return (s.isEmpty ? "x" : s).replacingOccurrences(of: "..", with: "_")
    }
    private func file(_ k: GeoKind, _ n: String, source: Bool) -> URL {
        dir.appendingPathComponent("\(k.rawValue)/\(Self.safe(n)).\(source ? "json" : "srs")")
    }

    /// Путь к скачанной базе; nil — нет или повреждена (берётся вшитая).
    public func resolve(_ k: GeoKind, _ name: String) -> URL? {
        lock.lock(); defer { lock.unlock() }
        guard let e = meta()[key(k, name)] else { return nil }
        let f = file(k, name, source: (e["format"] as? String) == "source")
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: f.path), (attrs[.size] as? NSNumber)?.intValue == (e["size"] as? NSNumber)?.intValue else { return nil }
        return f
    }

    public func entries() -> [Entry] {
        lock.lock(); defer { lock.unlock() }
        return meta().compactMap { key, e -> Entry? in
            guard let kind = GeoKind(rawValue: String(key.split(separator: "/").first ?? "")) else { return nil }
            let name = String(key.drop(while: { $0 != "/" }).dropFirst())
            let f = file(kind, name, source: (e["format"] as? String) == "source")
            guard FileManager.default.fileExists(atPath: f.path) else { return nil }
            return Entry(kind: kind, name: name, path: f.path, sha256: e["sha256"] as? String ?? "", size: (e["size"] as? NSNumber)?.intValue ?? 0,
                         updatedAt: Date(timeIntervalSince1970: ((e["updated"] as? NSNumber)?.doubleValue ?? 0) / 1000),
                         source: e["source"] as? String ?? "", hasPrev: FileManager.default.fileExists(atPath: f.path + ".prev"))
        }.sorted { ($0.kind.rawValue, $0.name) < ($1.kind.rawValue, $1.name) }
    }

    public func lastChecked() -> Date {
        lock.lock(); defer { lock.unlock() }
        let ms = meta().values.compactMap { ($0["checked"] as? NSNumber)?.doubleValue }.max() ?? 0
        return Date(timeIntervalSince1970: ms / 1000)
    }

    /// null — файл годится.
    public static func validate(_ b: Data, source: Bool) -> String? {
        if b.count < 8 { return "файл слишком короткий" }
        if b.count > maxBytes { return "файл слишком большой" }
        if source {
            guard let o = try? JSONSerialization.jsonObject(with: b) as? [String: Any], let r = o["rules"] as? [Any] else { return "не rule-set JSON" }
            return r.isEmpty ? "в списке нет правил" : nil
        }
        let a = [UInt8](b.prefix(4))
        if a[0] != 0x53 || a[1] != 0x52 || a[2] != 0x53 { return "это не rule-set sing-box (.srs)" }
        return (1...3).contains(Int(a[3])) ? nil : "версия rule-set \(a[3]) не поддерживается ядром"
    }

    public func install(_ k: GeoKind, _ name: String, _ bytes: Data, source: Bool, from: String, now: Date = Date()) -> Installed {
        if let why = Self.validate(bytes, source: source) { return Installed(outcome: .rejected, reason: why) }
        lock.lock(); defer { lock.unlock() }
        let sha = Sha256.hex(bytes)
        var m = meta()
        let kk = key(k, name)
        let nowMs = now.timeIntervalSince1970 * 1000
        let old = m[kk]
        let target = file(k, name, source: source)
        let oldFile = old.map { file(k, name, source: ($0["format"] as? String) == "source") }
        let fm = FileManager.default
        if let old, (old["sha256"] as? String) == sha, let oldFile, fm.fileExists(atPath: oldFile.path) {
            var o = old; o["checked"] = nowMs; m[kk] = o; saveMeta(m)
            return Installed(outcome: .unchanged)
        }
        if let old, let oldFile, fm.fileExists(atPath: oldFile.path), (old["format"] as? String) == (source ? "source" : "srs"),
           let sz = (try? fm.attributesOfItem(atPath: oldFile.path))?[.size] as? NSNumber, Double(bytes.count) < sz.doubleValue * 0.4 {
            return Installed(outcome: .rejected, reason: "новая база подозрительно мала (\(bytes.count) Б против \(sz.intValue) Б)")
        }
        do {
            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            let tmp = URL(fileURLWithPath: target.path + ".tmp")
            try bytes.write(to: tmp, options: .atomic)
            if let oldFile, fm.fileExists(atPath: oldFile.path) {
                let prev = URL(fileURLWithPath: oldFile.path + ".prev")
                try? fm.removeItem(at: prev)
                if oldFile.path == target.path { try? fm.copyItem(at: oldFile, to: prev) } else { try? fm.moveItem(at: oldFile, to: prev) }
            }
            if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
            try fm.moveItem(at: tmp, to: target)
        } catch {
            return Installed(outcome: .rejected, reason: "не удалось записать: \(error.localizedDescription)")
        }
        m[kk] = ["sha256": sha, "size": bytes.count, "updated": nowMs, "checked": nowMs, "source": from, "format": source ? "source" : "srs"]
        saveMeta(m)
        return Installed(outcome: .installed)
    }

    @discardableResult
    public func rollback(_ k: GeoKind, _ name: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        var m = meta()
        let kk = key(k, name)
        guard let e = m[kk] else { return false }
        let fm = FileManager.default
        let cur = file(k, name, source: (e["format"] as? String) == "source")
        let candidates = [URL(fileURLWithPath: cur.path + ".prev"), URL(fileURLWithPath: file(k, name, source: (e["format"] as? String) != "source").path + ".prev")]
        guard let prev = candidates.first(where: { fm.fileExists(atPath: $0.path) }), let bytes = try? Data(contentsOf: prev) else { return false }
        let prevSource = prev.lastPathComponent.hasSuffix(".json.prev")
        if Self.validate(bytes, source: prevSource) != nil { return false }
        try? fm.removeItem(at: cur)
        let restored = file(k, name, source: prevSource)
        guard (try? bytes.write(to: restored, options: .atomic)) != nil else { return false }
        try? fm.removeItem(at: prev)
        let ms = Date().timeIntervalSince1970 * 1000
        m[kk] = ["sha256": Sha256.hex(bytes), "size": bytes.count, "updated": ms, "checked": ms, "source": "rollback", "format": prevSource ? "source" : "srs"]
        saveMeta(m)
        return true
    }

    public func remove(_ k: GeoKind, _ name: String) {
        lock.lock(); defer { lock.unlock() }
        var m = meta()
        guard let e = m[key(k, name)] else { return }
        let f = file(k, name, source: (e["format"] as? String) == "source")
        [f, URL(fileURLWithPath: f.path + ".prev")].forEach { try? FileManager.default.removeItem(at: $0) }
        m.removeValue(forKey: key(k, name)); saveMeta(m)
    }

    /// Текстовый список → rule-set «source»: с `/` или IP — CIDR, остальное — домены (домен + поддомены).
    public static func listToSource(_ text: String, _ kind: GeoKind) -> Data {
        let items = text.split(whereSeparator: \.isNewline).map { String($0.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? "").trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        var rule: [String: Any] = [:]
        if kind == .ip {
            rule["ip_cidr"] = items.map { $0.contains("/") ? $0 : ($0.contains(":") ? "\($0)/128" : "\($0)/32") }
        } else {
            let suffix = items.filter { !$0.hasPrefix("full:") && !$0.hasPrefix("keyword:") }.map { s -> String in
                var v = s; if v.hasPrefix("domain:") { v.removeFirst(7) }; if v.hasPrefix(".") { v.removeFirst() }; return v
            }
            rule["domain_suffix"] = suffix
            let full = items.filter { $0.hasPrefix("full:") }.map { String($0.dropFirst(5)) }
            if !full.isEmpty { rule["domain"] = full }
            let kw = items.filter { $0.hasPrefix("keyword:") }.map { String($0.dropFirst(8)) }
            if !kw.isEmpty { rule["domain_keyword"] = kw }
        }
        return (try? JSONSerialization.data(withJSONObject: ["version": 2, "rules": [rule]])) ?? Data()
    }
}

/// Достаёт одну запись из `geoip.dat` / `geosite.dat` (protobuf v2fly) и пишет её как rule-set «source».
public enum DatConverter {
    private struct Reader {
        let d: Data
        var p: Int
        init(_ d: Data, _ p: Int = 0) { self.d = d; self.p = p }
        var atEnd: Bool { p >= d.endIndex - d.startIndex }
        mutating func varint() -> UInt64? {
            var shift: UInt64 = 0, r: UInt64 = 0
            while p < d.count {
                let b = d[d.startIndex + p]; p += 1
                r |= UInt64(b & 0x7f) << shift
                if b & 0x80 == 0 { return r }
                shift += 7
                if shift > 63 { return nil }
            }
            return nil
        }
        mutating func bytes(_ n: Int) -> Data? {
            guard n >= 0, p + n <= d.count else { return nil }
            let s = d.startIndex + p
            p += n
            return d.subdata(in: s..<(s + n))
        }
    }

    private enum Field { case num(UInt64), data(Data) }

    private static func fields(_ b: Data) -> [(Int, Field)] {
        var r = Reader(b)
        var out: [(Int, Field)] = []
        while !r.atEnd {
            guard let tag = r.varint() else { break }
            let num = Int(tag >> 3)
            switch tag & 7 {
            case 0: guard let v = r.varint() else { return out }; out.append((num, .num(v)))
            case 2: guard let n = r.varint(), let x = r.bytes(Int(n)) else { return out }; out.append((num, .data(x)))
            case 1: r.p += 8
            case 5: r.p += 4
            default: return out
            }
        }
        return out
    }

    /// nil — записи с таким именем нет.
    public static func extract(_ data: Data, kind: GeoKind, name: String) -> Data? {
        let want = name.lowercased()
        var r = Reader(data)
        while !r.atEnd {
            guard let tag = r.varint() else { return nil }
            let type = tag & 7
            if type != 2 { if type == 0 { _ = r.varint(); continue } else { return nil } }
            guard let len = r.varint() else { return nil }
            if tag >> 3 != 1 { r.p += Int(len); continue }
            guard let entry = r.bytes(Int(len)) else { return nil }
            let f = fields(entry)
            var code = ""
            for (n, v) in f where n == 1 { if case .data(let d) = v { code = String(decoding: d, as: UTF8.self).lowercased() }; break }
            if code != want { continue }
            return kind == .ip ? ipSource(f) : siteSource(f)
        }
        return nil
    }

    private static func ipSource(_ f: [(Int, Field)]) -> Data {
        var cidr: [String] = []
        for (n, v) in f where n == 2 {
            guard case .data(let d) = v else { continue }
            var ip: Data?, prefix = 0
            for (cn, cv) in fields(d) {
                if cn == 1, case .data(let x) = cv { ip = x }
                if cn == 2, case .num(let x) = cv { prefix = Int(x) }
            }
            guard let ip else { continue }
            let b = [UInt8](ip)
            if b.count == 4 { cidr.append("\(b[0]).\(b[1]).\(b[2]).\(b[3])/\(prefix)") }
            else if b.count == 16 {
                let groups = stride(from: 0, to: 16, by: 2).map { String(format: "%x", Int(b[$0]) << 8 | Int(b[$0 + 1])) }
                cidr.append(groups.joined(separator: ":") + "/\(prefix)")
            }
        }
        return (try? JSONSerialization.data(withJSONObject: ["version": 2, "rules": [["ip_cidr": cidr]]])) ?? Data()
    }

    private static func siteSource(_ f: [(Int, Field)]) -> Data {
        var kw: [String] = [], rx: [String] = [], suffix: [String] = [], full: [String] = []
        for (n, v) in f where n == 2 {
            guard case .data(let d) = v else { continue }
            var type = 0, value = ""
            for (dn, dv) in fields(d) {
                if dn == 1, case .num(let x) = dv { type = Int(x) }
                if dn == 2, case .data(let x) = dv { value = String(decoding: x, as: UTF8.self) }
            }
            if value.isEmpty { continue }
            switch type { case 0: kw.append(value); case 1: rx.append(value); case 2: suffix.append(value); default: full.append(value) }
        }
        var rule: [String: Any] = [:]
        if !suffix.isEmpty { rule["domain_suffix"] = suffix }
        if !full.isEmpty { rule["domain"] = full }
        if !kw.isEmpty { rule["domain_keyword"] = kw }
        if !rx.isEmpty { rule["domain_regex"] = rx }
        return (try? JSONSerialization.data(withJSONObject: ["version": 2, "rules": [rule]])) ?? Data()
    }
}

/// Скачивание: протокол, чтобы подменять в тестах. Бросает при любой ошибке или превышении `maxBytes`.
public protocol GeoHttp: Sendable {
    func get(_ url: String, maxBytes: Int) async throws -> Data
    /// Скачать в файл: большие `.dat` не держим в памяти.
    func getFile(_ url: String, maxBytes: Int, to dest: URL) async throws
}

public extension GeoHttp {
    func getFile(_ url: String, maxBytes: Int, to dest: URL) async throws {
        try await get(url, maxBytes: maxBytes).write(to: dest, options: .atomic)
    }
}

public struct URLSessionGeoHttp: GeoHttp {
    public init() {}
    public func get(_ url: String, maxBytes: Int) async throws -> Data {
        guard let u = URL(string: url), u.scheme == "https" else { throw URLError(.badURL) }
        var req = URLRequest(url: u, timeoutInterval: 25)
        req.setValue("HydraVPN-geo", forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        guard data.count <= maxBytes else { throw URLError(.dataLengthExceedsMaximum) }
        return data
    }

    public func getFile(_ url: String, maxBytes: Int, to dest: URL) async throws {
        guard let u = URL(string: url), u.scheme == "https" else { throw URLError(.badURL) }
        var req = URLRequest(url: u, timeoutInterval: 60)
        req.setValue("HydraVPN-geo", forHTTPHeaderField: "User-Agent")
        let (tmp, resp) = try await URLSession.shared.download(for: req)
        defer { try? FileManager.default.removeItem(at: tmp) }
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        let size = (try? FileManager.default.attributesOfItem(atPath: tmp.path))?[.size] as? NSNumber
        guard (size?.intValue ?? Int.max) <= maxBytes else { throw URLError(.dataLengthExceedsMaximum) }
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)
    }
}

public struct GeoUpdater: Sendable {
    public struct Item: Sendable { public var kind: GeoKind; public var name: String; public var outcome: GeoStore.Outcome?; public var message: String }
    public struct Report: Sendable {
        public var items: [Item]
        public var installed: Int { items.filter { $0.outcome == .installed }.count }
        public var unchanged: Int { items.filter { $0.outcome == .unchanged }.count }
        public var failed: Int { items.filter { $0.outcome == nil || $0.outcome == .rejected }.count }
    }
    public static let datMax = 120 * 1024 * 1024

    public let store: GeoStore
    public let http: GeoHttp
    public init(store: GeoStore, http: GeoHttp = URLSessionGeoHttp()) { self.store = store; self.http = http }

    public func update(_ s: GeoSettings, wanted: [(GeoKind, String)], log: @Sendable (String) -> Void = { _ in }) async -> Report {
        var items: [Item] = []
        var dat: [String: URL] = [:]
        defer { dat.values.forEach { try? FileManager.default.removeItem(at: $0) } }
        var seen = Set<String>()
        for (kind, raw) in wanted {
            let name = raw.trimmingCharacters(in: .whitespaces).lowercased()
            if name.isEmpty || !seen.insert("\(kind.rawValue)/\(name)").inserted { continue }
            let item = await updateOne(s, kind, name, &dat, log)
            log("Geo-базы: \(kind.rawValue)/\(name) — \(item.outcome.map { "\($0)" } ?? "ошибка")\(item.message.isEmpty ? "" : " (\(item.message))")")
            items.append(item)
        }
        return Report(items: items)
    }

    private func datFile(_ url: String, _ dat: inout [String: URL]) async throws -> URL {
        if let f = dat[url] { return f }
        let f = FileManager.default.temporaryDirectory.appendingPathComponent("geo-dat-\(UUID().uuidString).tmp")
        try await http.getFile(url, maxBytes: Self.datMax, to: f)
        dat[url] = f
        return f
    }

    private func updateOne(_ s: GeoSettings, _ kind: GeoKind, _ name: String, _ dat: inout [String: URL], _ log: @Sendable (String) -> Void) async -> Item {
        do {
            if let c = s.custom.first(where: { $0.kind == kind && $0.name.lowercased() == name }) { return try await fromCustom(c, kind, name, &dat) }
            let src = GeoSources.byId(kind == .ip ? s.ipSource : s.siteSource)
            if src.format == .dat {
                let url = src.templates(kind)[0]
                return fromDat(try await datFile(url, &dat), kind, name, src.id)
            }
            let remotes = (kind == .site && name.count == 2) ? [name, "category-\(name)"] : [name]
            var last = "нет ответа"
            for remote in remotes {
                for t in src.templates(kind) {
                    let url = t.replacingOccurrences(of: "{name}", with: remote)
                    do {
                        let r = store.install(kind, name, try await http.get(url, maxBytes: GeoStore.maxBytes), source: false, from: src.id)
                        return Item(kind: kind, name: name, outcome: r.outcome, message: r.reason)
                    } catch { last = error.localizedDescription; log("Geo-базы: \(url) — \(last)") }
                }
            }
            return Item(kind: kind, name: name, outcome: nil, message: last)
        } catch {
            return Item(kind: kind, name: name, outcome: nil, message: error.localizedDescription)
        }
    }

    private func fromCustom(_ c: CustomGeoSource, _ kind: GeoKind, _ name: String, _ dat: inout [String: URL]) async throws -> Item {
        if c.type == CustomGeoSource.typeDat { return fromDat(try await datFile(c.url, &dat), kind, name, "custom") }
        let bytes = try await http.get(c.url, maxBytes: GeoStore.maxBytes)
        switch c.type {
        case CustomGeoSource.typeSrs:
            let r = store.install(kind, name, bytes, source: false, from: "custom")
            return Item(kind: kind, name: name, outcome: r.outcome, message: r.reason)
        case CustomGeoSource.typeList:
            let r = store.install(kind, name, GeoStore.listToSource(String(decoding: bytes, as: UTF8.self), kind), source: true, from: "custom")
            return Item(kind: kind, name: name, outcome: r.outcome, message: r.reason)
        default:
            let r = store.install(kind, name, GeoStore.listToSource(String(decoding: bytes, as: UTF8.self), kind), source: true, from: "custom")
            return Item(kind: kind, name: name, outcome: r.outcome, message: r.reason)
        }
    }

    private func fromDat(_ file: URL, _ kind: GeoKind, _ name: String, _ from: String) -> Item {
        guard let data = try? Data(contentsOf: file, options: .mappedIfSafe) else {
            return Item(kind: kind, name: name, outcome: nil, message: "не удалось прочитать скачанный .dat")
        }
        let json = (kind == .site && name.count == 2 ? DatConverter.extract(data, kind: kind, name: "category-\(name)") : nil)
            ?? DatConverter.extract(data, kind: kind, name: name)
        guard let json else { return Item(kind: kind, name: name, outcome: nil, message: "в .dat нет записи «\(name)»") }
        let r = store.install(kind, name, json, source: true, from: from)
        return Item(kind: kind, name: name, outcome: r.outcome, message: r.reason)
    }
}
