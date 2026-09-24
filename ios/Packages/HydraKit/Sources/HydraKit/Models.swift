import Foundation

/// Движок, который обслуживает протокол на iOS. В отличие от Android, всё, что работает,
/// идёт через одно ядро sing-box в Network Extension: второй Go-рантайм (Xray, amneziawg-go)
/// в том же процессе не уживается, а подпроцессы (olcRTC, OpenFlux) iOS запрещает.
public enum Engine: String, Codable, Sendable {
    case singBox
    /// AmneziaWG — отдельное расширение на amneziawg-go (второй Go-рантайм с Libbox не уживается).
    case amneziaWG
    /// Есть на Android, на iOS пока нет — показываем честно, с причиной.
    case notYetOnIOS
    /// Невозможно на iOS в принципе (подпроцессы, GRE и т. п.).
    case unavailable
}

/// Семейства протоколов — те же id, что на Android (`Protocol.id`): ссылки, подписки и
/// резервные копии совместимы между платформами.
public enum ServerProtocol: String, Codable, CaseIterable, Sendable {
    case sstp, l2tp, pptp, vless, vmess, trojan
    case shadowsocks = "ss"
    case hysteria2, tuic, wireguard
    case amneziaWG = "awg"
    case wdtt, olcrtc, openflux

    public var displayName: String {
        switch self {
        case .sstp: "SSTP (TLS/PPP)"
        case .l2tp: "L2TP (PPP/UDP)"
        case .pptp: "PPTP (N/A)"
        case .vless: "VLESS"
        case .vmess: "VMess"
        case .trojan: "Trojan"
        case .shadowsocks: "Shadowsocks"
        case .hysteria2: "Hysteria2"
        case .tuic: "TUIC v5"
        case .wireguard: "WireGuard"
        case .amneziaWG: "AmneziaWG"
        case .wdtt: "WDTT"
        case .olcrtc: "olcRTC"
        case .openflux: "OpenFlux"
        }
    }

    public var shortCode: String {
        switch self {
        case .vless: "VL"
        case .vmess: "VM"
        case .trojan: "TR"
        case .shadowsocks: "SS"
        case .hysteria2: "HY2"
        case .tuic: "TUIC"
        case .wireguard: "WG"
        case .amneziaWG: "AWG"
        case .openflux: "OFX"
        default: String(rawValue.prefix(3)).uppercased()
        }
    }

    /// Протокол можно поднять на iOS (sing-box или AmneziaWG).
    public var supportedOnIOS: Bool { engine == .singBox || engine == .amneziaWG }

    public var engine: Engine {
        switch self {
        case .vless, .vmess, .trojan, .shadowsocks, .hysteria2, .tuic, .wireguard: .singBox
        case .amneziaWG: .amneziaWG
        // SSTP/L2TP: PPP-стек перенесён (HydraKit/PPP), транспорт MS-SSTP ждёт исправления и проверки на
        // живом сервере — Android-реализация расходится со спецификацией (см. docs/ROADMAP.md, 0.6.25).
        case .sstp, .l2tp: .notYetOnIOS
        case .pptp, .wdtt, .olcrtc, .openflux: .unavailable
        }
    }

    public static func fromScheme(_ scheme: String) -> ServerProtocol? {
        switch scheme.lowercased() {
        case "sstp": .sstp
        case "l2tp": .l2tp
        case "pptp": .pptp
        case "vless": .vless
        case "vmess": .vmess
        case "trojan": .trojan
        case "ss", "shadowsocks": .shadowsocks
        case "hysteria2", "hy2": .hysteria2
        case "tuic": .tuic
        case "wireguard", "wg": .wireguard
        case "awg", "amnezia": .amneziaWG
        case "wdtt": .wdtt
        case "olcrtc": .olcrtc
        case "openflux": .openflux
        default: nil
        }
    }
}

