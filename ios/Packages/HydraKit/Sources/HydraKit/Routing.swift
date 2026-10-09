import Foundation

/// Полная маршрутизация как на роутерах (0.7.13) — порт `RoutePlan`/`RoutePlanApplier`/`RouteConfig` с Android:
/// несколько выходов одновременно, цепочки любой глубины, группы выходов, условия «И»/«НЕ», geo-базы в правилах и в DNS.
/// План накладывается на готовый конфиг sing-box. На iOS правил «по приложениям» нет (система не сообщает пакет).
public enum RouteKind: String, Codable, CaseIterable, Sendable {
    case app, process, domain, suffix, keyword, regex, cidr
    case srcCidr = "src_cidr"
    case port
    case proto = "protocol"
    case network, geoip, geosite
}

/// Выходы. Остальные — узлы из списка серверов (`node-<id>`) и группы (`grp-<имя>`).
public enum RouteTarget {
    public static let proxy = "proxy"
    public static let direct = "direct"
    public static let dpi = "dpi"
    public static let block = "block"

    public static func node(_ id: Int64) -> String { "node-\(id)" }
    public static func nodeId(_ target: String) -> Int64? { target.hasPrefix("node-") ? Int64(target.dropFirst(5)) : nil }
    public static func isGroup(_ target: String) -> Bool { target.hasPrefix("grp-") }
    public static func group(_ name: String) -> String {
        let allowed = Set("abcdefghijklmnopqrstuvwxyz0123456789_-абвгдеёжзийклмнопрстуфхцчшщъыьэюя")
        var out = ""
        var lastDash = false
        for c in name.trimmingCharacters(in: .whitespaces).lowercased() {
            if allowed.contains(c) { out.append(c); lastDash = false }
            else if !lastDash { out.append("-"); lastDash = true }
        }
        return "grp-" + out.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    }
}

public struct RouteRule: Codable, Hashable, Sendable {
    public var kind: RouteKind
    public var value: String
    public var target: String
    public var invert: Bool
    public var group: String
    public init(kind: RouteKind, value: String, target: String, invert: Bool = false, group: String = "") {
        self.kind = kind; self.value = value; self.target = target; self.invert = invert; self.group = group
    }
}

public struct RouteGroup: Codable, Hashable, Sendable {
    public static let urltest = "urltest"
    public static let selector = "selector"
    public var name: String
    public var type: String
    public var members: [String]
    public var url: String
    public var intervalSec: Int
    public init(name: String, type: String = RouteGroup.urltest, members: [String] = [],
                url: String = "https://www.gstatic.com/generate_204", intervalSec: Int = 300) {
        self.name = name; self.type = type; self.members = members; self.url = url; self.intervalSec = intervalSec
    }
    public var tag: String { RouteTarget.group(name) }
}

/// Правила, группы и обход DPI одним значением — хранится в `RoutingSettings` (входит в профили маршрутизации).
public struct RouteConfig: Codable, Hashable, Sendable {
    public var rules: [RouteRule] = []
    public var groups: [RouteGroup] = []
    public var dpi = DpiSettings()
    public init() {}
}

/// Цепочка профиля: `dpi`, `node-<id>` или nil (в `extra.via`).
public func viaOf(_ p: ServerProfile) -> String? {
    let v = (p.extraObject["via"] as? String) ?? ""
    return v.isEmpty ? nil : v
}

public func withVia(_ p: ServerProfile, _ via: String?) -> ServerProfile {
    var q = p
    var o = p.extraObject
    if let via, !via.isEmpty { o["via"] = via } else { o.removeValue(forKey: "via") }
    if let d = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]), let s = String(data: d, encoding: .utf8) { q.extra = s }
    return q
}

public struct RouteNode: @unchecked Sendable {
    public var tag: String
    public var outbound: [String: Any]
    public var via: String?
    public init(tag: String, outbound: [String: Any], via: String? = nil) { self.tag = tag; self.outbound = outbound; self.via = via }
}

