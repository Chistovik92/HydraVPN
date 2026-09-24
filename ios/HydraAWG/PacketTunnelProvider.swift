import Foundation
import HydraKit
import NetworkExtension
import WireGuardKit

/// AmneziaWG на iOS (Фаза 12) — отдельное расширение: amneziawg-go несёт свой Go-рантайм и не
/// уживается с Libbox в одном процессе (та же причина, по которой на Android Xray живёт в :xray).
/// Движок — WireGuardKit из официального amneziawg-apple; конфиг собирается из профиля Hydra
/// (те же ключи extra, что парсит WireGuardParser), все поколения протокола 1.0 – 3.x.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let store = HydraStore.shared()
    private lazy var adapter = WireGuardAdapter(with: self) { [weak self] _, message in self?.store.appendLog("AWG: \(message)") }

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let state = store.load()
        let id = (options?["serverId"] as? NSNumber)?.int64Value ?? state.app.lastServerId
        guard let server = state.servers.first(where: { $0.id == id }) ?? state.selectedServer,
              server.serverProtocol == .amneziaWG || server.serverProtocol == .wireguard else {
            completionHandler(fail("сервер AmneziaWG не выбран")); return
        }
        let config: TunnelConfiguration
        do { config = try Self.configuration(server) } catch { completionHandler(fail(error.localizedDescription)); return }
        store.appendLog("AmneziaWG: подключение к \(server.address):\(server.port)")
        adapter.start(tunnelConfiguration: config) { [weak self] error in
            if let error {
                completionHandler(self?.fail("AmneziaWG: \(error)"))
            } else {
                self?.store.appendLog("Подключено: \(ServerLocation.label(server))")
                completionHandler(nil)
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        store.appendLog("AmneziaWG: остановка (\(reason.rawValue))")
        adapter.stop { _ in completionHandler() }
    }

    /// Статистика для приложения: rx/tx из runtime-конфигурации wireguard-go.
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        adapter.getRuntimeConfiguration { settings in completionHandler?(settings?.data(using: .utf8)) }
    }

    private func fail(_ m: String) -> NSError {
        store.appendLog("Ошибка: \(m)")
        return NSError(domain: "HydraAWG", code: 1, userInfo: [NSLocalizedDescriptionKey: m])
    }

    enum ConfigError: Error, LocalizedError {
        case bad(String)
        var errorDescription: String? { if case .bad(let s) = self { return s }; return nil }
    }

    /// Профиль Hydra → TunnelConfiguration WireGuardKit.
    static func configuration(_ p: ServerProfile) throws -> TunnelConfiguration {
        let e = p.extraObject
        func s(_ k: String) -> String? {
            if let v = e[k] as? String, !v.isEmpty { return v }
            if let n = e[k] as? NSNumber { return n.stringValue }
            return nil
        }
        func u16(_ k: String) -> UInt16? { s(k).flatMap { UInt16($0) } }

        guard let key = PrivateKey(base64Key: p.uuidOrPassword) else { throw ConfigError.bad("неверный PrivateKey") }
        var iface = InterfaceConfiguration(privateKey: key)
        iface.addresses = (s("local_address") ?? "").split(separator: ",").compactMap { IPAddressRange(from: $0.trimmingCharacters(in: .whitespaces)) }
        iface.dns = (s("dns") ?? "1.1.1.1").split(separator: ",").compactMap { DNSServer(from: $0.trimmingCharacters(in: .whitespaces)) }
        iface.mtu = u16("mtu")
        iface.junkPacketCount = u16("jc")
        iface.junkPacketMinSize = u16("jmin")
        iface.junkPacketMaxSize = u16("jmax")
        iface.initPacketJunkSize = u16("s1")
        iface.responsePacketJunkSize = u16("s2")
        iface.cookieReplyPacketJunkSize = u16("s3")
        iface.transportPacketJunkSize = u16("s4")
        iface.initPacketMagicHeader = s("h1")
        iface.responsePacketMagicHeader = s("h2")
        iface.underloadPacketMagicHeader = s("h3")
        iface.transportPacketMagicHeader = s("h4")
        iface.specialJunk1 = s("i1"); iface.specialJunk2 = s("i2"); iface.specialJunk3 = s("i3")
        iface.specialJunk4 = s("i4"); iface.specialJunk5 = s("i5")
        iface.headerProtectionKey = s("headerprotectionkey").flatMap { PrivateKey(base64Key: $0) }
        iface.contentPaddingAddition = s("contentpaddingaddition")
        iface.rekeyAfterTime = s("rekeyaftertime")
        iface.rekeyTimeout = s("rekeytimeout")
        iface.rejectAfterTime = s("rejectaftertime")
        iface.keepaliveTimeout = s("keepalivetimeout")
        iface.maxHandshakeAttempts = s("maxhandshakeattempts")
        iface.randomTrailers = s("randomtrailers")
        iface.disableCookies = s("disablecookies")

        guard let pub = s("public_key").flatMap({ PublicKey(base64Key: $0) }) else { throw ConfigError.bad("неверный PublicKey пира") }
        var peer = PeerConfiguration(publicKey: pub)
        peer.preSharedKey = s("preshared_key").flatMap { PreSharedKey(base64Key: $0) }
        peer.allowedIPs = (s("allowed_ips") ?? "0.0.0.0/0, ::/0").split(separator: ",")
            .compactMap { IPAddressRange(from: $0.trimmingCharacters(in: .whitespaces)) }
        let host = p.address.contains(":") && !p.address.hasPrefix("[") ? "[\(p.address)]" : p.address
        peer.endpoint = Endpoint(from: "\(host):\(p.port)")
        peer.persistentKeepAlive = s("keepalive")
        return TunnelConfiguration(name: p.name, interface: iface, peers: [peer])
    }
}
