import Foundation

/// Служебные заголовки ответа подписки (Remnawave / Marzban / 3x-ui / Happ / v2rayN) —
/// порт `SubscriptionHeaders` с Android.
public enum SubscriptionHeaders {
    public struct Info: Equatable, Sendable {
        public var title = ""
        public var upload: Int64 = 0
        public var download: Int64 = 0
        public var total: Int64 = 0
        public var expire: Int64 = 0
        public var updateHours: Int?
        public var supportUrl = ""
    }

    /// `header` — поиск заголовка без учёта регистра.
    public static func parse(_ header: (String) -> String?) -> Info {
        let user = parseUserInfo(header("subscription-userinfo") ?? "")
        var info = Info()
        info.title = decodeTitle(header("profile-title") ?? "")
        if info.title.isEmpty { info.title = fromContentDisposition(header("content-disposition") ?? "") }
        info.upload = user["upload"] ?? 0
        info.download = user["download"] ?? 0
        info.total = user["total"] ?? 0
        info.expire = user["expire"] ?? 0
        if let h = Int((header("profile-update-interval") ?? "").trimmingCharacters(in: .whitespaces)), (1...168).contains(h) {
            info.updateHours = h
        }
        info.supportUrl = (header("support-url") ?? "").trimmingCharacters(in: .whitespaces)
        return info
    }

    static func decodeTitle(_ raw: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespaces)
        if t.lowercased().hasPrefix("base64:") {
            guard let d = Data(base64Encoded: String(t.dropFirst(7)).trimmingCharacters(in: .whitespaces),
                               options: .ignoreUnknownCharacters) else { return "" }
            return (String(data: d, encoding: .utf8) ?? "").trimmingCharacters(in: .whitespaces)
        }
        return t
    }

    static func parseUserInfo(_ raw: String) -> [String: Int64] {
        var out: [String: Int64] = [:]
        for part in raw.split(separator: ";") {
            let kv = part.split(separator: "=", maxSplits: 1)
            guard kv.count == 2, let v = Double(kv[1].trimmingCharacters(in: .whitespaces)) else { continue }
            out[kv[0].trimmingCharacters(in: .whitespaces).lowercased()] = Int64(v)
        }
        return out
    }

    static func fromContentDisposition(_ raw: String) -> String {
        let patterns = [#"filename\*=(?:UTF-8'')?([^;]+)"#, #"filename="?([^";]+)"?"#]
        for p in patterns {
            guard let re = try? NSRegularExpression(pattern: p, options: .caseInsensitive),
                  let m = re.firstMatch(in: raw, range: NSRange(raw.startIndex..., in: raw)),
                  let r = Range(m.range(at: 1), in: raw) else { continue }
            var v = String(raw[r]).trimmingCharacters(in: .whitespaces)
            v = v.removingPercentEncoding ?? v
            for ext in [".txt", ".yaml", ".json"] where v.hasSuffix(ext) { v.removeLast(ext.count) }
            return v
        }
        return ""
    }
}

/// Синхронизация подписки «как сказал хозяин подписки» — порт `SubscriptionSync`:
/// пропавшие серверы удаляются, новые добавляются, оставшиеся обновляются с сохранением id и пинга.
/// Совпадение — по устойчивому ключу (протокол + адрес + порт + учётные данные), не по имени.
public enum SubscriptionSync {
    public struct Plan: Sendable {
        public var toInsert: [ServerProfile]
        public var toUpdate: [ServerProfile]
        public var toDelete: [ServerProfile]
    }

    public static func key(_ p: ServerProfile) -> String {
        [p.protocolId, p.address.lowercased(), String(p.port), p.uuidOrPassword].joined(separator: "|")
    }

