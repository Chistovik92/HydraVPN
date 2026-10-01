import Foundation
import HydraKit
import Security
import SwiftUI
import UIKit

/// Токен устройства бота «Радар» — доступ к подпискам человека, поэтому в Keychain (только это устройство,
/// без iCloud), а не в App Group и не в резервной копии Hydra.
enum BotKeychain {
    private static let service = "ru.gidravpn.hydra.bot"
    private static let account = "token"

    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    static func token() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func save(_ token: String) {
        let data = Data(token.utf8)
        let update = [kSecValueData as String: data]
        if SecItemUpdate(query as CFDictionary, update as CFDictionary) == errSecItemNotFound {
            var add = query
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    static func clear() { SecItemDelete(query as CFDictionary) }
}

/// Приватный DoH Hydra VPN. Адрес с токеном — секрет и в репозитории его нет: сборка кладёт его в Info.plist
/// (`HydraPrivateDNS` ← переменная окружения HYDRA_PRIVATE_DNS при `xcodegen generate`). Пусто — пункт скрыт.
enum HydraPrivateDNS {
    static var url: String? {
        BotJSON.validPrivateDns(Bundle.main.object(forInfoDictionaryKey: "HydraPrivateDNS") as? String)
    }
}

/// Вход в аккаунт бота и синхронизация подписок — логика общая с Android и ПК (HydraKit/Bot.swift).
@MainActor
enum BotSync {
    private static let client = BotClient()
    private static let lastSyncKey = "bot.lastSync"

    static func text(_ e: BotError) -> String {
        switch e.code {
        case 0: L("bot_err_network")
        case 401: L("bot_err_auth")
        case 404: L("bot_err_off")
        case 429: L("bot_err_rate")
        default: e.text
        }
    }

    /// Приватный DNS нужен туннелю, а он читает только state.json — адрес кладём в настройки маршрутизации.
    static func applyPrivateDns(_ app: AppModel, linked: Bool) {
        let url = linked ? HydraPrivateDNS.url : nil
        guard app.state.routing.hydraDnsUrl != url || (url == nil && app.state.routing.dnsProvider == .hydra) else { return }
        app.mutate { s in
            s.routing.hydraDnsUrl = url
            if url == nil && s.routing.dnsProvider == .hydra { s.routing.dnsProvider = .cloudflare }
        }
        app.applyTunnelSettings(reconnect: false)
    }

    /// Код из бота («VPN» → «Подключить приложение») → токен устройства → первая синхронизация.
    static func link(_ app: AppModel, serverInput: String, code: String) async -> String {
        guard let server = BotJSON.normalizeServer(serverInput) else { return L("bot_err_server") }
        do {
            let token = try await client.link(server: server, code: code, device: UIDevice.current.name)
            BotKeychain.save(token)
            let p = try? await client.profile(server: server, token: token)
            app.mutate { $0.bot = BotAccountInfo(server: server, username: p?.username ?? "", panels: p?.panels) }
            applyPrivateDns(app, linked: true)
            return await sync(app)
        } catch let e as BotError {
            return text(e)
        } catch {
            return error.localizedDescription
        }
    }

    /// Заводит/обновляет подписки бота; возвращает текст для пользователя.
    static func sync(_ app: AppModel) async -> String {
        guard let info = app.state.bot, let token = BotKeychain.token() else { return "" }
        let items: [BotSubscription]
        do {
            items = try await client.subscriptions(server: info.server, token: token)
        } catch let e as BotError {
            // Токен отозван (устройство отключили в боте) — держать его дальше незачем.
            if e.code == 401 { forget(app) }
            return text(e)
        } catch {
            return error.localizedDescription
        }
        if let p = try? await client.profile(server: info.server, token: token) {
            app.mutate { s in
                s.bot?.panels = p.panels
                if !p.username.isEmpty { s.bot?.username = p.username }
            }
        }
        applyPrivateDns(app, linked: true)

        var subs = 0, failed = 0
        for step in BotSyncPlanner.plan(existing: app.state.subscriptions, items: items) {
            switch step {
            case .disable(let id):
                app.mutate { s in
                    if let i = s.subscriptions.firstIndex(where: { $0.id == id }) {
                        s.subscriptions[i].autoUpdate = false
                        s.subscriptions[i].lastError = BotSyncPlanner.disabledMark
                    }
                }
            case .add(let item):
                var id: Int64 = 0
                app.mutate { s in
                    id = s.nextSubscriptionId()
                    s.subscriptions.append(Subscription(id: id, name: item.title, url: item.url, botPanel: item.panel))
                }
                if await refreshed(app, id) { subs += 1 } else { failed += 1 }
            case .update(let id, let item):
                app.mutate { s in
                    if let i = s.subscriptions.firstIndex(where: { $0.id == id }) {
                        if s.subscriptions[i].lastError == BotSyncPlanner.disabledMark { s.subscriptions[i].autoUpdate = true }
                        s.subscriptions[i].url = item.url
                        s.subscriptions[i].botPanel = item.panel
                    }
                }
                if await refreshed(app, id) { subs += 1 } else { failed += 1 }
            }
        }
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: lastSyncKey)
        if !items.contains(where: \.importable) { return L("bot_sync_none") }
        return failed > 0 ? L("bot_sync_partial", subs, 0, failed) : L("bot_sync_ok", subs, app.state.servers.count)
    }

    private static func refreshed(_ app: AppModel, _ id: Int64) async -> Bool {
        await app.refreshNow(id)
        return app.state.subscriptions.first { $0.id == id }?.lastError.isEmpty == true
    }

    /// Отключить устройство в боте и забыть токен. Уже заведённые подписки остаются.
    static func unlink(_ app: AppModel) async {
        if let info = app.state.bot, let token = BotKeychain.token() { try? await client.logout(server: info.server, token: token) }
        forget(app)
    }

    private static func forget(_ app: AppModel) {
        BotKeychain.clear()
        app.mutate { $0.bot = nil }
        applyPrivateDns(app, linked: false)
    }

    /// Раз в 6 часов при выходе приложения на передний план — как BotSyncWorker на Android.
    static func syncIfDue(_ app: AppModel) async {
        guard app.state.bot != nil else { return }
        let last = UserDefaults.standard.double(forKey: lastSyncKey)
        if Date().timeIntervalSince1970 - last < 6 * 3600 { return }
        _ = await sync(app)
    }
}

/// «Аккаунт бота» в Профиле: вход по коду и забор выданных там подписок.
struct BotAccountSection: View {
    @EnvironmentObject var model: AppModel
    @State private var server = ""
    @State private var code = ""
    @State private var busy = false
    @State private var result: String?

