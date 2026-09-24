import Foundation

/// Парсер ссылок-конфигов — порт `LinkParser` с Android. Форматы — де-факто стандарт
/// экосистемы v2ray/Xray/sing-box (x-ui / 3x-ui / PasarGuard / Remnawave).
public enum LinkParser {

    /// Содержимое подписки: plain-список ссылок или base64-блоб.
    public static func parseSubscription(_ raw: String, subscriptionId: Int64? = nil) -> [ServerProfile] {
        let text = maybeBase64Decode(raw.trimmingCharacters(in: .whitespacesAndNewlines))
        return text.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && $0.contains("://") }
            .compactMap { parseLine($0) }
            .map { var p = $0; p.subscriptionId = subscriptionId; return p }
    }

    public static func parseLine(_ link: String) -> ServerProfile? {
        let scheme = link.components(separatedBy: "://").first?.lowercased() ?? ""
        switch scheme {
        case "vless": return parseVless(link)
        case "trojan": return parseTrojan(link)
        case "vmess": return parseVmess(link)
        case "ss": return parseShadowsocks(link)
        case "hysteria2", "hy2": return parseHysteria2(link)
        case "tuic": return parseTuic(link)
        case "wireguard", "wg": return WireGuardParser.toProfile(link, fallbackName: "WireGuard", isAmnezia: false)
        case "awg", "amnezia": return WireGuardParser.toProfile(link, fallbackName: "AmneziaWG", isAmnezia: true)
        case "sstp": return parseUserPass(link, .sstp, 443)
        case "l2tp": return parseUserPass(link, .l2tp, 1701)
        case "pptp": return parseUserPass(link, .pptp, 1723)
        default:
            // вставленный целиком .conf: AWG-параметры → AmneziaWG, иначе WireGuard
            guard link.contains("[Interface]") else { return nil }
            let awg = link.range(of: #"(?im)^\s*(Jc|Jmin|Jmax|S1|S2|S3|S4|H[1-4]|I[1-5])\s*="#, options: .regularExpression) != nil
            return WireGuardParser.toProfile(link, fallbackName: awg ? "AmneziaWG" : "WireGuard", isAmnezia: awg)
        }
    }

    // MARK: - протоколы

    private static func parseUserPass(_ link: String, _ proto: ServerProtocol, _ defaultPort: Int) -> ServerProfile? {
        guard let u = URLParts(link) else { return nil }
        var extra: [String: Any] = ["username": u.user]
        if let v = u.query["allow_insecure"] { extra["allow_insecure"] = (v == "1" || v == "true") }
        if let v = u.query["tunnel_secret"] { extra["tunnel_secret"] = v }
        return ServerProfile(
            name: u.fragment ?? "\(proto.displayName) \(u.host)", protocolId: proto.rawValue,
            address: u.host, port: u.port ?? defaultPort, uuidOrPassword: u.password,
            sni: u.query["sni"] ?? "", extra: json(extra))
    }

    // vless://uuid@host:port?type=ws&security=reality&pbk=...&sid=...&sni=...&flow=...#name
    private static func parseVless(_ link: String) -> ServerProfile? {
        guard let u = URLParts(link) else { return nil }
        var extra: [String: Any] = [:]
        if let v = u.query["pbk"] { extra["reality_pbk"] = v }
        if let v = u.query["sid"] { extra["reality_sid"] = v }
        return ServerProfile(
            name: u.fragment ?? "VLESS \(u.host)", protocolId: ServerProtocol.vless.rawValue,
            address: u.host, port: u.port ?? 443, uuidOrPassword: urlDecode(u.userInfo),
            flow: u.query["flow"] ?? "", sni: u.query["sni"] ?? u.query["host"] ?? "",
            transport: u.query["type"] ?? "tcp", transportPath: u.query["path"] ?? u.query["serviceName"] ?? "",
            security: u.query["security"] ?? "none", alpn: u.query["alpn"] ?? "",
            fingerprint: u.query["fp"] ?? "chrome", extra: json(extra))
    }

    // trojan://password@host:port?sni=...&type=...#name
    private static func parseTrojan(_ link: String) -> ServerProfile? {
        guard let u = URLParts(link) else { return nil }
        return ServerProfile(
            name: u.fragment ?? "Trojan \(u.host)", protocolId: ServerProtocol.trojan.rawValue,
            address: u.host, port: u.port ?? 443, uuidOrPassword: urlDecode(u.userInfo),
            sni: u.query["sni"] ?? u.query["peer"] ?? "", transport: u.query["type"] ?? "tcp",
            transportPath: u.query["path"] ?? u.query["serviceName"] ?? "",
            security: u.query["security"] ?? "tls", alpn: u.query["alpn"] ?? "")
    }

    // vmess://<base64 json>
    private static func parseVmess(_ link: String) -> ServerProfile? {
        let body = String(link.dropFirst("vmess://".count))
        guard let data = base64Decode(body),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        func str(_ k: String) -> String {
            if let s = o[k] as? String { return s }
            if let n = o[k] as? NSNumber { return n.stringValue }
            return ""
        }
        let ps = str("ps")
        return ServerProfile(
            name: ps.isEmpty ? "VMess \(str("add"))" : ps, protocolId: ServerProtocol.vmess.rawValue,
            address: str("add"), port: Int(str("port")) ?? 443, uuidOrPassword: str("id"),
            sni: str("sni").isEmpty ? str("host") : str("sni"),
            transport: str("net").isEmpty ? "tcp" : str("net"), transportPath: str("path"),
            security: str("tls") == "tls" ? "tls" : "none", alpn: str("alpn"),
            extra: json(["aid": Int(str("aid")) ?? 0]))
    }

    // ss://base64(method:password)@host:port#name  ИЛИ  ss://base64(method:password@host:port)#name
    private static func parseShadowsocks(_ link: String) -> ServerProfile? {
        let body = String(link.dropFirst("ss://".count))
        let hashParts = body.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)
        let name = hashParts.count > 1 ? urlDecode(String(hashParts[1])) : ""
        var core = String(hashParts[0])
        if let q = core.firstIndex(of: "?") { core = String(core[..<q]) }   // plugin-параметры не поддерживаем
        if core.hasSuffix("/") { core.removeLast() }

        let method: String, password: String, hostPort: String
        if let at = core.lastIndex(of: "@") {
            let credsRaw = String(core[..<at])
            // SIP002: userinfo в base64url либо в открытом виде method:password (percent-encoded)
            let creds = base64Decode(credsRaw).flatMap { String(data: $0, encoding: .utf8) } ?? urlDecode(credsRaw)
            guard let c = creds.firstIndex(of: ":") else { return nil }
            method = String(creds[..<c]); password = String(creds[creds.index(after: c)...])
            hostPort = String(core[core.index(after: at)...])
        } else {
            guard let data = base64Decode(core), let decoded = String(data: data, encoding: .utf8),
                  let c = decoded.firstIndex(of: ":"), let at = decoded.lastIndex(of: "@") else { return nil }
            method = String(decoded[..<c]); password = String(decoded[decoded.index(after: c)..<at])
            hostPort = String(decoded[decoded.index(after: at)...])
        }
        guard let colon = hostPort.lastIndex(of: ":") else { return nil }
        let host = String(hostPort[..<colon]).trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        let port = Int(hostPort[hostPort.index(after: colon)...]) ?? 8388
        return ServerProfile(
            name: name.isEmpty ? "SS \(host)" : name, protocolId: ServerProtocol.shadowsocks.rawValue,
            address: host, port: port, uuidOrPassword: password, extra: json(["method": method]))
    }

    // hysteria2://password@host:port?sni=...&obfs=...#name
    private static func parseHysteria2(_ link: String) -> ServerProfile? {
        let normalized = link.lowercased().hasPrefix("hy2://") ? "hysteria2://" + link.dropFirst("hy2://".count) : link
        guard let u = URLParts(normalized) else { return nil }
        var extra: [String: Any] = [:]
        if let v = u.query["obfs"] { extra["obfs"] = v }
        if let v = u.query["obfs-password"] { extra["obfs_password"] = v }
        return ServerProfile(
            name: u.fragment ?? "Hysteria2 \(u.host)", protocolId: ServerProtocol.hysteria2.rawValue,
            address: u.host, port: u.port ?? 443, uuidOrPassword: urlDecode(u.userInfo),
            sni: u.query["sni"] ?? "", security: "tls", extra: json(extra))
    }

    // tuic://uuid:password@host:port?sni=...&congestion_control=bbr#name
    private static func parseTuic(_ link: String) -> ServerProfile? {
        guard let u = URLParts(link) else { return nil }
        return ServerProfile(
            name: u.fragment ?? "TUIC \(u.host)", protocolId: ServerProtocol.tuic.rawValue,
            address: u.host, port: u.port ?? 443, uuidOrPassword: u.user,
            sni: u.query["sni"] ?? "", security: "tls", alpn: u.query["alpn"] ?? "h3",
            extra: json(["password": u.password, "congestion_control": u.query["congestion_control"] ?? "bbr"]))
    }

    // MARK: - helpers

    static func json(_ o: [String: Any]) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]),
              let s = String(data: d, encoding: .utf8) else { return "{}" }
        return s
    }

    static func urlDecode(_ s: String) -> String { s.removingPercentEncoding ?? s }

    /// base64 / base64url с добитым паддингом; nil, если это не base64.
    public static func base64Decode(_ s: String) -> Data? {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            .replacingOccurrences(of: "\n", with: "").replacingOccurrences(of: "\r", with: "")
        let m = t.count % 4
        if m != 0 { t += String(repeating: "=", count: 4 - m) }
        return Data(base64Encoded: t)
    }

    static func maybeBase64Decode(_ s: String) -> String {
        if s.contains("://") { return s }
        guard let d = base64Decode(s), let t = String(data: d, encoding: .utf8) else { return s }
        return t
    }
}

