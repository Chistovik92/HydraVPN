import Foundation

/// Настройки обхода DPI (0.7.13) — порт `DpiSettings`/`DpiArgs` с Android. Движок — ByeDPI (`ciadpi`, MIT): локальный SOCKS5,
/// который режет и подделывает первые пакеты соединения так, что DPI провайдера не узнаёт запрещённый хост (SNI).
public struct DpiSettings: Codable, Hashable, Sendable {
    /// Порт локального SOCKS5 ByeDPI (OpenFlux/olcRTC — 10810/10809).
    public static let defaultPort = 10880

    public var enabled: Bool
    public var strategy: String
    public var port: Int
    /// Трафик «напрямую» идёт через обход DPI.
    public var directViaDpi: Bool

    public init(enabled: Bool = false, strategy: String = DpiStrategies.defaultStrategy, port: Int = DpiSettings.defaultPort, directViaDpi: Bool = true) {
        self.enabled = enabled; self.strategy = strategy; self.port = port; self.directViaDpi = directViaDpi
    }
}

public enum DpiArgs {
    private static let banned: Set<String> = ["-h", "--help", "-v", "--version", "-D", "--daemon", "-w", "--pidfile", "-P"]
    private static let own: Set<String> = ["-i", "--ip", "-p", "--port"]

    /// Разбор строки как в командной оболочке: пробелы разделяют, кавычки группируют.
    public static func shellSplit(_ s: String) -> [String] {
        var out: [String] = []
        var cur = ""
        var quote: Character?
        var has = false
        for c in s {
            if let q = quote {
                if c == q { quote = nil } else { cur.append(c) }
            } else if c == "\"" || c == "'" {
                quote = c; has = true
            } else if c.isWhitespace {
                if has || !cur.isEmpty { out.append(cur); cur = ""; has = false }
            } else {
                cur.append(c)
            }
        }
        if has || !cur.isEmpty { out.append(cur) }
        return out
    }

    /// Строка стратегии без служебного: `{sni}` подставлен, опасные и «наши» ключи убраны.
    public static func sanitize(_ strategy: String) -> [String] {
        let replaced = strategy.replacingOccurrences(of: "{sni}", with: DpiStrategies.fakeSni)
        let toks = Array(shellSplit(replaced).drop(while: { !$0.hasPrefix("-") }))
        var out: [String] = []
        var i = 0
        while i < toks.count {
            let t = toks[i]
            let key = String(t.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
            if banned.contains(key) {
                if ["-w", "--pidfile", "-P"].contains(key), !t.contains("=") { i += 1 }
            } else if own.contains(key) {
                if !t.contains("=") { i += 1 }
            } else if t.count > 2, t.hasPrefix("-"), !t.hasPrefix("--"), ["i", "p"].contains(String(t.dropFirst().prefix(1))),
                      t.dropFirst(2).allSatisfy({ $0.isNumber || $0 == "." || $0 == ":" }) {
                // «слитные» формы -i127.0.0.1 / -p1080 — тоже наши
            } else {
                out.append(t)
            }
            i += 1
        }
        return out
    }

    /// Полный список аргументов для `ciadpi` (без имени программы).
    public static func build(_ strategy: String, port: Int = DpiSettings.defaultPort, ip: String = "127.0.0.1") -> [String] {
        let s = strategy.trimmingCharacters(in: .whitespaces).isEmpty ? DpiStrategies.defaultStrategy : strategy
        return ["-i", ip, "-p", String(port)] + sanitize(s)
    }

    public static func isUsable(_ strategy: String) -> Bool { !sanitize(strategy).isEmpty }
}

/// Итог проверки стратегии: сколько сайтов открылось (порт `DpiProbe.Result` с Android).
public struct DpiProbeResult: Hashable, Sendable, Identifiable {
    public struct Group: Hashable, Sendable { public var name: String; public var ok: Int; public var total: Int }
    public var strategy: String
    public var ok: Int
    public var total: Int
    public var groups: [Group]
    public var id: String { strategy }
    public var ratio: Double { total == 0 ? 0 : Double(ok) / Double(total) }
    public var percent: Int { Int(ratio * 100) }
    public init(strategy: String, ok: Int, total: Int, groups: [Group] = []) {
        self.strategy = strategy; self.ok = ok; self.total = total; self.groups = groups
    }
}

public enum DpiProbe {
    /// Быстрый режим — первые стратегии списка, полный — все.
    public static let quick = 15

    /// Сайты по группам для проверки: выбранные группы + свой сайт.
    public static func sites(groups: Set<String>, extra: String) -> [(name: String, sites: [String])] {
        var out: [(String, [String])] = []
        for g in ["youtube", "discord", "telegram", "general"] where groups.contains(g) {
            if let s = DpiStrategies.sites[g] { out.append((g, s)) }
        }
        let e = extra.trimmingCharacters(in: .whitespaces)
        if !e.isEmpty { out.append(("custom", [e])) }
        if out.isEmpty, let g = DpiStrategies.sites["general"] { out.append(("general", g)) }
        return out.map { (name: $0.0, sites: $0.1) }
    }
}
