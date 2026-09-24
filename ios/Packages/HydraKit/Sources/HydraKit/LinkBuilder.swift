import Foundation

/// Профиль → ссылка для обмена — порт `LinkBuilder` с Android (обратное к LinkParser).
/// nil — у протокола нет общепринятого формата ссылки.
public enum LinkBuilder {
    public static func link(_ p: ServerProfile) -> String? {
        guard let proto = p.serverProtocol else { return nil }
        let e = p.extraObject
        func s(_ k: String) -> String {
            if let v = e[k] as? String { return v }
            if let n = e[k] as? NSNumber { return n.stringValue }
            return ""
        }
        let name = enc(p.name)
        switch proto {
        case .vless:
            var q: [(String, String)] = [("encryption", "none"), ("type", p.transport), ("security", p.security)]
            if !p.flow.isEmpty { q.append(("flow", p.flow)) }
            if !p.sni.isEmpty { q.append(("sni", p.sni)) }
            if !p.alpn.isEmpty { q.append(("alpn", p.alpn)) }
            if !p.fingerprint.isEmpty && p.security != "none" { q.append(("fp", p.fingerprint)) }
            if let tp = transportPath(p) { q.append(tp) }
            if !s("reality_pbk").isEmpty { q.append(("pbk", s("reality_pbk"))) }
            if !s("reality_sid").isEmpty { q.append(("sid", s("reality_sid"))) }
            return "vless://\(p.uuidOrPassword)@\(host(p.address)):\(p.port)?\(query(q))#\(name)"
        case .trojan:
            var q: [(String, String)] = [("security", p.security)]
            if !p.sni.isEmpty { q.append(("sni", p.sni)) }
            q.append(("type", p.transport))
            if !p.alpn.isEmpty { q.append(("alpn", p.alpn)) }
            if let tp = transportPath(p) { q.append(tp) }
            return "trojan://\(enc(p.uuidOrPassword))@\(host(p.address)):\(p.port)?\(query(q))#\(name)"
        case .vmess:
            let o: [String: String] = [
                "v": "2", "ps": p.name, "add": p.address, "port": String(p.port), "id": p.uuidOrPassword,
                "aid": s("aid").isEmpty ? "0" : s("aid"), "scy": "auto", "net": p.transport, "type": "none",
                "host": p.sni, "path": p.transportPath, "tls": p.security == "tls" ? "tls" : "", "sni": p.sni, "alpn": p.alpn,
            ]
            guard let d = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]) else { return nil }
            return "vmess://" + d.base64EncodedString()
        case .shadowsocks:
            let method = s("method").isEmpty ? "aes-256-gcm" : s("method")
            return "ss://\(b64url("\(method):\(p.uuidOrPassword)"))@\(host(p.address)):\(p.port)#\(name)"
        case .hysteria2:
            var q: [(String, String)] = []
            if !p.sni.isEmpty { q.append(("sni", p.sni)) }
            if !s("obfs").isEmpty { q.append(("obfs", s("obfs"))) }
            if !s("obfs_password").isEmpty { q.append(("obfs-password", s("obfs_password"))) }
            return "hysteria2://\(enc(p.uuidOrPassword))@\(host(p.address)):\(p.port)\(q.isEmpty ? "" : "?" + query(q))#\(name)"
        case .tuic:
            var q: [(String, String)] = []
            if !p.sni.isEmpty { q.append(("sni", p.sni)) }
            if !p.alpn.isEmpty { q.append(("alpn", p.alpn)) }
            if !s("congestion_control").isEmpty { q.append(("congestion_control", s("congestion_control"))) }
            return "tuic://\(p.uuidOrPassword):\(enc(s("password")))@\(host(p.address)):\(p.port)\(q.isEmpty ? "" : "?" + query(q))#\(name)"
        case .sstp, .l2tp:
            var q: [(String, String)] = []
            if !p.sni.isEmpty { q.append(("sni", p.sni)) }
            if let b = e["allow_insecure"] as? Bool { q.append(("allow_insecure", b ? "1" : "0")) }
            if !s("tunnel_secret").isEmpty { q.append(("tunnel_secret", s("tunnel_secret"))) }
            return "\(proto.rawValue)://\(enc(s("username"))):\(enc(p.uuidOrPassword))@\(host(p.address)):\(p.port)\(q.isEmpty ? "" : "?" + query(q))#\(name)"
        case .wireguard, .amneziaWG:
            return "\(proto == .wireguard ? "wireguard" : "awg")://\(b64url(wireGuardConf(p)))#\(name)"
        default:
            return nil
        }
    }

    /// .conf WireGuard/AmneziaWG из профиля (для ссылки wireguard:// и экспорта).
    public static func wireGuardConf(_ p: ServerProfile) -> String {
        let e = p.extraObject
        func s(_ k: String) -> String? {
            if let v = e[k] as? String, !v.isEmpty { return v }
            if let n = e[k] as? NSNumber { return n.stringValue }
            return nil
        }
        var lines = ["[Interface]", "PrivateKey = \(p.uuidOrPassword)"]
        if let v = s("local_address") { lines.append("Address = \(v)") }
        if let v = s("dns") { lines.append("DNS = \(v)") }
        if let v = s("mtu") { lines.append("MTU = \(v)") }
        let names = ["jc": "Jc", "jmin": "Jmin", "jmax": "Jmax", "s1": "S1", "s2": "S2", "s3": "S3", "s4": "S4",
                     "h1": "H1", "h2": "H2", "h3": "H3", "h4": "H4", "i1": "I1", "i2": "I2", "i3": "I3", "i4": "I4", "i5": "I5",
                     "headerprotectionkey": "HeaderProtectionKey", "contentpaddingaddition": "ContentPaddingAddition",
                     "rekeyaftertime": "RekeyAfterTime", "rekeytimeout": "RekeyTimeout", "rejectaftertime": "RejectAfterTime",
                     "keepalivetimeout": "KeepaliveTimeout", "maxhandshakeattempts": "MaxHandshakeAttempts",
                     "randomtrailers": "RandomTrailers", "disablecookies": "DisableCookies"]
        for k in WireGuardParser.awgKeys { if let v = s(k) { lines.append("\(names[k] ?? k) = \(v)") } }
        lines += ["", "[Peer]", "PublicKey = \(s("public_key") ?? "")"]
        if let v = s("preshared_key") { lines.append("PresharedKey = \(v)") }
        lines.append("AllowedIPs = \(s("allowed_ips") ?? "0.0.0.0/0, ::/0")")
        lines.append("Endpoint = \(host(p.address)):\(p.port)")
        if let v = s("keepalive") { lines.append("PersistentKeepalive = \(v)") }
        return lines.joined(separator: "\n") + "\n"
    }

    static func transportPath(_ p: ServerProfile) -> (String, String)? {
        p.transportPath.isEmpty ? nil : (p.transport == "grpc" ? "serviceName" : "path", p.transportPath)
    }
    static func host(_ h: String) -> String { h.contains(":") && !h.hasPrefix("[") ? "[\(h)]" : h }
    static func enc(_ s: String) -> String {
        // Только ASCII: .alphanumerics пропустил бы кириллицу и эмодзи в имени без кодирования.
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
    static func query(_ q: [(String, String)]) -> String { q.map { "\($0.0)=\(enc($0.1))" }.joined(separator: "&") }
    static func b64url(_ s: String) -> String {
        Data(s.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}