public struct RoutePlan: @unchecked Sendable {
    public var rules: [RouteRule] = []
    public var nodes: [RouteNode] = []
    public var groups: [RouteGroup] = []
    public var dpi = DpiSettings()
    public var proxyVia: String?
    public init() {}

    public var isEmpty: Bool { rules.isEmpty && nodes.isEmpty && groups.isEmpty && !dpi.enabled && proxyVia == nil }

    /// Нужен ли локальный ByeDPI: «напрямую» через него, правило/группа/узел на выход «dpi» или цепочка «DPI → сервер».
    public var needsDpi: Bool {
        dpi.enabled || proxyVia == RouteTarget.dpi || rules.contains { $0.target == RouteTarget.dpi } ||
            nodes.contains { $0.via == RouteTarget.dpi } || groups.contains { $0.members.contains(RouteTarget.dpi) }
    }

    /// План без всего, что опирается на ByeDPI: если он не запустился, соединение всё равно поднимается.
    public func withoutDpi() -> RoutePlan {
        var p = self
        p.dpi.enabled = false
        if p.proxyVia == RouteTarget.dpi { p.proxyVia = nil }
        p.rules = rules.filter { $0.target != RouteTarget.dpi }
        p.nodes = nodes.map { n in var m = n; if m.via == RouteTarget.dpi { m.via = nil }; return m }
        p.groups = groups.map { g in var h = g; h.members = g.members.filter { $0 != RouteTarget.dpi }; return h }
        return p
    }
}

public enum RoutePlanApplier {
    private static let chainable: Set<String> = ["vless", "vmess", "trojan", "shadowsocks", "socks", "http", "ssh"]
    private static let domainish: Set<RouteKind> = [.domain, .suffix, .keyword, .regex, .geosite]

    public static func dpiOutbound(port: Int) -> [String: Any] {
        ["type": "socks", "tag": RouteTarget.dpi, "server": "127.0.0.1", "server_port": port, "version": "5"]
    }

