import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(Security) && canImport(CryptoKit)
import CryptoKit
import Security
#endif

// Клиент управляющего API роутера HydraVPN for Router (`/api/v1/…`, docs/API.md проекта
// https://github.com/Chistovik92/HydraVPNforRouters). Те же поля и правила, что у RouterClient.kt
// в :shared (Android и ПК). Ссылка сопряжения: hydravpn-router://host:port?token=…&tls=1&fp=<sha256>.

/// Роутер, добавленный в приложение: адрес, токен управления и (при TLS) отпечаток сертификата.
public struct RouterLink: Codable, Hashable, Identifiable, Sendable {
    public var host: String
    public var port: Int
    public var token: String
    public var tls: Bool
    /// SHA-256 сертификата роутера (hex): закрепляется вместо доверия самоподписанному сертификату.
    public var fingerprint: String?
    public var name: String

    public static let scheme = "hydravpn-router"
    public static let defaultPort = 8088

    public init(host: String, port: Int, token: String, tls: Bool = false, fingerprint: String? = nil, name: String? = nil) {
        self.host = host; self.port = port; self.token = token; self.tls = tls
        self.fingerprint = fingerprint
        self.name = name ?? host
    }

    public var id: String { baseURL }

    public var baseURL: String {
        "\(tls ? "https" : "http")://\(host.contains(":") ? "[\(host)]" : host):\(port)"
    }

    /// Токен по открытому HTTP видит любой в той же сети — интерфейс предупреждает.
    public var insecure: Bool { !tls && !Self.isLoopback(host) }

    /// Ссылка сопряжения (для копирования/QR).
    public var uri: String {
        var c = URLComponents()
        c.scheme = Self.scheme
        c.host = host
        c.port = port
        var q = [URLQueryItem(name: "token", value: token), URLQueryItem(name: "tls", value: tls ? "1" : "0")]
        if let fingerprint { q.append(URLQueryItem(name: "fp", value: fingerprint)) }
        c.queryItems = q
        return c.string ?? ""
    }

    private static func isLoopback(_ h: String) -> Bool { h == "localhost" || h.hasPrefix("127.") || h == "::1" }

    /// Ссылка сопряжения или «адрес[:порт]» + отдельно токен. nil — не разобралось.
    public static func parse(_ raw: String, token override: String? = nil) -> RouterLink? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        guard let c = URLComponents(string: s.contains("://") ? s : "\(scheme)://\(s)"),
              let sc = c.scheme?.lowercased(), sc == scheme || sc == "http" || sc == "https",
              var host = c.host, !host.isEmpty else { return nil }
        if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
        if !host.contains(":") && host.range(of: "^[A-Za-z0-9.-]{1,253}$", options: .regularExpression) == nil { return nil }
        func q(_ name: String) -> String? { c.queryItems?.first { $0.name == name }?.value }
        let overrideToken = override?.trimmingCharacters(in: .whitespacesAndNewlines)
        let token = ((overrideToken?.isEmpty == false) ? overrideToken : q("token")?.trimmingCharacters(in: .whitespacesAndNewlines)) ?? ""
        guard !token.isEmpty, token.count <= 256,
              !token.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || CharacterSet.controlCharacters.contains($0) })
        else { return nil }
        let tls: Bool
        switch sc {
        case "https": tls = true
        case "http": tls = false
        default: tls = q("tls") == "1" || q("tls")?.lowercased() == "true"
        }
        var fp = q("fp")?.lowercased().replacingOccurrences(of: ":", with: "")
        if fp?.isEmpty == true { fp = nil }
        if let f = fp, f.range(of: "^[0-9a-f]{64}$", options: .regularExpression) == nil { return nil }
        let port = c.port.flatMap { (1...65535).contains($0) ? $0 : nil } ?? defaultPort
        return RouterLink(host: host, port: port, token: token, tls: tls, fingerprint: tls ? fp : nil)
    }
}