/// Профиль сервера — поле в поле как `ServerProfile` на Android (Room-сущность), чтобы
/// резервная копия переносилась между платформами без преобразований.
public struct ServerProfile: Codable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var name: String
    public var protocolId: String
    public var address: String
    public var port: Int
    public var uuidOrPassword: String
    public var flow: String
    public var sni: String
    public var transport: String
    public var transportPath: String
    public var security: String
    public var alpn: String
    public var fingerprint: String
    /// JSON-объект с полями протокола (Reality pbk/sid, obfs, WireGuard и т. п.).
    public var extra: String
    public var subscriptionId: Int64?
    public var pingMs: Int
    public var flag: String

    public init(
        id: Int64 = 0, name: String, protocolId: String, address: String, port: Int,
        uuidOrPassword: String = "", flow: String = "", sni: String = "", transport: String = "tcp",
        transportPath: String = "", security: String = "none", alpn: String = "", fingerprint: String = "chrome",
        extra: String = "{}", subscriptionId: Int64? = nil, pingMs: Int = -1, flag: String = "🌐"
    ) {
        self.id = id; self.name = name; self.protocolId = protocolId; self.address = address; self.port = port
        self.uuidOrPassword = uuidOrPassword; self.flow = flow; self.sni = sni; self.transport = transport
        self.transportPath = transportPath; self.security = security; self.alpn = alpn
        self.fingerprint = fingerprint; self.extra = extra; self.subscriptionId = subscriptionId
        self.pingMs = pingMs; self.flag = flag
    }

    public var serverProtocol: ServerProtocol? { ServerProtocol(rawValue: protocolId) }

    /// Поля `extra` как словарь (пустой, если JSON битый).
    public var extraObject: [String: Any] {
        guard let data = extra.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return obj
    }
}

/// Подписка — как `Subscription` на Android.
public struct Subscription: Codable, Identifiable, Hashable, Sendable {
    public var id: Int64
    public var name: String
    public var url: String
    public var userAgent: String
    public var lastUpdated: Int64
    public var autoUpdateHours: Int
    public var serverTitle: String
    public var uploadBytes: Int64
    public var downloadBytes: Int64
    public var totalBytes: Int64
    public var expireAt: Int64
    public var supportUrl: String
    public var autoUpdate: Bool
    public var collapsed: Bool
    public var lastError: String

    public init(
        id: Int64 = 0, name: String, url: String, userAgent: String = "", lastUpdated: Int64 = 0,
        autoUpdateHours: Int = 12, serverTitle: String = "", uploadBytes: Int64 = 0, downloadBytes: Int64 = 0,
        totalBytes: Int64 = 0, expireAt: Int64 = 0, supportUrl: String = "", autoUpdate: Bool = true,
        collapsed: Bool = false, lastError: String = ""
    ) {
        self.id = id; self.name = name; self.url = url; self.userAgent = userAgent
        self.lastUpdated = lastUpdated; self.autoUpdateHours = autoUpdateHours; self.serverTitle = serverTitle
        self.uploadBytes = uploadBytes; self.downloadBytes = downloadBytes; self.totalBytes = totalBytes
        self.expireAt = expireAt; self.supportUrl = supportUrl; self.autoUpdate = autoUpdate
        self.collapsed = collapsed; self.lastError = lastError
    }

    public var displayName: String { serverTitle.isEmpty ? name : serverTitle }
    public var usedBytes: Int64 { uploadBytes + downloadBytes }
}

// MARK: - Настройки маршрутизации (как на Android, те же имена значений)

public enum SplitMode: String, Codable, CaseIterable, Sendable { case off = "OFF", include = "INCLUDE", exclude = "EXCLUDE" }

public enum NetRuleType: String, Codable, CaseIterable, Sendable {
    case ipCidr = "IP_CIDR", domain = "DOMAIN", domainSuffix = "DOMAIN_SUFFIX", domainKeyword = "DOMAIN_KEYWORD"
    public var singBoxKey: String {
        switch self {
        case .ipCidr: "ip_cidr"
        case .domain: "domain"
        case .domainSuffix: "domain_suffix"
        case .domainKeyword: "domain_keyword"
        }
    }
}

public struct NetworkRule: Codable, Hashable, Sendable {
    public var type: NetRuleType
    public var value: String
    public init(type: NetRuleType, value: String) { self.type = type; self.value = value }
}

