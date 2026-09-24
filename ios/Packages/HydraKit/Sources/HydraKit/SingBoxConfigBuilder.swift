import Foundation

/// Конфиг sing-box 1.12 для iOS — порт `SingBoxConfigBuilder` с Android. Правила маршрутизации,
/// DNS, outbound-ы, фрагментация TLS — один в один. Отличие только в tun: на Android маршрутами
/// владеет VpnService.Builder (`auto_route: false`), а на iOS sing-box сам сообщает их системе через
/// `openTun` → NEPacketTunnelNetworkSettings, поэтому `auto_route: true`.
public enum SingBoxConfigBuilder {

    public struct GeoCountry: Sendable {
        public var code: String
        public var geoipPath: String
        public var geositePath: String?
        public init(code: String, geoipPath: String, geositePath: String? = nil) {
            self.code = code; self.geoipPath = geoipPath; self.geositePath = geositePath
        }
    }

    public struct Options: Sendable {
        public var routing = RoutingSettings()
        public var geo: [GeoCountry] = []
        public var hotspot: (port: Int, user: String, password: String)?
        public init() {}
    }

    public static func build(_ profile: ServerProfile, _ o: Options) throws -> [String: Any] {
        var root: [String: Any] = [:]
        root["log"] = ["level": "info", "timestamp": true]
        root["experimental"] = ["clash_api": [String: Any]()]

        let dns = o.routing.resolvedDns()
        var servers: [[String: Any]] = []
        if let dns {
            var s: [String: Any] = ["type": dns.type, "tag": "remote", "server": dns.host, "detour": "proxy"]
            if let p = dns.port { s["server_port"] = p }
            if let p = dns.path { s["path"] = p }
            if !dns.isIp { s["domain_resolver"] = "bootstrap" }
            servers.append(s)
            if !dns.isIp {
                servers.append(["type": "https", "tag": "bootstrap", "server": "1.1.1.1", "detour": "proxy"])
            }
        }
        servers.append(["type": "local", "tag": "local"])
        // IPv6 «Блокировать»: DNS отдаёт только A-записи — приложения сразу идут по IPv4.
        let v4only = o.routing.ipv6 == .block
        root["dns"] = ["servers": servers, "final": dns != nil ? "remote" : "local",
                       "strategy": v4only ? "ipv4_only" : "prefer_ipv4"]

        // IPv6-адрес у tun есть всегда: без него система не отдаёт туннелю IPv6-маршруты, и IPv6
        // шёл бы мимо VPN (а NEIPv6Settings без адресов iOS не принимает — трюк Android с «::/0 без
        // адреса» здесь не работает). В режиме «Блокировать» IPv6 отклоняется правилом ниже.
        let tun: [String: Any] = [
            "type": "tun", "tag": "tun-in", "mtu": o.routing.mtu.value,
            "address": ["172.19.0.1/28", "fdfe:dcba:9876::1/126"],
            "auto_route": true, "strict_route": false, "stack": "gvisor",
        ]
        var inbounds: [[String: Any]] = [tun]
        if let h = o.hotspot, !h.password.isEmpty {
            inbounds.append([
                "type": "mixed", "tag": "hotspot-in", "listen": "0.0.0.0", "listen_port": h.port,
                "users": [["username": h.user, "password": h.password]],
            ])
        }
        root["inbounds"] = inbounds
        root["outbounds"] = [try outbound(profile, o.routing.tlsFragment), ["type": "direct", "tag": "direct"]]

        let netRules = o.routing.netActive ? o.routing.netRules : []
        let geoActive = o.routing.geoMode != .off && !o.geo.isEmpty
        var geoTags: [(String, String)] = []
        if geoActive {
            for c in o.geo {
                geoTags.append(("geoip-\(c.code)", c.geoipPath))
                if let s = c.geositePath { geoTags.append(("geosite-\(c.code)", s)) }
            }
        }
        var route: [String: Any] = [:]
        if geoActive {
            route["rule_set"] = geoTags.map { ["type": "local", "tag": $0.0, "format": "binary", "path": $0.1] }
        }
        var rules: [[String: Any]] = [["action": "sniff"], ["protocol": "dns", "action": "hijack-dns"]]
        if v4only { rules.append(["ip_version": 6, "action": "reject"]) }
        if !netRules.isEmpty {
            let target = o.routing.netMode == .exclude ? "direct" : "proxy"
            var grouped: [(NetRuleType, [String])] = []
            for r in netRules {
                if let i = grouped.firstIndex(where: { $0.0 == r.type }) { grouped[i].1.append(r.value) }
                else { grouped.append((r.type, [r.value])) }
            }
            for (type, values) in grouped { rules.append([type.singBoxKey: values, "outbound": target]) }
        }
        rules.append(["ip_is_private": true, "outbound": "direct"])
        if geoActive {
            rules.append(["rule_set": geoTags.map(\.0), "outbound": o.routing.geoMode == .direct ? "direct" : "proxy"])
        }
        route["rules"] = rules
        let includeOnly = !netRules.isEmpty && o.routing.netMode == .include
        route["final"] = (includeOnly || (geoActive && o.routing.geoMode == .viaProxy)) ? "direct" : "proxy"
        route["auto_detect_interface"] = true
        route["default_domain_resolver"] = "local"
        root["route"] = route
        return root
    }

