import Foundation

/// Свой список доменов для проверки (как «Добавить список» в ByeByeDPI).
public struct CustomSiteList: Codable, Hashable, Sendable {
    public var name: String
    public var domains: [String]
    public init(name: String, domains: [String]) { self.name = name; self.domains = domains }
}

/// Настройки подбора стратегии — порт `DpiProbeSettings` с Android (0.7.15), на iOS с 0.7.20. Те же, что в ByeByeDPI:
///  - `delaySec` — пауза между проверками стратегий; `requests` — сколько раз спрашивать домен (сайт открыт, если ответило большинство);
///  - `parallel` — сколько запросов одновременно; `timeoutSec` — сколько ждать ответа домена;
///  - `groups` — какие встроенные списки проверять (ключи `DpiStrategies.sites`), `custom` — свои списки;
///  - `customStrategiesOn` + `customStrategies` — проверять не встроенные 60 стратегий, а свой список (по одной в строке).
public struct DpiProbeSettings: Codable, Hashable, Sendable {
    public var delaySec: Int
    public var requests: Int
    public var parallel: Int
    public var timeoutSec: Int
    public var groups: Set<String>
    public var custom: [CustomSiteList]
    public var customStrategiesOn: Bool
    public var customStrategies: String

    public init(delaySec: Int = 1, requests: Int = 1, parallel: Int = 16, timeoutSec: Int = 4,
                groups: Set<String> = ["youtube", "discord", "telegram"], custom: [CustomSiteList] = [],
                customStrategiesOn: Bool = false, customStrategies: String = "") {
        self.delaySec = delaySec; self.requests = requests; self.parallel = parallel; self.timeoutSec = timeoutSec
        self.groups = groups; self.custom = custom
        self.customStrategiesOn = customStrategiesOn; self.customStrategies = customStrategies
    }

    /// Выбранные списки: название → домены (порядок как в `DpiStrategies.groupOrder`, потом свои).
    public func selectedSites() -> [(name: String, sites: [String])] {
        var out: [(name: String, sites: [String])] = []
        for g in DpiStrategies.groupOrder where groups.contains(g) {
            if let s = DpiStrategies.sites[g] { out.append((name: g, sites: s)) }
        }
        for c in custom where groups.contains(c.name) && !c.domains.isEmpty { out.append((name: c.name, sites: c.domains)) }
        return out
    }

    /// Свои стратегии: непустые строки без комментариев и повторов.
    public func strategyLines() -> [String] {
        var seen = Set<String>()
        return customStrategies.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
            .filter { seen.insert($0).inserted }
    }

    // MARK: JSON в формате Android (`DpiProbeSettings.toJson`) — резервная копия переносится между платформами.

    public func androidObject() -> [String: Any] {
        [
            "delay": delaySec, "requests": requests, "parallel": parallel, "timeout": timeoutSec,
            "groups": groups.sorted(),
            "custom": custom.map { ["name": $0.name, "domains": $0.domains] as [String: Any] },
            "custom_strategies_on": customStrategiesOn, "custom_strategies": customStrategies,
        ]
    }

    public init(androidObject o: [String: Any]?) {
        self.init()
        guard let o else { return }
        func int(_ k: String, _ d: Int, _ r: ClosedRange<Int>) -> Int { min(max((o[k] as? NSNumber)?.intValue ?? d, r.lowerBound), r.upperBound) }
        delaySec = int("delay", delaySec, 0...30)
        requests = int("requests", requests, 1...5)
        parallel = int("parallel", parallel, 1...50)
        timeoutSec = int("timeout", timeoutSec, 1...20)
        if let g = o["groups"] as? [String] { groups = Set(g) }
        custom = (o["custom"] as? [[String: Any]] ?? []).compactMap { x in
            guard let name = x["name"] as? String, !name.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            return CustomSiteList(name: name, domains: x["domains"] as? [String] ?? [])
        }
        customStrategiesOn = o["custom_strategies_on"] as? Bool ?? false
        customStrategies = o["custom_strategies"] as? String ?? ""
    }
}
