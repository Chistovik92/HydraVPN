import Foundation
import HydraKit
import NetworkExtension

/// Управление системной VPN-конфигурацией Hydra (NETunnelProviderManager) — общее для приложения
/// и виджета. Аналог запуска/остановки HydraVpnService на Android.
///  - два расширения: sing-box (все прокси-протоколы и WireGuard) и AmneziaWG (свой Go-рантайм) —
///    у каждого своя конфигурация; активна та, чей протокол у выбранного сервера;
///  - Kill Switch → `includeAllNetworks` (система не выпускает трафик мимо туннеля);
///  - «Автоподключение» → правила On-Demand (после перезагрузки, обрыва, смены сети).
@MainActor
enum VPNController {
    static let singBoxBundleId = "ru.gidravpn.hydra.tunnel"
    static let awgBundleId = "ru.gidravpn.hydra.awg"

    static func bundleId(for server: ServerProfile?) -> String {
        server?.serverProtocol?.engine == .amneziaWG ? awgBundleId : singBoxBundleId
    }

    static func all() async -> [NETunnelProviderManager] {
        ((try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []).filter {
            let id = ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier
            return id == singBoxBundleId || id == awgBundleId
        }
    }

    /// Текущая конфигурация: поднятая, иначе включённая, иначе любая.
    static func load() async -> NETunnelProviderManager? {
        let list = await all()
        return list.first { $0.connection.status.isUp } ?? list.first { $0.isEnabled } ?? list.first
    }

    /// Создать или обновить конфигурацию под выбранный сервер. При первом вызове iOS спрашивает
    /// разрешение «Добавить конфигурацию VPN» — аналог VPN-согласия на Android.
    @discardableResult
    static func install(_ state: HydraState) async throws -> NETunnelProviderManager {
        let bundle = bundleId(for: state.selectedServer)
        let list = await all()
        let m = list.first { ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == bundle }
            ?? NETunnelProviderManager()
        let proto = (m.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
        proto.providerBundleIdentifier = bundle
        proto.serverAddress = state.selectedServer.map { ServerLocation.label($0) } ?? "Hydra"
        proto.includeAllNetworks = state.app.killSwitch
        proto.excludeLocalNetworks = true
        m.protocolConfiguration = proto
        m.localizedDescription = bundle == awgBundleId ? "Hydra AmneziaWG" : "Hydra"
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
        // Вторая конфигурация не должна перехватывать On-Demand.
        for other in list where other !== m && other.isOnDemandEnabled {
            other.isOnDemandEnabled = false
            try? await other.saveToPreferences()
        }
        return m
    }

    static func connect(_ state: HydraState) async throws {
        // Если поднят туннель другого движка — сначала опустить его.
        let target = bundleId(for: state.selectedServer)
        for m in await all() where m.connection.status.isUp &&
            (m.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier != target {
            m.connection.stopVPNTunnel()
        }
        let m = try await install(state)
        var options: [String: NSObject] = [:]
        if let id = state.selectedServer?.id { options["serverId"] = NSNumber(value: id) }
        try m.connection.startVPNTunnel(options: options)
    }

    /// Отключить. При включённом On-Demand система подняла бы туннель снова, поэтому
    /// On-Demand на время ручного отключения выключается (как «Отключить» на Android).
    static func disconnect() async {
        for m in await all() {
            if m.isOnDemandEnabled {
                m.isOnDemandEnabled = false
                try? await m.saveToPreferences()
            }
            if m.connection.status != .disconnected { m.connection.stopVPNTunnel() }
        }
    }

    static func status() async -> NEVPNStatus { await load()?.connection.status ?? .invalid }
}

extension NEVPNStatus {
    var isUp: Bool { self == .connected || self == .connecting || self == .reasserting }
}

/// Строка из Localizable.strings (ключи — те же, что в Android-ресурсах).
func L(_ key: String) -> String { NSLocalizedString(key, bundle: .main, comment: "") }
func L(_ key: String, _ args: CVarArg...) -> String { String(format: L(key), arguments: args) }
