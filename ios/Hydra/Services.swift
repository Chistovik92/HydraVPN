import CryptoKit
import Foundation
import HydraKit
import Libbox
import Network
import UIKit

/// Как клиент представляется панели подписки — аналог `HydraDevice` на Android: тот же формат
/// HWID (SHA-256 от идентификатора устройства и bundle id, оформленный как UUID), UA и x-заголовки.
enum HydraDevice {
    static var hwid: String {
        let seed = UIDevice.current.identifierForVendor?.uuidString ?? "unknown"
        let digest = SHA256.hash(data: Data("ru.gidravpn.hydra:\(seed)".utf8))
        let hex = digest.prefix(16).map { String(format: "%02X", $0) }.joined()
        let c = Array(hex)
        return "\(String(c[0..<8]))-\(String(c[8..<12]))-\(String(c[12..<16]))-\(String(c[16..<20]))-\(String(c[20..<32]))"
    }

    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0" }

    static var headers: [String: String] {
        let model = UIDevice.current.model
        let os = UIDevice.current.systemVersion
        return [
            "User-Agent": "Hydra/\(version)/ios CFNetwork (iOS \(os); \(model))",
            "x-hwid": hwid,
            "x-device-os": "iOS",
            "x-ver-os": os,
            "x-device-model": model,
            "Accept": "*/*",
        ]
    }
}

/// Задержка до сервера — время TCP-connect (как PingMeasurer на Android). -1 — таймаут/ошибка.
enum Ping {
    static func measure(host: String, port: Int, timeout: TimeInterval = 3) async -> Int {
        guard let p = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return -1 }
        let conn = NWConnection(host: NWEndpoint.Host(host), port: p, using: .tcp)
        let start = Date()
        return await withCheckedContinuation { cont in
            let done = OnceFlag()
            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if done.set() { cont.resume(returning: Int(Date().timeIntervalSince(start) * 1000)); conn.cancel() }
                case .failed, .cancelled:
                    if done.set() { cont.resume(returning: -1) }
                default: break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                if done.set() { cont.resume(returning: -1); conn.cancel() }
            }
        }
    }

    private final class OnceFlag: @unchecked Sendable {
        private var fired = false
        private let lock = NSLock()
        func set() -> Bool { lock.lock(); defer { lock.unlock() }; if fired { return false }; fired = true; return true }
    }
}

/// Счётчики трафика из работающего туннеля: командный сервер libbox слушает сокет в App Group,
/// приложение подключается к нему как клиент (так же делает официальный клиент sing-box).
@MainActor
final class TrafficClient: ObservableObject {
    @Published var up: Int64 = 0
    @Published var down: Int64 = 0
    private var client: LibboxCommandClient?

    static func setupLibbox() {
        let store = HydraStore.shared()
        let o = LibboxSetupOptions()
        o.basePath = store.directory.path
        o.workingPath = store.directory.appendingPathComponent("work", isDirectory: true).path
        o.tempPath = FileManager.default.temporaryDirectory.path
        var error: NSError?
        LibboxSetup(o, &error)
    }

    func connect() {
        guard client == nil else { return }
        let options = LibboxCommandClientOptions()
        options.command = LibboxCommandStatus
        options.statusInterval = Int64(NSEC_PER_SEC)
        guard let c = LibboxNewCommandClient(Handler(self), options) else { return }
        Task.detached {
            for attempt in 0..<10 {
                try? await Task.sleep(nanoseconds: UInt64(150 + attempt * 50) * NSEC_PER_MSEC)
                if (try? c.connect()) != nil {
                    await MainActor.run { self.client = c }
                    return
                }
            }
        }
    }

    func disconnect() {
        try? client?.disconnect()
        client = nil
        up = 0; down = 0
    }

    private final class Handler: NSObject, LibboxCommandClientHandlerProtocol {
        weak var owner: TrafficClient?
        init(_ owner: TrafficClient) { self.owner = owner }
        func connected() {}
        func disconnected(_: String?) {}
        func clearLogs() {}
        func writeLogs(_: (any LibboxStringIteratorProtocol)?) {}
        func writeStatus(_ message: LibboxStatusMessage?) {
            guard let message else { return }
            let up = message.uplinkTotal, down = message.downlinkTotal
            Task { @MainActor [weak owner] in owner?.up = up; owner?.down = down }
        }
        func writeGroups(_: LibboxOutboundGroupIteratorProtocol?) {}
        func initializeClashMode(_: LibboxStringIteratorProtocol?, currentMode _: String?) {}
        func updateClashMode(_: String?) {}
        func write(_: LibboxConnections?) {}
    }
}