    /// Дополняет `root` (результат `SingBoxConfigBuilder`) планом. `geoPath` — путь к базе страны/набора (nil — базы нет,
    /// правило пропускается). Возвращает предупреждения для журнала.
    @discardableResult
    public static func apply(_ root: inout [String: Any], _ plan: RoutePlan, geoPath: (RouteKind, String) -> String? = { _, _ in nil }) -> [String] {
        if plan.isEmpty { return [] }
        var warn: [String] = []
        var outbounds = root["outbounds"] as? [[String: Any]] ?? []
        var route = root["route"] as? [String: Any] ?? [:]
        let rules = route["rules"] as? [[String: Any]] ?? []

        var tags = Set(outbounds.compactMap { $0["tag"] as? String })
        func add(_ o: [String: Any]) {
            if let t = o["tag"] as? String, tags.insert(t).inserted { outbounds.append(o) }
        }
        func index(_ tag: String) -> Int? { outbounds.firstIndex { ($0["tag"] as? String) == tag } }

        if plan.needsDpi { add(dpiOutbound(port: plan.dpi.port)) }
        plan.nodes.forEach { add($0.outbound) }

        // Цепочки: «выход → через выход»; глубина любая, петли отсекаются.
        func loops(_ tag: String, _ via: String) -> Bool {
            var cur: String? = via
            var guardCount = 0
            while let c = cur, guardCount < 16 {
                if c == tag { return true }
                guardCount += 1
                cur = index(c).flatMap { outbounds[$0]["detour"] as? String }
            }
            return false
        }
        func chain(_ tag: String, _ via: String?) {
            guard let via, let i = index(tag) else { return }
            let type = outbounds[i]["type"] as? String ?? ""
            if !tags.contains(via) { warn.append("цепочка «\(via) → \(tag)» не применена: выхода «\(via)» нет") }
            else if via == tag { warn.append("цепочка «\(tag) → \(tag)» не применена: выход ведёт сам в себя") }
            else if !chainable.contains(type) { warn.append("цепочка не применена к «\(tag)»: протокол \(type) работает по UDP — через другой выход его не провести") }
            else if loops(tag, via) { warn.append("цепочка «\(via) → \(tag)» не применена: получилась бы петля") }
            else { outbounds[i]["detour"] = via }
        }
        plan.nodes.forEach { chain($0.tag, $0.via) }
        chain(RouteTarget.proxy, plan.proxyVia)

        // Группы выходов.
        for g in plan.groups {
            let members = g.members.filter { tags.contains($0) && $0 != g.tag }
            if members.isEmpty { warn.append("группа «\(g.name)» пропущена: в ней нет доступных выходов"); continue }
            var o: [String: Any] = ["tag": g.tag, "outbounds": members]
            if g.type == RouteGroup.selector { o["type"] = "selector"; o["default"] = members[0] }
            else {
                o["type"] = "urltest"; o["url"] = g.url
                o["interval"] = "\(min(max(g.intervalSec, 30), 3600))s"; o["tolerance"] = 100
            }
            add(o)
        }

        // Правила — сразу после sniff и hijack-dns.
        var at = 0
        for (i, r) in rules.enumerated() {
            let a = r["action"] as? String
            if a == "sniff" || a == "hijack-dns" { at = i + 1 }
        }
        var ruleSets = route["rule_set"] as? [[String: Any]] ?? []
        var known = Set(ruleSets.compactMap { $0["tag"] as? String })

        func cond(_ r: RouteRule) -> (String, Any)? {
            let v = r.value.trimmingCharacters(in: .whitespaces)
            switch r.kind {
            case .app: return ("package_name", v)
            case .process: return ("process_name", v)
            case .domain: return ("domain", v)
            case .suffix: return ("domain_suffix", v.hasPrefix(".") ? String(v.dropFirst()) : v)
            case .keyword: return ("domain_keyword", v)
            case .regex: return ("domain_regex", v)
            case .cidr: return ("ip_cidr", v)
            case .srcCidr: return ("source_ip_cidr", v)
            case .proto: return ("protocol", v.lowercased())
            case .network: let n = v.lowercased(); return (n == "tcp" || n == "udp") ? ("network", n) : nil
            case .port:
                if v.contains("-") { return ("port_range", v.replacingOccurrences(of: "-", with: ":")) }
                return Int(v).map { ("port", $0) }
            case .geoip, .geosite:
                let name = v.lowercased()
                let tag = "\(r.kind.rawValue)-\(name)"
                if !known.contains(tag) {
                    guard let path = geoPath(r.kind, name) else { warn.append("правило «\(r.kind.rawValue) \(name)» пропущено: нет базы"); return nil }
                    ruleSets.append(["type": "local", "tag": tag, "format": path.hasSuffix(".json") ? "source" : "binary", "path": path])
                    known.insert(tag)
                }
                return ("rule_set", tag)
            }
        }

        // Порядок сохраняем; склеиваем только условия одной непустой группы.
        var sequence: [[RouteRule]] = []
        var groupIndex: [String: Int] = [:]
        for r in plan.rules {
            if r.value.trimmingCharacters(in: .whitespaces).isEmpty { continue }
            if r.target != RouteTarget.block && !tags.contains(r.target) {
                warn.append("правило «\(r.kind.rawValue) \(r.value)» пропущено: выхода «\(r.target)» нет"); continue
            }
            if r.group.isEmpty { sequence.append([r]) }
            else {
                let key = r.group + "\u{1}" + r.target
                if let i = groupIndex[key] { sequence[i].append(r) } else { groupIndex[key] = sequence.count; sequence.append([r]) }
            }
        }

        var built: [[String: Any]] = []
        var dnsRules: [[String: Any]] = []
        for conds in sequence {
            guard let target = conds.first?.target else { continue }
            var plain: [String: Any] = [:]
            var negated: [String: Any] = [:]
            var ok = true
            for c in conds {
                guard let (k, v) = cond(c) else { ok = false; break }
                if c.invert { negated[k] = (negated[k] as? [Any] ?? []) + [v] }
                else { plain[k] = (plain[k] as? [Any] ?? []) + [v] }
            }
            if !ok || (plain.isEmpty && negated.isEmpty) { continue }
            let dnsMatch = plain
            var rule: [String: Any]
            if negated.isEmpty { rule = plain } else {
                var parts: [[String: Any]] = []
                if !plain.isEmpty { parts.append(plain) }
                for (k, v) in negated.sorted(by: { $0.key < $1.key }) { parts.append([k: v, "invert": true]) }
                rule = ["type": "logical", "mode": "and", "rules": parts]
            }
            if target == RouteTarget.block { rule["action"] = "reject" } else { rule["outbound"] = target }
            built.append(rule)
            if conds.count == 1, !conds[0].invert, domainish.contains(conds[0].kind), target == RouteTarget.direct || target == RouteTarget.dpi {
                var d = dnsMatch; d["server"] = "local"; dnsRules.append(d)
            }
        }
        if !ruleSets.isEmpty { route["rule_set"] = ruleSets }

        var merged = Array(rules.prefix(at)) + built + Array(rules.dropFirst(at))

        if !dnsRules.isEmpty, var dns = root["dns"] as? [String: Any] {
            dns["rules"] = dnsRules + (dns["rules"] as? [[String: Any]] ?? [])
            root["dns"] = dns
        }

        // «Напрямую» через обход DPI (локальные адреса остаются прямыми).
        if plan.dpi.enabled && plan.dpi.directViaDpi {
            for i in merged.indices where (merged[i]["outbound"] as? String) == "direct" && (merged[i]["ip_is_private"] as? Bool) != true {
                merged[i]["outbound"] = RouteTarget.dpi
            }
            if (route["final"] as? String) == "direct" { route["final"] = RouteTarget.dpi }
        }
        route["rules"] = merged
        root["outbounds"] = outbounds
        root["route"] = route
        return warn
    }
}