    public static func buildJSON(_ profile: ServerProfile, _ o: Options) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: try build(profile, o), options: [.prettyPrinted, .sortedKeys])
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    public enum BuildError: Error, LocalizedError {
        case unsupported(String)
        public var errorDescription: String? {
            switch self { case .unsupported(let p): "\(p): протокол пока не поддерживается на iOS" }
        }
    }

    static func outbound(_ p: ServerProfile, _ fragment: TlsFragmentMode) throws -> [String: Any] {
        var o: [String: Any] = ["tag": "proxy", "server": p.address, "server_port": p.port]
        let extra = p.extraObject
        func str(_ k: String) -> String? {
            if let s = extra[k] as? String { return s }
            if let n = extra[k] as? NSNumber { return n.stringValue }
            return nil
        }
        switch p.serverProtocol {
        case .vless:
            o["type"] = "vless"; o["uuid"] = p.uuidOrPassword
            if !p.flow.isEmpty { o["flow"] = p.flow }
            o["tls"] = tls(p, extra, fragment)
            if let t = transport(p) { o["transport"] = t }
        case .vmess:
            o["type"] = "vmess"; o["uuid"] = p.uuidOrPassword; o["security"] = "auto"
            o["alter_id"] = Int(str("aid") ?? "0") ?? 0
            if p.security == "tls" { o["tls"] = tls(p, extra, fragment) }
            if let t = transport(p) { o["transport"] = t }
        case .trojan:
            o["type"] = "trojan"; o["password"] = p.uuidOrPassword
            o["tls"] = tls(p, extra, fragment)
            if let t = transport(p) { o["transport"] = t }
        case .shadowsocks:
            o["type"] = "shadowsocks"; o["method"] = str("method") ?? "aes-256-gcm"; o["password"] = p.uuidOrPassword
        case .hysteria2:
            o["type"] = "hysteria2"; o["password"] = p.uuidOrPassword
            o["tls"] = tls(p, extra, .off)
            if extra["obfs"] != nil { o["obfs"] = ["type": "salamander", "password": str("obfs_password") ?? ""] }
        case .wireguard:
            o["type"] = "wireguard"; o["private_key"] = p.uuidOrPassword
            o["peer_public_key"] = str("public_key") ?? ""
            let local = (str("local_address") ?? "").isEmpty ? "172.19.0.2/32" : str("local_address")!
            o["local_address"] = local.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
            if let psk = str("preshared_key") { o["pre_shared_key"] = psk }
            if let m = str("mtu"), let mtu = Int(m) { o["mtu"] = mtu }
        case .tuic:
            o["type"] = "tuic"; o["uuid"] = p.uuidOrPassword; o["password"] = str("password") ?? ""
            o["congestion_control"] = str("congestion_control") ?? "bbr"
            o["tls"] = tls(p, extra, .off)
        default:
            throw BuildError.unsupported(p.serverProtocol?.displayName ?? p.protocolId)
        }
        return o
    }

    static func tls(_ p: ServerProfile, _ extra: [String: Any], _ fragment: TlsFragmentMode) -> [String: Any] {
        var t: [String: Any] = ["enabled": true, "server_name": p.sni.isEmpty ? p.address : p.sni]
        if !p.alpn.isEmpty { t["alpn"] = p.alpn.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) } }
        t["utls"] = ["enabled": true, "fingerprint": p.fingerprint]
        if p.security == "reality", let pbk = extra["reality_pbk"] as? String {
            t["reality"] = ["enabled": true, "public_key": pbk, "short_id": (extra["reality_sid"] as? String) ?? ""]
        }
        switch fragment {
        case .off: break
        case .record: t["record_fragment"] = true
        case .tcp: t["fragment"] = true
        }
        return t
    }

    static func transport(_ p: ServerProfile) -> [String: Any]? {
        switch p.transport {
        case "ws": return ["type": "ws", "path": p.transportPath.isEmpty ? "/" : p.transportPath]
        case "grpc": return ["type": "grpc", "service_name": p.transportPath]
        case "http": return ["type": "http", "path": p.transportPath.isEmpty ? "/" : p.transportPath]
        default: return nil
        }
    }
}
