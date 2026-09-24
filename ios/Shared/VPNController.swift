import Foundation
import HydraKit
import NetworkExtension

/// Управление системной VPN-конфигурацией Hydra (NETunnelProviderManager) — общее для приложения
/// и виджета. Аналог запуска/остановки HydraVpnService на Android.
///  - Kill Switch → `includeAllNetworks` (система не выпускает трафик мимо туннеля, пока он включён);
///  - «Автоподключение» → правила On-Demand (подключаться всегда, когда есть сеть) — на iOS так
///    работает и автозапуск после перезагрузки, и переподключение после обрыва.
@MainActor
enum VPNController {
    static let tunnelBundleId = "ru.gidravpn.hydra.tunnel"

    static func load() async -> NETunnelProviderManager? {
        let all = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
        return all.first { ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == tunnelBundleId }
    }

    /// Создать или обновить конфигурацию под текущие настройки. При первом вызове iOS спрашивает
    /// разрешение «Добавить конфигурацию VPN» — аналог VPN-согласия на Android.
    @discardableResult
    static func install(_ state: HydraState) async throws -> NETunnelProviderManager {
        let m = await load() ?? NETunnelProviderManager()
        let proto = (m.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
        proto.providerBundleIdentifier = tunnelBundleId
        proto.serverAddress = state.selectedServer.map { ServerLocation.label($0) } ?? "Hydra"
        proto.includeAllNetworks = state.app.killSwitch
        // С Kill Switch локальная сеть (принтер, AirPlay) всё равно доступна — иначе iOS режет и её.
        proto.excludeLocalNetworks = true
        m.protocolConfiguration = proto
        m.localizedDescription = "Hydra"
        m.isEnabled = true
        if state.app.onDemand {
            let rule = NEOnDemandRuleConnect()
            rule.interfaceTypeMatch = .any
            m.onDemandRules = [rule]
            m.isOnDemandEnabled = true
        } else {
            m.onDemandRules = []
            m.isOnDemandEnabled = false
        }
        try await m.saveToPreferences()
        try await m.loadFromPreferences()
        return m
    }

    static func connect(_ state: HydraState) async throws {
        let m = try await install(state)
        var options: [String: NSObject] = [:]
        if let id = state.selectedServer?.id { options["serverId"] = NSNumber(value: id) }
        try m.connection.startVPNTunnel(options: options)
    }

    /// Отключить. При включённом On-Demand система подняла бы туннель снова, поэтому
    /// On-Demand на время ручного отключения выключается (как «Отключить» в приложении на Android).
    static func disconnect() async {
        guard let m = await load() else { return }
        if m.isOnDemandEnabled {
            m.isOnDemandEnabled = false
            try? await m.saveToPreferences()
        }
        m.connection.stopVPNTunnel()
    }

    static func status() async -> NEVPNStatus { await load()?.connection.status ?? .invalid }
}

extension NEVPNStatus {
    var isUp: Bool { self == .connected || self == .connecting || self == .reasserting }
}

/// Строка из Localizable.strings (ключи — те же, что в Android-ресурсах).
func L(_ key: String) -> String { NSLocalizedString(key, bundle: .main, comment: "") }
func L(_ key: String, _ args: CVarArg...) -> String { String(format: L(key), arguments: args) }