public enum RoutePlanFactory {
    private static let maxNodes = 24

    /// Собирает план для подключения `profile`. `supported` — виды правил платформы (iOS: пусто — нет ни приложений, ни процессов,
    /// но домены, адреса, порты, страны работают: передавайте всё, кроме `.app`/`.process`). `nodeOutbound` — outbound узла (nil — нельзя).
    public static func build(
        _ cfg: RouteConfig, profile: ServerProfile?, supported: Set<RouteKind>,
        lookup: (Int64) -> ServerProfile?, nodeOutbound: (ServerProfile, String) -> [String: Any]?
    ) -> RoutePlan {
        var plan = RoutePlan()
        let rules = cfg.rules.filter { supported.contains($0.kind) }
        let via = profile.flatMap(viaOf)

        let groupsByTag = Dictionary(cfg.groups.map { ($0.tag, $0) }, uniquingKeysWith: { a, _ in a })
        var used: [RouteGroup] = []
        var usedTags = Set<String>()
        func useGroup(_ tag: String) {
            guard let g = groupsByTag[tag], usedTags.insert(tag).inserted else { return }
            used.append(g)
            g.members.filter(RouteTarget.isGroup).forEach(useGroup)
        }
        (rules.map(\.target) + (via.map { [$0] } ?? [])).filter(RouteTarget.isGroup).forEach(useGroup)

        var queue = Array(NSOrderedSet(array: rules.map(\.target) + used.flatMap(\.members) + (via.map { [$0] } ?? []))) as? [String] ?? []
        var nodes: [String: RouteNode] = [:]
        var order: [String] = []
        while !queue.isEmpty, nodes.count < maxNodes {
            let target = queue.removeFirst()
            guard let id = RouteTarget.nodeId(target), nodes[target] == nil, id != profile?.id,
                  let p = lookup(id), let o = nodeOutbound(p, target) else { continue }
            let nodeVia = viaOf(p)
            nodes[target] = RouteNode(tag: target, outbound: o, via: nodeVia)
            order.append(target)
            if let nodeVia { queue.append(nodeVia) }
        }
        plan.rules = rules
        plan.nodes = order.compactMap { nodes[$0] }
        plan.groups = used
        plan.dpi = cfg.dpi
        plan.proxyVia = via
        return plan
    }
}
