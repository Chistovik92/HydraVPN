import Foundation

/// DNS-сервер для sing-box — порт `DnsEndpoint` с Android: голый IP/хост (→ DoH), либо
/// `https://host[:port]/path` (приватный DoH с токеном в пути), `tls://host[:port]`, `udp://host[:port]`.
public struct DnsEndpoint: Hashable, Sendable {
    public static let typeHTTPS = "https"
    public static let typeTLS = "tls"
    public static let typeUDP = "udp"

    public var type: String
    public var host: String
    public var port: Int?
    public var path: String?

    public init(type: String, host: String, port: Int? = nil, path: String? = nil) {
        self.type = type; self.host = host; self.port = port; self.path = path
    }

    public static func doh(_ ip: String) -> DnsEndpoint { DnsEndpoint(type: typeHTTPS, host: ip) }

    /// IP не нужно резолвить; для хоста sing-box 1.12 требует domain_resolver.
    public var isIp: Bool { host.range(of: #"^\d{1,3}(\.\d{1,3}){3}$"#, options: .regularExpression) != nil || host.contains(":") }

    /// nil — строку нельзя превратить в DNS-сервер (показать ошибку в UI).
    public static func parse(_ raw: String) -> DnsEndpoint? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        if !s.contains("://") {
            if s.filter({ $0 == ":" }).count >= 2,
               s.range(of: #"^\[?[0-9A-Fa-f:.]+\]?$"#, options: .regularExpression) != nil {
                return DnsEndpoint(type: typeHTTPS, host: s.trimmingCharacters(in: CharacterSet(charactersIn: "[]")))
            }
            let parts = s.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            let host = String(parts[0])
            let port: Int? = parts.count > 1 ? Int(parts[1]) : nil
            guard host.range(of: #"^[A-Za-z0-9.-]+$"#, options: .regularExpression) != nil else { return nil }
            if parts.count > 1 && port == nil { return nil }
            return DnsEndpoint(type: typeHTTPS, host: host, port: port)
        }
        guard let comps = URLComponents(string: s),
              let scheme = comps.scheme?.lowercased(), [typeHTTPS, typeTLS, typeUDP].contains(scheme),
              let rawHost = comps.host, !rawHost.isEmpty else { return nil }
        let host = rawHost.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        var path: String?
        if scheme == typeHTTPS {
            var p = comps.percentEncodedPath
            if let q = comps.percentEncodedQuery { p += "?" + q }
            if !p.isEmpty && p != "/" { path = p }
        }
        return DnsEndpoint(type: scheme, host: host, port: comps.port, path: path)
    }
}
