import Foundation

/// Резервная копия в формате Android-версии (`hydra-backup`, v1): серверы и подписки
/// переносятся между платформами в обе стороны. Настройки Android хранятся по DataStore-ключам —
/// iOS читает те, что имеют смысл здесь (маршрутизация, Kill Switch, «скрывать ключи»),
/// остальные пропускает; при экспорте пишет те же ключи, чтобы копия открылась на Android.
public enum BackupCodec {
    public static let format = "hydra-backup"
    public static let version = 1

    public enum BackupError: Error, LocalizedError, Equatable {
        case notBackup, newerVersion, corrupted(String)
        public var errorDescription: String? {
            switch self {
            case .notBackup: "Файл не является резервной копией Hydra"
            case .newerVersion: "Копия сделана более новой версией Hydra — обновите приложение"
            case .corrupted(let m): "Резервная копия повреждена: \(m)"
            }
        }
    }

    public static func export(_ s: HydraState, appVersion: String, now: Date = Date()) throws -> Data {
        let r = s.routing
        let routing: [String: Any] = [
            "dns_provider": tv(r.dnsProvider.rawValue), "dns_custom_address": tv(r.dnsCustomAddress),
            "geo_routing_mode": tv(r.geoMode.rawValue), "geo_countries": tv(r.geoCountries.sorted().joined(separator: ",")),
            "tun_mtu": tv(r.mtu.rawValue), "tls_fragment": tv(r.tlsFragment.rawValue), "ipv6_mode": tv(r.ipv6.rawValue),
        ]
        let rules = r.netRules.map { ["type": $0.type.rawValue, "value": $0.value] }
        let rulesJSON = String(data: (try? JSONSerialization.data(withJSONObject: rules)) ?? Data("[]".utf8), encoding: .utf8) ?? "[]"
        let settings: [String: Any] = ["split_net_mode": tv(r.netMode.rawValue), "split_net_rules": tv(rulesJSON)]
        var vpn: [String: Any] = [
            "kill_switch": tb(s.app.killSwitch), "hide_secrets": tb(s.app.hideSecrets), "app_lock": tb(s.app.appLock),
        ]
        if let id = s.app.lastServerId { vpn["last_server_id"] = ["t": "l", "v": id] }

        let root: [String: Any] = [
            "format": format, "version": version, "appVersion": appVersion,
            "createdAt": Int64(now.timeIntervalSince1970 * 1000),
            "prefs": ["routing_settings": routing, "settings": settings, "vpn_settings": vpn],
            "servers": s.servers.map(encodeServer),
            "subscriptions": s.subscriptions.map(encodeSubscription),
        ]
        return try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
    }

    /// Разобрать копию и вернуть новое состояние (текущее — как база для того, чего в копии нет).
    public static func `import`(_ data: Data, into base: HydraState) throws -> HydraState {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw BackupError.notBackup }
        guard root["format"] as? String == format else { throw BackupError.notBackup }
        if let v = root["version"] as? Int, v > version { throw BackupError.newerVersion }

        var s = base
        guard let servers = root["servers"] as? [[String: Any]] else { throw BackupError.corrupted("нет списка серверов") }
        s.servers = try servers.map(decodeServer)
        s.subscriptions = ((root["subscriptions"] as? [[String: Any]]) ?? []).compactMap(decodeSubscription)