    public static func plan(subId: Int64, existing: [ServerProfile], incoming: [ServerProfile]) -> Plan {
        var pool: [String: [ServerProfile]] = Dictionary(grouping: existing, by: key)
        var insert: [ServerProfile] = [], update: [ServerProfile] = []
        for fresh in incoming {
            let k = key(fresh)
            if var bucket = pool[k], !bucket.isEmpty {
                let old = bucket.removeFirst()
                pool[k] = bucket
                var next = fresh
                next.id = old.id; next.subscriptionId = subId; next.pingMs = old.pingMs; next.flag = old.flag
                if next != old { update.append(next) }
            } else {
                var n = fresh
                n.id = 0; n.subscriptionId = subId
                insert.append(n)
            }
        }
        return Plan(toInsert: insert, toUpdate: update, toDelete: pool.values.flatMap { $0 })
    }

    /// Применить план к состоянию. Пустой ответ не применяется (страховка из 0.6.15).
    public static func apply(_ plan: Plan, to state: inout HydraState) {
        let deleteIds = Set(plan.toDelete.map(\.id))
        state.servers.removeAll { deleteIds.contains($0.id) }
        for u in plan.toUpdate {
            if let i = state.servers.firstIndex(where: { $0.id == u.id }) { state.servers[i] = u }
        }
        for var n in plan.toInsert {
            n.id = state.nextServerId()
            state.servers.append(n)
        }
    }
}

/// «Умный» импорт — порт `ImportDetector`: клиент сам решает, что ему дали.
public enum ImportDetector {
    public enum Result: Equatable {
        case subscriptionURL(url: String, nameHint: String)
        case servers([ServerProfile])
        case unsupportedJSON
        case unknown
        case empty
    }

    static let wrapperSchemes: Set<String> = [
        "sing-box", "clash", "clashmeta", "hiddify", "v2rayng", "v2raytun", "happ", "flclash", "olcbox",
    ]

    public static func classify(_ raw: String) -> Result {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        if text.isEmpty { return .empty }
        if let url = unwrapDeepLink(text) { return .subscriptionURL(url: url, nameHint: nameFrom(text)) }
        if (text.hasPrefix("http://") || text.hasPrefix("https://")) && !text.contains(where: \.isWhitespace) {
            return .subscriptionURL(url: text.components(separatedBy: "#")[0], nameHint: nameFrom(text))
        }
        if text.hasPrefix("{") || text.hasPrefix("[") && !text.hasPrefix("[Interface]") { return .unsupportedJSON }
        let profiles = text.contains("[Interface]")
            ? [LinkParser.parseLine(text)].compactMap { $0 }
            : LinkParser.parseSubscription(text)
        return profiles.isEmpty ? .unknown : .servers(profiles)
    }

    static func unwrapDeepLink(_ text: String) -> String? {
        guard let sep = text.range(of: "://") else { return nil }
        let scheme = text[..<sep.lowerBound].lowercased()
        guard wrapperSchemes.contains(scheme) else { return nil }
        let body = String(text[sep.upperBound...]).components(separatedBy: "#")[0]
        if let q = body.firstIndex(of: "?") {
            for kv in body[body.index(after: q)...].split(separator: "&") where kv.lowercased().hasPrefix("url=") {
                let v = String(kv.dropFirst(4)).removingPercentEncoding ?? String(kv.dropFirst(4))
                if v.hasPrefix("http://") || v.hasPrefix("https://") { return v }
            }
        }
        if let r = body.range(of: #"^(?:import/)?(https?://.+)$"#, options: [.regularExpression, .caseInsensitive]) {
            var s = String(body[r])
            if s.lowercased().hasPrefix("import/") { s.removeFirst(7) }
            return s.removingPercentEncoding ?? s
        }
        return nil
    }

    static func nameFrom(_ text: String) -> String {
        if let h = text.firstIndex(of: "#") {
            let frag = String(text[text.index(after: h)...])
            if !frag.trimmingCharacters(in: .whitespaces).isEmpty {
                return (frag.removingPercentEncoding ?? frag).trimmingCharacters(in: .whitespaces)
            }
        }
        let url = unwrapDeepLink(text) ?? text.components(separatedBy: "#")[0]
        let afterScheme = url.components(separatedBy: "://").last ?? url
        let host = afterScheme.split(whereSeparator: { "/:?".contains($0) }).first.map(String.init) ?? ""
        return host.isEmpty ? "Subscription" : host
    }
}
