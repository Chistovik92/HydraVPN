import Foundation
#if canImport(FoundationNetworking)
    import FoundationNetworking
#endif

/// Аккаунт бота «Радар» (= Hydra VPN; API для приложений, контракт — docs/API_APPS.md в репозитории
/// Chistovik92/radar). Порт `data/botaccount` с Android и `bot/BotAccount.kt` с ПК: вход по одноразовому коду,
/// чтение выданных подписок, выход. API только читает.

/// Что приложение помнит об аккаунте (токен устройства здесь НЕ хранится — он в Keychain).
public struct BotAccountInfo: Codable, Hashable, Sendable {
    public var server: String
    public var username: String
    /// Сколько панелей выдано (из `/me`); nil — ещё не узнавали.
    public var panels: Int?
    public init(server: String, username: String = "", panels: Int? = nil) {
        self.server = server; self.username = username; self.panels = panels
    }
}

public struct BotSubscription: Hashable, Sendable {
    public var panel: String
    public var title: String
    /// `subscription` — ссылка подписки (её и импортируем); `key` и `config` — одиночный ключ/файл.
    public var linkKind: String
    /// `ok`, `no_link`, `panel_error`, `missing`.
    public var state: String
    public var enabled: Bool
    public var url: String

    public init(panel: String, title: String, linkKind: String = "subscription", state: String = "ok", enabled: Bool = true, url: String) {
        self.panel = panel; self.title = title; self.linkKind = linkKind; self.state = state; self.enabled = enabled; self.url = url
    }

    public var importable: Bool { state == "ok" && enabled && linkKind == "subscription" && !url.isEmpty }
}

public struct BotProfile: Hashable, Sendable {
    public var userId: String
    public var username: String
    public var vpnState: String
    public var panels: Int
}

/// Отказ бота: `code` — HTTP-код (0 — сети нет).
public struct BotError: Error, LocalizedError, Sendable {
    public var code: Int
    public var text: String
    public var errorDescription: String? { text }
    public init(code: Int, text: String) { self.code = code; self.text = text }
}

public enum BotJSON {
    public static func token(_ data: Data) throws -> String {
        guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any], let t = o["token"] as? String, !t.isEmpty
        else { throw BotError(code: 0, text: "bad response") }
        return t
    }

    public static func errorText(_ data: Data) -> String {
        ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["error"] as? String ?? ""
    }

    public static func profile(_ data: Data) throws -> BotProfile {
        guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw BotError(code: 0, text: "bad response") }
        let vpn = o["vpn"] as? [String: Any]
        return BotProfile(
            userId: o["user_id"] as? String ?? String(describing: o["user_id"] ?? ""),
            username: o["username"] as? String ?? "",
            vpnState: vpn?["state"] as? String ?? "none",
            panels: vpn?["panels"] as? Int ?? 0)
    }

    public static func subscriptions(_ data: Data) throws -> [BotSubscription] {
        guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw BotError(code: 0, text: "bad response") }
        let arr = o["subscriptions"] as? [[String: Any]] ?? []
        return arr.map { e in
            let panel = e["panel"].map { "\($0)" } ?? ""
            let title = (e["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "#" + panel
            return BotSubscription(
                panel: panel, title: title,
                linkKind: e["link_kind"] as? String ?? "subscription",
                state: e["state"] as? String ?? "ok",
                enabled: e["enabled"] as? Bool ?? false,
                url: e["url"] as? String ?? "")
        }
    }

    /// Приводит адрес к `https://host[:port]`; nil — негоден. Токен и ссылки подписок идут по этому соединению,
    /// поэтому обычный http допускается только в своей сети (localhost, 10.x, 192.168.x, 172.16–31.x).
    public static func normalizeServer(_ input: String) -> String? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        if s.isEmpty || s.contains(where: { $0.isWhitespace }) { return nil }
        if !s.contains("://") { s = "https://" + s }
        let scheme = String(s.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)[0]).lowercased()
        guard scheme == "https" || scheme == "http" else { return nil }
        let rest = s.dropFirst(scheme.count + 3)
        let authority = String(rest.prefix { $0 != "/" && $0 != "?" })
        if authority.isEmpty || authority.contains("@") { return nil }
        let host = authority.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)[0].lowercased()
        if host.isEmpty { return nil }
        if scheme == "http" && !isLocalHost(host) { return nil }
        return "\(scheme)://\(authority)"
    }

    static func isLocalHost(_ host: String) -> Bool {
        if host == "localhost" || host.hasSuffix(".local") { return true }
        let p = host.split(separator: ".").compactMap { Int($0) }
        guard p.count == 4, p.allSatisfy({ (0...255).contains($0) }) else { return false }
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && (16...31).contains(p[1]))
    }

    /// Приватный DoH Hydra VPN принимаем только как https-адрес (защита от случайной «${VAR}» из незаданной переменной сборки).
    public static func validPrivateDns(_ url: String?) -> String? {
        guard let u = url?.trimmingCharacters(in: .whitespacesAndNewlines), u.hasPrefix("https://"),
              DnsEndpoint.parse(u) != nil else { return nil }
        return u
    }
}