public struct RouterError: Error, LocalizedError, Sendable {
    public let code: Int
    public let message: String
    public var errorDescription: String? { message }
}

public struct RouterSection: Hashable, Sendable {
    public let name: String
    public let label: String
    public let enabled: Bool
    public let action: String
    public let provider: String
    public var title: String { label.isEmpty ? name : label }
}

public struct RouterSubscription: Hashable, Sendable {
    public let index: Int
    public let section: String
    /// Роутер отдаёт адрес с замаскированным токеном.
    public let url: String
    public let autoUpdate: Bool
    public let updateIntervalHours: Double
}

public struct RouterNode: Hashable, Sendable {
    public let name: String
    public let type: String
    /// У группы — выбранный узел.
    public let now: String
    public let members: [String]
    public let delayMs: Int
    public let country: String
    public var isGroup: Bool { !members.isEmpty }
}

public struct RouterLogEntry: Hashable, Sendable {
    public let time: String
    public let level: String
    public let message: String
}

public struct RouterStatus: Sendable {
    public init() {}
    public var version = ""
    public var state = ""
    public var uptime = ""
    public var lastError = ""
}

/// Клиент API роутера. Для TLS с известным отпечатком сертификат принимается ТОЛЬКО по нему
/// (самоподписанный сертификат роутера), без отпечатка — обычной цепочкой доверия системы.
public final class RouterClient: @unchecked Sendable {
    public let link: RouterLink
    private let session: URLSession

    public init(link: RouterLink, timeout: TimeInterval = 10) {
        self.link = link
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = timeout
        cfg.timeoutIntervalForResource = 120
        cfg.connectionProxyDictionary = [:]          // роутер в LAN — мимо системного прокси
        #if canImport(Security) && canImport(CryptoKit)
        let delegate: URLSessionDelegate? = link.tls ? link.fingerprint.map { PinningDelegate(fingerprint: $0) } : nil
        #else
        let delegate: URLSessionDelegate? = nil
        #endif
        session = URLSession(configuration: cfg, delegate: delegate, delegateQueue: nil)
    }

    // MARK: - эндпоинты

    public func status() async throws -> RouterStatus {
        let v = try obj(await get("/api/v1/version"))
        let s = try obj(await get("/api/v1/status"))
        return RouterStatus(version: v["version"] as? String ?? "", state: s["state"] as? String ?? "",
                            uptime: s["uptime"] as? String ?? "", lastError: s["last_error"] as? String ?? "")
    }

