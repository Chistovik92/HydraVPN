import Foundation
import HydraKit
import Security

/// Сопряжённые роутеры в Keychain (только это устройство, без iCloud): токен управления даёт
/// полный контроль над роутером, поэтому он не лежит ни в App Group, ни в резервной копии Hydra.
enum RouterKeychain {
    private static let service = "ru.gidravpn.hydra.routers"
    private static let account = "links"

    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    static func load() -> [RouterLink] {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return [] }
        return (try? JSONDecoder().decode([RouterLink].self, from: data)) ?? []
    }

    static func save(_ links: [RouterLink]) {
        guard let data = try? JSONEncoder().encode(links) else { return }
        let update = [kSecValueData as String: data]
        if SecItemUpdate(query as CFDictionary, update as CFDictionary) == errSecItemNotFound {
            var add = query
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }
}

/// Состояние выбранного роутера и команды к нему — аналог RouterManager из :shared (Android и ПК).
@MainActor
final class RouterModel: ObservableObject {
    @Published private(set) var links: [RouterLink] = RouterKeychain.load()
    @Published var selected: String?
    @Published private(set) var busy = false
    @Published private(set) var online = false
    @Published private(set) var error: String?
    @Published private(set) var status = RouterStatus()
    @Published private(set) var sections: [RouterSection] = []
    @Published private(set) var subscriptions: [RouterSubscription] = []
    @Published private(set) var nodes: [RouterNode] = []
    @Published private(set) var nodesError: String?
    @Published private(set) var logs: [RouterLogEntry] = []
    @Published private(set) var delays: [String: Int] = [:]
    @Published var message: String?

    init() { selected = links.first?.baseURL }

    var current: RouterLink? { links.first { $0.baseURL == selected } }

    /// Ссылка `hydravpn-router://…` или адрес + отдельно токен.
    @discardableResult
    func add(link: String, token: String, name: String) -> Bool {
        guard var l = RouterLink.parse(link, token: token.isEmpty ? nil : token) else {
            message = L("rt_bad_link")
            return false
        }
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        l.name = n.isEmpty ? l.host : String(n.prefix(40))
        links = links.filter { $0.baseURL != l.baseURL } + [l]
        RouterKeychain.save(links)
        select(l.baseURL)
        if l.insecure { message = L("rt_insecure") }
        return true
    }

    func remove(_ l: RouterLink) {
        links.removeAll { $0.baseURL == l.baseURL }
        RouterKeychain.save(links)
        select(links.first?.baseURL)
    }

    func select(_ baseURL: String?) {
        selected = baseURL
        online = false; error = nil; status = RouterStatus()
        sections = []; subscriptions = []; nodes = []; nodesError = nil; logs = []; delays = [:]
        Task { await refresh() }
    }

    /// Полный опрос: версия, статус, секции, подписки, узлы, журнал.
    func refresh() async {
        await run(quiet: true) { c in
            let st = try await c.status()
            let sec = try await c.sections()
            let subs = try await c.subscriptions()
            var nodeList: [RouterNode] = []
            var nodeErr: String?
            do { nodeList = try await c.nodes() } catch { nodeErr = error.localizedDescription }
            let log = (try? await c.logs(150)) ?? []
            await MainActor.run {
                self.online = true; self.error = nil; self.status = st
                self.sections = sec; self.subscriptions = subs
                self.nodes = nodeList; self.nodesError = nodeErr; self.logs = Array(log.suffix(150))
            }
        }
    }

    func selectNode(group: String, node: String) async {
        await run { c in try await c.selectNode(group: group, node: node); await self.reloadLists(c) }
    }

    func testNode(_ node: String) async {
        await run(quiet: true) { c in
            let ms = try await c.testNode(node)
            await MainActor.run { self.delays[node] = ms }
        }
    }

    func addSubscription(section: String, url: String) async {
        await run(done: L("rt_msg_sub_added")) { c in try await c.addSubscription(section: section, url: url); await self.reloadLists(c) }
    }

    func refreshSubscription(_ index: Int) async {
        await run(done: L("rt_msg_sub_updated")) { c in try await c.refreshSubscription(index); await self.reloadLists(c) }
    }

    func deleteSubscription(_ index: Int) async {
        await run(done: L("rt_msg_sub_deleted")) { c in try await c.deleteSubscription(index); await self.reloadLists(c) }
    }

    func restart() async { await run(done: L("rt_msg_restarting")) { c in try await c.restart() } }
    func reloadConfig() async { await run(done: L("rt_msg_reloaded")) { c in try await c.reload(); await self.reloadLists(c) } }

    private func reloadLists(_ c: RouterClient) async {
        let subs = (try? await c.subscriptions()) ?? subscriptions
        var list = nodes
        var err: String?
        do { list = try await c.nodes() } catch { err = error.localizedDescription }
        subscriptions = subs; nodes = list; nodesError = err
    }

    /// Команды идут строго по очереди: пока одна выполняется, вторая не стартует.
    private func run(done: String? = nil, quiet: Bool = false, _ block: @escaping (RouterClient) async throws -> Void) async {
        guard let link = current, !busy else { return }
        busy = true
        defer { busy = false }
        do {
            try await block(RouterClient(link: link))
            if let done { message = done }
        } catch {
            let msg: String
            if let e = error as? RouterError { msg = e.message; online = true }
            else if let e = error as? URLError, e.code == .timedOut || e.code == .cannotConnectToHost {
                msg = L("rt_unreachable", "\(link.host):\(link.port)"); online = false
            } else if let e = error as? URLError, e.code == .cancelled || e.code == .serverCertificateUntrusted {
                msg = L("rt_pin_failed"); online = false
            } else { msg = error.localizedDescription; online = false }
            self.error = msg
            if !quiet { message = msg }
        }
    }
}