/// Разбор `scheme://userinfo@host:port?query#fragment` без URLComponents: те пропускают не
/// все символы, которые панели кладут в userinfo и фрагмент (эмодзи-флаги, пробелы, `|`).
struct URLParts {
    let userInfo: String
    let host: String
    let port: Int?
    let query: [String: String]
    let fragment: String?

    /// userinfo до первого «:» (логин / uuid) и после (пароль).
    var user: String { LinkParser.urlDecode(userInfo.components(separatedBy: ":").first ?? "") }
    var password: String {
        guard let c = userInfo.firstIndex(of: ":") else { return "" }
        return LinkParser.urlDecode(String(userInfo[userInfo.index(after: c)...]))
    }

    init?(_ link: String) {
        guard let schemeEnd = link.range(of: "://") else { return nil }
        var rest = String(link[schemeEnd.upperBound...])
        var fragment: String?
        if let h = rest.firstIndex(of: "#") {
            let f = LinkParser.urlDecode(String(rest[rest.index(after: h)...]))
            fragment = f.trimmingCharacters(in: .whitespaces).isEmpty ? nil : f
            rest = String(rest[..<h])
        }
        var queryString = ""
        if let q = rest.firstIndex(of: "?") {
            queryString = String(rest[rest.index(after: q)...])
            rest = String(rest[..<q])
        }
        if rest.hasSuffix("/") { rest.removeLast() }
        if let slash = rest.firstIndex(of: "/") { rest = String(rest[..<slash]) }
        var userInfo = ""
        if let at = rest.lastIndex(of: "@") {
            userInfo = String(rest[..<at])
            rest = String(rest[rest.index(after: at)...])
        }
        var host = rest, port: Int?
        if rest.hasPrefix("["), let close = rest.firstIndex(of: "]") {
            host = String(rest[rest.index(after: rest.startIndex)..<close])
            let after = rest[rest.index(after: close)...]
            if after.hasPrefix(":") { port = Int(after.dropFirst()) }
        } else if let colon = rest.lastIndex(of: ":") {
            host = String(rest[..<colon])
            port = Int(rest[rest.index(after: colon)...])
        }
        var query: [String: String] = [:]
        for pair in queryString.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let k = LinkParser.urlDecode(String(kv[0]))
            let v = kv.count > 1 ? LinkParser.urlDecode(String(kv[1]).replacingOccurrences(of: "+", with: " ")) : ""
            if !k.isEmpty && query[k] == nil { query[k] = v }
        }
        guard !host.isEmpty else { return nil }
        self.userInfo = userInfo; self.host = host; self.port = (port ?? 0) > 0 ? port : nil
        self.query = query; self.fragment = fragment
    }
}