    var body: some View {
        Section(header: Text(L("bot_title"))) {
            if let info = model.state.bot {
                Text(info.username.isEmpty ? L("bot_linked") : L("bot_linked_as", info.username)).font(.subheadline.weight(.semibold))
                Text(info.server).font(.caption).foregroundStyle(Color.hydraMuted)
                if let panels = info.panels { Text(L("bot_panels", panels)).font(.caption).foregroundStyle(Color.hydraMuted) }
                Button(L("bot_sync")) { run { await BotSync.sync(model) } }.disabled(busy)
                Button(L("bot_unlink"), role: .destructive) {
                    run { await BotSync.unlink(model); return L("bot_unlinked") }
                }.disabled(busy)
            } else {
                Text(L("bot_hint")).font(.footnote).foregroundStyle(Color.hydraMuted)
                TextField(L("bot_server"), text: $server)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                TextField(L("bot_code"), text: $code)
                    .keyboardType(.numberPad)
                    .onChange(of: code) { _, v in code = String(v.filter(\.isNumber).prefix(8)) }
                Button(L("bot_connect")) {
                    let (s, c) = (server, code)
                    code = ""
                    run { await BotSync.link(model, serverInput: s, code: c) }
                }.disabled(busy || server.isEmpty || code.count != 8)
            }
            if busy { ProgressView() }
            if let result { Text(result).font(.footnote).foregroundStyle(Color.hydraMuted) }
        }
    }

    private func run(_ work: @escaping () async -> String) {
        busy = true; result = nil
        Task {
            let text = await work()
            result = text.isEmpty ? nil : text
            busy = false
        }
    }
}

/// Статус аккаунта на главном: срок и трафик подписок, выданных ботом. Нет таких подписок — ничего.
struct AccountStatusCard: View {
    @EnvironmentObject var model: AppModel
    var theme: HydraTheme { HydraTheme(rawValue: model.state.app.theme) ?? .ambient }

    var body: some View {
        let subs = model.state.subscriptions.filter { ($0.botPanel ?? "") != "" }
        if !subs.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(L("main_account").uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(Color.hydraMuted)
                ForEach(subs) { sub in
                    HStack {
                        Text(sub.displayName).font(.subheadline.weight(.semibold)).lineLimit(1)
                        Spacer()
                        if sub.expireAt > 0 {
                            let end = Date(timeIntervalSince1970: TimeInterval(sub.expireAt))
                            Text(L("sub_until", end.formatted(date: .abbreviated, time: .omitted)))
                                .font(.caption).foregroundStyle(end < Date() ? Color.hydraDanger : Color.hydraMuted)
                        }
                    }
                    if sub.totalBytes > 0 {
                        ProgressView(value: min(1, Double(sub.usedBytes) / Double(sub.totalBytes)))
                            .tint(sub.usedBytes * 10 > sub.totalBytes * 9 ? Color.hydraDanger : theme.accent)
                        Text("\(Format.bytes(sub.usedBytes)) / \(Format.bytes(sub.totalBytes))").font(.caption).foregroundStyle(Color.hydraMuted)
                    } else if sub.usedBytes > 0 {
                        Text(Format.bytes(sub.usedBytes)).font(.caption).foregroundStyle(Color.hydraMuted)
                    }
                    if !sub.lastError.isEmpty { Text(sub.lastError).font(.caption).foregroundStyle(Color.hydraDanger) }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(theme.card, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.08)))
        }
    }
}