/// Клиент API. Редиректы не выполняются: токен не должен уйти на чужой адрес.
public final class BotClient: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private lazy var session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)

    public override init() { super.init() }

    public func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                           newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    public func link(server: String, code: String, device: String) async throws -> String {
        let body = try JSONSerialization.data(withJSONObject: ["code": code.filter(\.isNumber), "device": device, "app": "hydravpn"])
        return try BotJSON.token(try await call("POST", "\(server)/api/v1/app/link", token: nil, body: body))
    }

    public func profile(server: String, token: String) async throws -> BotProfile {
        try BotJSON.profile(try await call("GET", "\(server)/api/v1/app/me", token: token, body: nil))
    }

    public func subscriptions(server: String, token: String) async throws -> [BotSubscription] {
        try BotJSON.subscriptions(try await call("GET", "\(server)/api/v1/app/subscriptions", token: token, body: nil))
    }

    public func logout(server: String, token: String) async throws {
        _ = try await call("DELETE", "\(server)/api/v1/app/session", token: token, body: nil)
    }

    private func call(_ method: String, _ url: String, token: String?, body: Data?) async throws -> Data {
        guard let u = URL(string: url) else { throw BotError(code: 0, text: "bad url") }
        var req = URLRequest(url: u, timeoutInterval: 20)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            req.httpBody = body
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: req) } catch { throw BotError(code: 0, text: error.localizedDescription) }
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        if !(200..<300).contains(code) {
            let t = BotJSON.errorText(data)
            throw BotError(code: code, text: t.isEmpty ? "HTTP \(code)" : t)
        }
        return data
    }
}

/// Сопоставление подписок бота с подписками приложения (как BotSyncPlanner на Android и ПК): связь по панели,
/// ранее заведённые подхватываются по URL; выключенные в боте помечаются; ничего не удаляется.
public enum BotSyncPlanner {
    public static let disabledMark = "отключена в боте"

    public enum Step: Equatable, Sendable {
        case add(BotSubscription)
        case update(subId: Int64, BotSubscription)
        case disable(subId: Int64)
    }

    public static func plan(existing: [Subscription], items: [BotSubscription]) -> [Step] {
        var steps: [Step] = []
        var claimed = Set<Int64>()
        for item in items {
            let sub = existing.first { !claimed.contains($0.id) && ($0.botPanel ?? "") != "" && $0.botPanel == item.panel }
                ?? existing.first {
                    !claimed.contains($0.id) && ($0.botPanel ?? "").isEmpty && !item.url.isEmpty
                        && $0.url.trimmingCharacters(in: .whitespaces) == item.url.trimmingCharacters(in: .whitespaces)
                }
            if let sub { claimed.insert(sub.id) }
            if item.importable {
                steps.append(sub.map { Step.update(subId: $0.id, item) } ?? Step.add(item))
            } else if let sub, !item.enabled, item.linkKind == "subscription", sub.lastError != disabledMark {
                steps.append(Step.disable(subId: sub.id))
            }
        }
        return steps
    }
}
