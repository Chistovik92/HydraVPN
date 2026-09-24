import Foundation

/// Разбор конфигов WireGuard / AmneziaWG — порт `WireGuardParser` с Android: `.conf`
/// (секции [Interface]/[Peer]) и ссылки `wireguard://` / `awg://` с конфигом в base64.
/// Поля складываются в `extra` под теми же ключами, что на Android.
public enum WireGuardParser {

    /// Ключи обфускации AmneziaWG всех поколений 1.0 – 3.x (строчными, как в `AwgParams.ALL`).
    public static let awgKeys = [
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5",
        "headerprotectionkey", "contentpaddingaddition", "rekeyaftertime", "rekeytimeout", "rejectaftertime",
        "keepalivetimeout", "maxhandshakeattempts", "randomtrailers", "disablecookies",
    ]
    private static let v3Keys: Set<String> = [
        "headerprotectionkey", "contentpaddingaddition", "rekeyaftertime", "rekeytimeout", "rejectaftertime",
        "keepalivetimeout", "maxhandshakeattempts", "randomtrailers", "disablecookies",
    ]

    public static func toProfile(_ text: String, fallbackName: String, isAmnezia: Bool) -> ServerProfile? {
        guard let conf = unwrap(text), let parsed = parseConf(conf) else { return nil }
        let (host, port, publicKey, extra) = parsed
        var e = extra
        e["public_key"] = publicKey
        return ServerProfile(
            name: fallbackName,
            protocolId: (isAmnezia ? ServerProtocol.amneziaWG : .wireguard).rawValue,
            address: host, port: port,
            uuidOrPassword: (extra["private_key"] as? String) ?? "",
            extra: LinkParser.json(e))
    }

    static func unwrap(_ text: String) -> String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        for prefix in ["wireguard://", "wg://", "awg://", "amnezia://"] where t.lowercased().hasPrefix(prefix) {
            let body = String(t.dropFirst(prefix.count)).components(separatedBy: "#")[0]
            if let d = LinkParser.base64Decode(body), let s = String(data: d, encoding: .utf8), s.contains("[") { return s }
            return body.removingPercentEncoding
        }
        return t.contains("[Interface]") ? t : nil
    }

    // swiftlint:disable:next large_tuple
    static func parseConf(_ conf: String) -> (String, Int, String, [String: Any])? {
        guard conf.contains("[Interface]") || conf.contains("[Peer]") else { return nil }
        var iface: [String: String] = [:], peer: [String: String] = [:], section = ""
        for raw in conf.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.caseInsensitiveCompare("[Interface]") == .orderedSame { section = "iface"; continue }
            if line.caseInsensitiveCompare("[Peer]") == .orderedSame { section = "peer"; continue }
            if line.isEmpty || line.hasPrefix("#") || section.isEmpty { continue }
            guard let eq = line.firstIndex(of: "=") else { continue }
            let k = line[..<eq].trimmingCharacters(in: .whitespaces).lowercased()
            let v = line[line.index(after: eq)...].trimmingCharacters(in: .whitespaces)
            if section == "iface" { iface[k] = v } else if peer[k] == nil { peer[k] = v }
        }
        guard let pub = peer["publickey"] else { return nil }
        let endpoint = peer["endpoint"] ?? ""
        var host = endpoint, port = 51820
        if let colon = endpoint.lastIndex(of: ":") {
            host = String(endpoint[..<colon])
            port = Int(endpoint[endpoint.index(after: colon)...]) ?? 51820
        }
        host = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))

        var extra: [String: Any] = [:]
        if let v = iface["privatekey"] { extra["private_key"] = v }
        if let v = iface["address"] { extra["local_address"] = v }
        if let v = iface["dns"] { extra["dns"] = v }
        if let v = iface["mtu"] { extra["mtu"] = v }
        if let v = peer["presharedkey"] { extra["preshared_key"] = v }
        if let v = peer["allowedips"] { extra["allowed_ips"] = v }
        if let v = peer["persistentkeepalive"] { extra["keepalive"] = v }
        var found: [String: String] = [:]
        for k in awgKeys { if let v = iface[k] { found[k] = v; extra[k] = v } }
        extra["awg_version"] = version(found)
        return (host, port, pub, extra)
    }

    static func version(_ values: [String: String]) -> String {
        if values.isEmpty { return "plain" }
        if values.keys.contains(where: v3Keys.contains) { return "3.x" }
        if values.keys.contains(where: { ["s3", "s4"].contains($0) }) ||
            values.values.contains(where: { $0.contains("-") }) { return "2.0" }
        if values.keys.contains(where: { $0.hasPrefix("i") }) { return "1.5" }
        return "1.0"
    }
}