public enum GeoRoutingMode: String, Codable, CaseIterable, Sendable { case off = "OFF", direct = "DIRECT", viaProxy = "VIA_PROXY" }
public enum TlsFragmentMode: String, Codable, CaseIterable, Sendable { case off = "OFF", record = "RECORD", tcp = "TCP" }
public enum Ipv6Mode: String, Codable, CaseIterable, Sendable { case block = "BLOCK", enable = "ENABLE" }

public enum MtuPreset: String, Codable, CaseIterable, Sendable {
    case auto = "AUTO", mtu1500 = "MTU_1500", mtu1400 = "MTU_1400", mtu1280 = "MTU_1280"
    public var value: Int {
        switch self {
        case .auto: 9000
        case .mtu1500: 1500
        case .mtu1400: 1400
        case .mtu1280: 1280
        }
    }
}

public enum DnsProvider: String, Codable, CaseIterable, Sendable {
    case cloudflare = "CLOUDFLARE", google = "GOOGLE", quad9 = "QUAD9", adguard = "ADGUARD"
    case system = "SYSTEM", custom = "CUSTOM"
    public var address: String? {
        switch self {
        case .cloudflare: "1.1.1.1"
        case .google: "8.8.8.8"
        case .quad9: "9.9.9.9"
        case .adguard: "94.140.14.14"
        case .system, .custom: nil
        }
    }
    public var label: String {
        switch self {
        case .cloudflare: "Cloudflare"
        case .google: "Google"
        case .quad9: "Quad9"
        case .adguard: "AdGuard"
        case .system: "System"
        case .custom: "Custom"
        }
    }
}

/// Всё, что влияет на маршрутизацию, — одна структура. Её же сохраняют профили маршрутизации.
public struct RoutingSettings: Codable, Hashable, Sendable {
    public var dnsProvider: DnsProvider = .cloudflare
    public var dnsCustomAddress: String = ""
    public var geoMode: GeoRoutingMode = .off
    public var geoCountries: [String] = ["ru"]
    public var mtu: MtuPreset = .auto
    public var tlsFragment: TlsFragmentMode = .off
    public var ipv6: Ipv6Mode = .block
    public var netMode: SplitMode = .off
    public var netRules: [NetworkRule] = []
    public init() {}

    public var netActive: Bool { netMode != .off && !netRules.isEmpty }

    /// Действующий DNS: nil — системный резолвер. Невалидный свой адрес → Cloudflare.
    public func resolvedDns() -> DnsEndpoint? {
        switch dnsProvider {
        case .system: return nil
        case .custom: return DnsEndpoint.parse(dnsCustomAddress) ?? .doh("1.1.1.1")
        default: return .doh(dnsProvider.address ?? "1.1.1.1")
        }
    }
}

/// Настройки безопасности и поведения — аналог vpn_settings на Android.
public struct AppSettings: Codable, Hashable, Sendable {
    /// Kill Switch на iOS — `includeAllNetworks` в конфигурации туннеля.
    public var killSwitch: Bool = false
    /// Автоподключение/переподключение — правила On-Demand системы.
    public var onDemand: Bool = false
    public var appLock: Bool = false
    public var hideSecrets: Bool = true
    public var lastServerId: Int64?
    public var theme: String = "AMBIENT"
    public var hotspotEnabled: Bool = false
    public var hotspotPort: Int = 10808
    public var hotspotUser: String = "hydra"
    public var hotspotPassword: String = ""
    public init() {}
}

/// Состояние, которое хранится в App Group и читается приложением, туннелем и виджетом.
public struct HydraState: Codable, Sendable {
    public var servers: [ServerProfile] = []
    public var subscriptions: [Subscription] = []
    public var routing = RoutingSettings()
    public var app = AppSettings()
    public var routingProfiles: [RoutingProfile] = []
    public init() {}

    public var selectedServer: ServerProfile? {
        servers.first { $0.id == app.lastServerId } ?? servers.first
    }

    public mutating func nextServerId() -> Int64 { (servers.map(\.id).max() ?? 0) + 1 }
    public mutating func nextSubscriptionId() -> Int64 { (subscriptions.map(\.id).max() ?? 0) + 1 }
}

public struct RoutingProfile: Codable, Hashable, Identifiable, Sendable {
    public var name: String
    public var routing: RoutingSettings
    public var id: String { name }
    public init(name: String, routing: RoutingSettings) { self.name = name; self.routing = routing }
}
