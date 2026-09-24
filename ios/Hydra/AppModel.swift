import Foundation
import HydraKit
import NetworkExtension
import SwiftUI
import WidgetKit

/// Состояние и действия приложения — аналог MainViewModel на Android. Источник правды —
/// HydraStore в App Group: туннель и виджет читают тот же файл.
@MainActor
final class AppModel: ObservableObject {
    let store = HydraStore.shared()
    @Published private(set) var state: HydraState
    @Published private(set) var status: NEVPNStatus = .disconnected
    @Published var message: String?
    @Published var refreshing: Set<Int64> = []
    @Published var measuring: Set<Int64> = []
    @Published var connectedSince: Date?
    let traffic = TrafficClient()

    init() {
        state = HydraStore.shared().load()
        NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { [weak self] n in
            guard let conn = n.object as? NEVPNConnection else { return }
            Task { @MainActor in self?.apply(status: conn.status, since: conn.connectedDate) }
        }
        Task { await refreshStatus() }
    }

    func refreshStatus() async {
        let m = await VPNController.load()
        apply(status: m?.connection.status ?? .disconnected, since: m?.connection.connectedDate)
    }

    private func apply(status: NEVPNStatus, since: Date?) {
        self.status = status
        connectedSince = status == .connected ? since : nil
        if status == .connected { traffic.connect() } else if status == .disconnected { traffic.disconnect() }
        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: - сохранение

    func mutate(_ body: (inout HydraState) -> Void) {
        var s = state
        body(&s)
        state = s
        do { try store.save(s) } catch { message = L("msg_backup_save_failed", error.localizedDescription) }
        WidgetCenter.shared.reloadAllTimelines()
    }

    var selected: ServerProfile? { state.selectedServer }

    // MARK: - подключение

    func toggle() {
        if status.isUp { disconnect() } else { connect() }
    }

    func connect() {
        guard let server = selected else { message = L("main_no_server"); return }
        if server.serverProtocol?.engine != .singBox {
            message = L("ios_protocol_not_on_ios", server.serverProtocol?.displayName ?? server.protocolId)
            return
        }
        Task {
            do { try await VPNController.connect(state) } catch { message = error.localizedDescription }
        }
    }

    func disconnect() { Task { await VPNController.disconnect() } }

    /// Выбор сервера; при поднятом туннеле — сразу переключение (как на Android).
    func select(_ id: Int64) {
        mutate { $0.app.lastServerId = id }
        if status.isUp { Task { await VPNController.disconnect(); try? await Task.sleep(nanoseconds: 700_000_000); connect() } }
    }

    /// Настройки, влияющие на конфигурацию туннеля: переустановить её и, если туннель поднят, переподключиться.
    func applyTunnelSettings(reconnect: Bool = true) {
        let s = state
        Task {
            _ = try? await VPNController.install(s)
            if reconnect && status == .connected {
                await VPNController.disconnect()
                try? await Task.sleep(nanoseconds: 700_000_000)
                connect()
            }
        }
    }

    // MARK: - импорт

    func importText(_ text: String, subscriptionName: String? = nil) {
        switch ImportDetector.classify(text) {
        case .empty: message = L("srv_clipboard_empty")
        case .unsupportedJSON: message = L("import_json_unsupported")
        case .unknown: message = L("import_unknown")
        case .subscriptionURL(let url, let hint): addSubscription(name: subscriptionName ?? hint, url: url)
        case .servers(let profiles):
            mutate { s in
                for var p in profiles { p.id = s.nextServerId(); s.servers.append(p) }
            }
            message = profiles.count == 1 ? L("import_done_one", profiles[0].name) : L("import_done_servers", profiles.count)
        }
    }

    func addServer(_ p: ServerProfile) {
        mutate { s in var n = p; n.id = s.nextServerId(); s.servers.append(n) }
    }

    func deleteServer(_ p: ServerProfile) { mutate { $0.servers.removeAll { $0.id == p.id } } }

    // MARK: - подписки

    func addSubscription(name: String, url: String) {
        let clean = url.trimmingCharacters(in: .whitespaces)
        var id: Int64 = 0
        mutate { s in
            if let existing = s.subscriptions.first(where: { $0.url.trimmingCharacters(in: .whitespaces) == clean }) {
                id = existing.id
            } else {
                id = s.nextSubscriptionId()
                s.subscriptions.append(Subscription(id: id, name: name, url: clean))
            }
        }
        refresh(subscriptionId: id)
    }

    func refresh(subscriptionId id: Int64) { Task { await refreshNow(id) } }

    func refreshNow(_ id: Int64) async {
        guard !refreshing.contains(id), let sub = state.subscriptions.first(where: { $0.id == id }) else { return }
        refreshing.insert(id)
        do {
            defer { refreshing.remove(id) }
            do {
                let result = try await SubscriptionClient.fetch(sub.url, headers: HydraDevice.headers, subscriptionId: id)
                let before = Set(state.servers.filter { $0.subscriptionId == id }.map(SubscriptionSync.key))
                var failure: Error?
                mutate { s in
                    do { try SubscriptionClient.apply(result, subscriptionId: id, to: &s) } catch { failure = error }
                }
                if let failure { throw failure }
                let after = Set(result.profiles.map(SubscriptionSync.key))
                message = L("sub_refreshed", state.subscriptions.first { $0.id == id }?.displayName ?? sub.name,
                            result.profiles.count, after.subtracting(before).count, before.subtracting(after).count)
            } catch {
                mutate { s in
                    if let i = s.subscriptions.firstIndex(where: { $0.id == id }) { s.subscriptions[i].lastError = error.localizedDescription }
                }
                message = L("import_fail_sub", sub.displayName, error.localizedDescription)
            }
        }
    }

    func refreshAllSubscriptions() { state.subscriptions.forEach { refresh(subscriptionId: $0.id) } }

    /// Автообновление: подписки, у которых прошло autoUpdateHours (при запуске и из фоновой задачи).
    func refreshDueSubscriptions() { Task { await refreshDueNow() } }

    func refreshDueNow(now: Date = Date()) async {
        let ms = Int64(now.timeIntervalSince1970 * 1000)
        for s in state.subscriptions where s.autoUpdate && ms - s.lastUpdated >= Int64(max(1, s.autoUpdateHours)) * 3_600_000 {
            await refreshNow(s.id)
        }
    }

    func deleteSubscription(_ sub: Subscription) {
        mutate { s in
            s.servers.removeAll { $0.subscriptionId == sub.id }
            s.subscriptions.removeAll { $0.id == sub.id }
        }
    }

    func updateSubscription(_ sub: Subscription) {
        mutate { s in if let i = s.subscriptions.firstIndex(where: { $0.id == sub.id }) { s.subscriptions[i] = sub } }
    }

    // MARK: - пинг

    func ping(_ p: ServerProfile) { Task { await measure(p) } }

    private func measure(_ p: ServerProfile) async {
        guard !measuring.contains(p.id) else { return }
        measuring.insert(p.id)
        let ms = await Ping.measure(host: p.address, port: p.port)
        measuring.remove(p.id)
        mutate { s in if let i = s.servers.firstIndex(where: { $0.id == p.id }) { s.servers[i].pingMs = ms } }
    }

    /// Не больше восьми замеров одновременно (как на Android).
    func pingAll(_ list: [ServerProfile]? = nil) {
        let targets = list ?? state.servers
        Task {
            await withTaskGroup(of: Void.self) { group in
                var inFlight = 0
                for p in targets {
                    if inFlight >= 8 { await group.next(); inFlight -= 1 }
                    group.addTask { await self.measure(p) }
                    inFlight += 1
                }
            }
        }
    }

    // MARK: - профили маршрутизации

    func saveRoutingProfile(_ name: String) {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return }
        mutate { s in
            s.routingProfiles.removeAll { $0.name.caseInsensitiveCompare(n) == .orderedSame }
            s.routingProfiles.append(RoutingProfile(name: n, routing: s.routing))
        }
    }

    func applyRoutingProfile(_ p: RoutingProfile) {
        mutate { $0.routing = p.routing }
        applyTunnelSettings()
    }

    func deleteRoutingProfile(_ p: RoutingProfile) { mutate { $0.routingProfiles.removeAll { $0.name == p.name } } }

    // MARK: - резервная копия

    func exportBackup() -> Data? {
        do { return try BackupCodec.export(state, appVersion: "ios-" + HydraDevice.version) } catch {
            message = L("msg_backup_save_failed", error.localizedDescription); return nil
        }
    }

    func importBackup(_ data: Data) {
        guard !status.isUp else { message = L("msg_backup_disconnect_first"); return }
        do {
            let restored = try BackupCodec.import(data, into: state)
            mutate { $0 = restored }
            message = L("msg_backup_restored", restored.servers.count, restored.subscriptions.count, 0)
        } catch {
            message = L("msg_backup_restore_failed", error.localizedDescription)
        }
    }

    func resetSettings() {
        mutate { s in
            s.routing = RoutingSettings()
            let last = s.app.lastServerId
            s.app = AppSettings()
            s.app.lastServerId = last
        }
        message = L("msg_reset_done")
    }
}