    public func sections() async throws -> [RouterSection] {
        try list(await get("/api/v1/sections")).map {
            RouterSection(name: $0["name"] as? String ?? "", label: $0["label"] as? String ?? "",
                          enabled: $0["enabled"] as? Bool ?? false, action: $0["action"] as? String ?? "",
                          provider: ($0["provider"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "singbox")
        }
    }

    public func subscriptions() async throws -> [RouterSubscription] {
        try list(await get("/api/v1/subscriptions")).map {
            // time.Duration в JSON — наносекунды.
            let ns = ($0["subscription_update_interval"] as? NSNumber)?.doubleValue ?? 0
            return RouterSubscription(index: ($0["index"] as? Int) ?? 0, section: $0["section"] as? String ?? "",
                                      url: $0["url"] as? String ?? "",
                                      autoUpdate: $0["subscription_update_enabled"] as? Bool ?? false,
                                      updateIntervalHours: ns / 3.6e12)
        }
    }

    /// Добавить подписку в секцию роутера; он сохранит конфиг и применит его сразу.
    public func addSubscription(section: String, url: String, updateHours: Int = 24) async throws {
        guard url.lowercased().hasPrefix("http://") || url.lowercased().hasPrefix("https://") else {
            throw RouterError(code: 0, message: "subscription URL must start with https://")
        }
        // Сервер отвергает неизвестные поля — отправляем ровно поля SubscriptionURL.
        _ = try await send("POST", "/api/v1/subscriptions", [
            "section": section, "url": url,
            "subscription_update_enabled": updateHours > 0,
            "subscription_update_interval": Int64(max(updateHours, 1)) * 3_600_000_000_000,
        ])
    }

    public func refreshSubscription(_ index: Int) async throws { _ = try await send("POST", "/api/v1/subscriptions/\(index)/refresh", [:], timeout: 100) }
    public func deleteSubscription(_ index: Int) async throws { _ = try await send("DELETE", "/api/v1/subscriptions/\(index)", nil) }

    public func nodes() async throws -> [RouterNode] {
        try list(await get("/api/v1/nodes")).map {
            RouterNode(name: $0["name"] as? String ?? "", type: $0["type"] as? String ?? "", now: $0["now"] as? String ?? "",
                       members: $0["members"] as? [String] ?? [], delayMs: $0["delay_ms"] as? Int ?? 0,
                       country: $0["country"] as? String ?? "")
        }
    }

    public func selectNode(group: String, node: String) async throws {
        _ = try await send("POST", "/api/v1/nodes/select", ["group": group, "node": node])
    }

    public func testNode(_ node: String) async throws -> Int {
        let o = try obj(await send("POST", "/api/v1/nodes/test", ["node": node]))
        return o["delay_ms"] as? Int ?? -1
    }

    public func logs(_ n: Int = 200) async throws -> [RouterLogEntry] {
        try list(await get("/api/v1/logs?n=\(min(max(n, 1), 1000))")).map {
            RouterLogEntry(time: $0["time"] as? String ?? "", level: $0["level"] as? String ?? "", message: $0["message"] as? String ?? "")
        }
    }

    public func reload() async throws { _ = try await send("POST", "/api/v1/reload", [:]) }
    public func restart() async throws { _ = try await send("POST", "/api/v1/restart", [:]) }

    // MARK: - транспорт

    private func get(_ path: String) async throws -> Data { try await send("GET", path, nil) }

    private func send(_ method: String, _ path: String, _ body: [String: Any]?, timeout: TimeInterval? = nil) async throws -> Data {
        guard let url = URL(string: link.baseURL + path) else { throw RouterError(code: 0, message: "bad URL") }
        var req = URLRequest(url: url)
        req.httpMethod = method
        if let timeout { req.timeoutInterval = timeout }
        req.setValue("Bearer \(link.token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, resp) = try await session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code >= 400 {
            let text = String(data: data.prefix(4096), encoding: .utf8) ?? ""
            let fromJSON = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            let msg = (fromJSON?.isEmpty == false ? fromJSON : nil) ?? {
                switch code {
                case 401: return "invalid router token"
                case 403: return "router refuses connections from this address (api_allow)"
                case 429: return "too many failed attempts — wait a minute"
                default: return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "HTTP \(code)" : String(text.prefix(200))
                }
            }()
            throw RouterError(code: code, message: msg)
        }
        return data
    }

    private func obj(_ d: Data) throws -> [String: Any] {
        (try JSONSerialization.jsonObject(with: d) as? [String: Any]) ?? [:]
    }

    /// Массив объектов; `null` (пустой список на стороне Go) — пустой массив.
    private func list(_ d: Data) throws -> [[String: Any]] {
        guard !d.isEmpty, let any = try? JSONSerialization.jsonObject(with: d) else { return [] }
        return (any as? [[String: Any]]) ?? []
    }
}

#if canImport(Security) && canImport(CryptoKit)
/// TLS, где сертификат сервера принимается только при совпадении SHA-256 с закреплённым.
final class PinningDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    private let fingerprint: String
    init(fingerprint: String) { self.fingerprint = fingerprint.lowercased() }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let digest = SHA256.hash(data: SecCertificateCopyData(leaf) as Data).map { String(format: "%02x", $0) }.joined()
        if digest == fingerprint {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}
#endif