        let prefs = root["prefs"] as? [String: [String: Any]] ?? [:]
        func v(_ store: String, _ key: String) -> Any? { (prefs[store]?[key] as? [String: Any])?["v"] }
        let rp = "routing_settings"
        if let x = v(rp, "dns_provider") as? String, let e = DnsProvider(rawValue: x) { s.routing.dnsProvider = e }
        if let x = v(rp, "dns_custom_address") as? String { s.routing.dnsCustomAddress = x }
        if let x = v(rp, "geo_routing_mode") as? String {
            // старые значения Android до выбора стран
            s.routing.geoMode = GeoRoutingMode(rawValue: x) ?? (x == "RU_DIRECT" ? .direct : x == "RU_VIA_PROXY" ? .viaProxy : .off)
        }
        if let x = v(rp, "geo_countries") as? String { s.routing.geoCountries = x.split(separator: ",").map(String.init) }
        if let x = v(rp, "tun_mtu") as? String, let e = MtuPreset(rawValue: x) { s.routing.mtu = e }
        if let x = v(rp, "tls_fragment") as? String, let e = TlsFragmentMode(rawValue: x) { s.routing.tlsFragment = e }
        if let x = v(rp, "ipv6_mode") as? String, let e = Ipv6Mode(rawValue: x) { s.routing.ipv6 = e }
        if let x = v("settings", "split_net_mode") as? String, let e = SplitMode(rawValue: x) { s.routing.netMode = e }
        if let x = v("settings", "split_net_rules") as? String, let d = x.data(using: .utf8),
           let arr = try? JSONSerialization.jsonObject(with: d) as? [[String: String]] {
            s.routing.netRules = arr.compactMap { o in
                guard let t = o["type"].flatMap(NetRuleType.init(rawValue:)), let val = o["value"] else { return nil }
                return NetworkRule(type: t, value: val)
            }
        }
        if let x = v("vpn_settings", "kill_switch") as? Bool { s.app.killSwitch = x }
        if let x = v("vpn_settings", "hide_secrets") as? Bool { s.app.hideSecrets = x }
        if let x = v("vpn_settings", "last_server_id") as? NSNumber { s.app.lastServerId = x.int64Value }
        return s
    }

    private static func tv(_ s: String) -> [String: Any] { ["t": "s", "v": s] }
    private static func tb(_ b: Bool) -> [String: Any] { ["t": "b", "v": b] }

    static func encodeServer(_ p: ServerProfile) -> [String: Any] {
        [
            "id": p.id, "name": p.name, "protocolId": p.protocolId, "address": p.address, "port": p.port,
            "uuidOrPassword": p.uuidOrPassword, "flow": p.flow, "sni": p.sni, "transport": p.transport,
            "transportPath": p.transportPath, "security": p.security, "alpn": p.alpn, "fingerprint": p.fingerprint,
            "extra": p.extra, "subscriptionId": p.subscriptionId.map { $0 as Any } ?? NSNull(),
            "pingMs": p.pingMs, "flag": p.flag,
        ]
    }

    static func decodeServer(_ o: [String: Any]) throws -> ServerProfile {
        guard let name = o["name"] as? String, let proto = o["protocolId"] as? String,
              let address = o["address"] as? String, let port = (o["port"] as? NSNumber)?.intValue else {
            throw BackupError.corrupted("сервер без имени, протокола или адреса")
        }
        let d = ServerProfile(name: "", protocolId: "", address: "", port: 0)
        return ServerProfile(
            id: (o["id"] as? NSNumber)?.int64Value ?? 0, name: name, protocolId: proto, address: address, port: port,
            uuidOrPassword: o["uuidOrPassword"] as? String ?? d.uuidOrPassword, flow: o["flow"] as? String ?? d.flow,
            sni: o["sni"] as? String ?? d.sni, transport: o["transport"] as? String ?? d.transport,
            transportPath: o["transportPath"] as? String ?? d.transportPath, security: o["security"] as? String ?? d.security,
            alpn: o["alpn"] as? String ?? d.alpn, fingerprint: o["fingerprint"] as? String ?? d.fingerprint,
            extra: o["extra"] as? String ?? d.extra, subscriptionId: (o["subscriptionId"] as? NSNumber)?.int64Value,
            pingMs: (o["pingMs"] as? NSNumber)?.intValue ?? d.pingMs, flag: o["flag"] as? String ?? d.flag)
    }

    static func encodeSubscription(_ s: Subscription) -> [String: Any] {
        [
            "id": s.id, "name": s.name, "url": s.url, "userAgent": s.userAgent, "lastUpdated": s.lastUpdated,
            "autoUpdateHours": s.autoUpdateHours, "serverTitle": s.serverTitle, "autoUpdate": s.autoUpdate,
            "collapsed": s.collapsed,
        ]
    }

    static func decodeSubscription(_ o: [String: Any]) -> Subscription? {
        guard let name = o["name"] as? String, let url = o["url"] as? String else { return nil }
        return Subscription(
            id: (o["id"] as? NSNumber)?.int64Value ?? 0, name: name, url: url,
            userAgent: o["userAgent"] as? String ?? "", lastUpdated: (o["lastUpdated"] as? NSNumber)?.int64Value ?? 0,
            autoUpdateHours: (o["autoUpdateHours"] as? NSNumber)?.intValue ?? 12,
            serverTitle: o["serverTitle"] as? String ?? "", autoUpdate: o["autoUpdate"] as? Bool ?? true,
            collapsed: o["collapsed"] as? Bool ?? false)
    }
}
