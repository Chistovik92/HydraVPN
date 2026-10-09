import Foundation

/// Ссылка профиля «Обход DPI (ByeDPI)» (0.7.13), порт `ByeDpiLink` с Android: `byedpi://?s=<стратегия, URL-кодированная>#<имя>`.
/// Профиль: address = 127.0.0.1, порт не используется, стратегия — в extra.strategy.
public enum ByeDpiLink {
    private static let prefix = "byedpi://"

    public static func parse(_ link: String) -> ServerProfile? {
        guard link.lowercased().hasPrefix(prefix) else { return nil }
        let body = String(link.dropFirst(prefix.count))
        let namePart = body.contains("#") ? String(body[body.index(after: body.firstIndex(of: "#")!)...]) : ""
        let name = namePart.removingPercentEncoding.flatMap { $0.isEmpty ? nil : $0 } ?? "Обход DPI"
        let head = body.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let query = head.contains("?") ? String(head[head.index(after: head.firstIndex(of: "?")!)...]) : ""
        let raw = query.split(separator: "&").first { $0.hasPrefix("s=") }.map { String($0.dropFirst(2)) } ?? ""
        let strategy = raw.removingPercentEncoding.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? DpiStrategies.defaultStrategy
        let extra = (try? JSONSerialization.data(withJSONObject: ["strategy": strategy])).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return ServerProfile(name: name, protocolId: ServerProtocol.byedpi.rawValue, address: "127.0.0.1", port: DpiSettings.defaultPort, extra: extra, flag: "🛡️")
    }

    public static func build(_ p: ServerProfile) -> String {
        let s = (p.extraObject["strategy"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? DpiStrategies.defaultStrategy
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_.~"))
        let enc = { (x: String) in x.addingPercentEncoding(withAllowedCharacters: allowed) ?? x }
        return "\(prefix)?s=\(enc(s))#\(enc(p.name))"
    }
}
