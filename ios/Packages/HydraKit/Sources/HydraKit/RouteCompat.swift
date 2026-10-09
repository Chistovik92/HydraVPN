import Foundation

/// JSON маршрутов и geo-настроек в формате Android (`RouteConfig.toJson`, `GeoSettings.toJson`): так резервная копия
/// переносится между платформами в обе стороны (ключи `route_config` и `geo_config` в `routing_settings`).
extension RouteConfig {
    public func androidJSON() -> String {
        let rules: [[String: Any]] = self.rules.map {
            var o: [String: Any] = ["kind": $0.kind.rawValue == "protocol" ? "protocol" : androidKind($0.kind), "value": $0.value, "target": $0.target]
            if $0.invert { o["invert"] = true }
            if !$0.group.isEmpty { o["group"] = $0.group }
            return o
        }
        let groups: [[String: Any]] = self.groups.map {
            ["name": $0.name, "type": $0.type, "members": $0.members, "url": $0.url, "interval": $0.intervalSec]
        }
        let dpi: [String: Any] = ["enabled": self.dpi.enabled, "strategy": self.dpi.strategy, "port": self.dpi.port, "direct_via_dpi": self.dpi.directViaDpi]
        let root: [String: Any] = ["rules": rules, "groups": groups, "dpi": dpi]
        return (try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    public init(androidJSON json: String) {
        self.init()
        guard let d = json.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return }
        rules = (o["rules"] as? [[String: Any]] ?? []).compactMap { r in
            guard let k = (r["kind"] as? String).flatMap(RouteConfig.kind(fromAndroid:)) else { return nil }
            return RouteRule(kind: k, value: r["value"] as? String ?? "", target: r["target"] as? String ?? RouteTarget.proxy,
                             invert: r["invert"] as? Bool ?? false, group: r["group"] as? String ?? "")
        }
        groups = (o["groups"] as? [[String: Any]] ?? []).compactMap { g in
            guard let name = g["name"] as? String, !name.isEmpty else { return nil }
            return RouteGroup(name: name, type: g["type"] as? String ?? RouteGroup.urltest, members: g["members"] as? [String] ?? [],
                              url: g["url"] as? String ?? "https://www.gstatic.com/generate_204", intervalSec: (g["interval"] as? NSNumber)?.intValue ?? 300)
        }
        if let x = o["dpi"] as? [String: Any] {
            let port = (x["port"] as? NSNumber)?.intValue ?? DpiSettings.defaultPort
            dpi = DpiSettings(enabled: x["enabled"] as? Bool ?? false,
                              strategy: (x["strategy"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? DpiStrategies.defaultStrategy,
                              port: (1024...65535).contains(port) ? port : DpiSettings.defaultPort,
                              directViaDpi: x["direct_via_dpi"] as? Bool ?? true)
        }
    }

    // В Android `RouteKind.id`: app, process, domain, suffix, keyword, regex, cidr, src_cidr, port, protocol, network, geoip, geosite.
    private func androidKind(_ k: RouteKind) -> String { k.rawValue }
    private static func kind(fromAndroid s: String) -> RouteKind? { RouteKind(rawValue: s) }
}

extension GeoSettings {
    public func androidJSON() -> String {
        let root: [String: Any] = [
            "auto": autoUpdate, "hours": intervalHours, "ip_source": ipSource, "site_source": siteSource,
            "extra_ip": extraIp, "extra_site": extraSite,
            "custom": custom.map { ["kind": $0.kind.rawValue, "name": $0.name, "url": $0.url, "type": $0.type] },
        ]
        return (try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    public init(androidJSON json: String) {
        self.init()
        guard let d = json.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return }
        autoUpdate = o["auto"] as? Bool ?? true
        intervalHours = min(max((o["hours"] as? NSNumber)?.intValue ?? 24, 1), 24 * 30)
        ipSource = o["ip_source"] as? String ?? GeoSources.metacubex.id
        siteSource = o["site_source"] as? String ?? GeoSources.metacubex.id
        extraIp = o["extra_ip"] as? [String] ?? []
        extraSite = o["extra_site"] as? [String] ?? []
        custom = (o["custom"] as? [[String: Any]] ?? []).compactMap { c in
            guard let k = (c["kind"] as? String).flatMap(GeoKind.init(rawValue:)), let n = c["name"] as? String, !n.isEmpty,
                  let u = c["url"] as? String, u.hasPrefix("http") else { return nil }
            return CustomGeoSource(kind: k, name: n, url: u, type: c["type"] as? String ?? CustomGeoSource.typeSrs)
        }
    }
}
