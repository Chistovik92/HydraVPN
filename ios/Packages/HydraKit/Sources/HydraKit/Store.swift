import Foundation
#if canImport(FoundationNetworking)
    import FoundationNetworking
#endif

/// Состояние Hydra в одном JSON-файле в контейнере App Group — его читают приложение,
/// Network Extension и виджет. Аналог Room + DataStore на Android, но проще: данных немного,
/// а три процесса должны видеть одно и то же без миграций схемы.
public final class HydraStore: @unchecked Sendable {
    public static let appGroup = "group.ru.gidravpn.hydra"

    public let directory: URL
    private let lock = NSLock()
    private var fileURL: URL { directory.appendingPathComponent("state.json") }
    public var logURL: URL { directory.appendingPathComponent("tunnel.log") }
    public var geoDirectory: URL { directory.appendingPathComponent("geo", isDirectory: true) }

    public init(directory: URL) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// Хранилище в контейнере App Group (на устройстве), либо во временном каталоге (тесты, превью).
    public static func shared() -> HydraStore {
        #if canImport(Darwin)
            if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
                return HydraStore(directory: url.appendingPathComponent("Hydra", isDirectory: true))
            }
        #endif
        return HydraStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent("Hydra", isDirectory: true))
    }

    public func load() -> HydraState {
        lock.lock(); defer { lock.unlock() }
        guard let data = try? Data(contentsOf: fileURL),
              let s = try? JSONDecoder().decode(HydraState.self, from: data) else { return HydraState() }
        return s
    }

    public func save(_ state: HydraState) throws {
        lock.lock(); defer { lock.unlock() }
        let data = try JSONEncoder().encode(state)
        // Атомарная запись: туннель может читать файл в тот же момент.
        #if canImport(Darwin)
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
            try data.write(to: fileURL, options: .atomic)
        #endif
    }

    /// Изменить состояние и сохранить одной операцией.
    @discardableResult
    public func update(_ body: (inout HydraState) throws -> Void) throws -> HydraState {
        var s = load()
        try body(&s)
        try save(s)
        return s
    }

    // MARK: - журнал туннеля (пишет расширение, читает приложение)

    public func appendLog(_ line: String) {
        let stamp = ISO8601DateFormatter().string(from: Date())
        let data = Data("[\(stamp)] \(line)\n".utf8)
        if let h = try? FileHandle(forWritingTo: logURL) {
            defer { try? h.close() }
            _ = try? h.seekToEnd()
            try? h.write(contentsOf: data)
            // Не даём журналу расти бесконечно: при 1 МБ оставляем последнюю половину.
            if (try? h.offset()) ?? 0 > 1_000_000, let all = try? Data(contentsOf: logURL) {
                try? all.suffix(500_000).write(to: logURL, options: .atomic)
            }
        } else {
            try? data.write(to: logURL, options: .atomic)
        }
    }

    public func readLog(maxLines: Int = 500) -> [String] {
        guard let s = try? String(contentsOf: logURL, encoding: .utf8) else { return [] }
        return Array(s.split(separator: "\n").suffix(maxLines).map(String.init))
    }

    public func clearLog() { try? FileManager.default.removeItem(at: logURL) }
}

/// Загрузка подписки — порт `SubscriptionFetcher`: заголовки клиента (UA, HWID) и потолок 8 МБ
/// на тело ответа (защита от сломанной или враждебной панели).
public enum SubscriptionClient {
    public static let maxBodyBytes = 8 * 1024 * 1024

    public struct Result: Sendable {
        public var profiles: [ServerProfile]
        public var info: SubscriptionHeaders.Info
    }

    public enum FetchError: Error, LocalizedError {
        case http(Int), tooLarge, badURL, empty
        public var errorDescription: String? {
            switch self {
            case .http(let c): "HTTP \(c)"
            case .tooLarge: "ответ подписки больше 8 МБ"
            case .badURL: "некорректный адрес подписки"
            case .empty: "подписка не вернула ни одного сервера — прежний список сохранён"
            }
        }
    }

    public static func fetch(_ url: String, headers: [String: String], subscriptionId: Int64?) async throws -> Result {
        guard let u = URL(string: url) else { throw FetchError.badURL }
        var req = URLRequest(url: u, timeoutInterval: 15)
        headers.forEach { req.setValue($0.value, forHTTPHeaderField: $0.key) }
        let (data, response) = try await URLSession.shared.data(for: req)
        let http = response as? HTTPURLResponse
        if let code = http?.statusCode, !(200..<300).contains(code) { throw FetchError.http(code) }
        if data.count > maxBodyBytes { throw FetchError.tooLarge }
        let body = String(data: data, encoding: .utf8) ?? ""
        let info = SubscriptionHeaders.parse { name in http?.value(forHTTPHeaderField: name) }
        return Result(profiles: LinkParser.parseSubscription(body, subscriptionId: subscriptionId), info: info)
    }

    /// Обновить подписку в состоянии: синхронизация серверов и сведения от панели.
    /// Пустой ответ не применяется — прежний список сохраняется (страховка 7e на Android).
    public static func apply(_ result: Result, subscriptionId: Int64, to state: inout HydraState, now: Date = Date()) throws {
        guard let i = state.subscriptions.firstIndex(where: { $0.id == subscriptionId }) else { return }
        guard !result.profiles.isEmpty else {
            state.subscriptions[i].lastError = "подписка не вернула ни одного сервера — прежний список сохранён"
            throw FetchError.empty
        }
        let existing = state.servers.filter { $0.subscriptionId == subscriptionId }
        let plan = SubscriptionSync.plan(subId: subscriptionId, existing: existing, incoming: result.profiles)
        SubscriptionSync.apply(plan, to: &state)
        let inf = result.info
        state.subscriptions[i].serverTitle = inf.title
        state.subscriptions[i].uploadBytes = inf.upload
        state.subscriptions[i].downloadBytes = inf.download
        state.subscriptions[i].totalBytes = inf.total
        state.subscriptions[i].expireAt = inf.expire
        state.subscriptions[i].supportUrl = inf.supportUrl
        if let h = inf.updateHours { state.subscriptions[i].autoUpdateHours = h }
        state.subscriptions[i].lastUpdated = Int64(now.timeIntervalSince1970 * 1000)
        state.subscriptions[i].lastError = ""
    }
}
