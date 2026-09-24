import Foundation
import Libbox
import Network
import NetworkExtension

/// PlatformInterface libbox 1.12.x для iOS — аналог HydraPlatformInterface на Android.
/// Сверено с sing-box-for-apple (ExtensionPlatformInterface, октябрь 2025 — та же ветка libbox).
/// Главное: openTun превращает маршруты, которые вычислил sing-box, в NEPacketTunnelNetworkSettings
/// и отдаёт ядру fd utun-интерфейса.
final class TunnelPlatformInterface: NSObject, LibboxPlatformInterfaceProtocol, LibboxCommandServerHandlerProtocol {
    private unowned let tunnel: PacketTunnelProvider
    private var networkSettings: NEPacketTunnelNetworkSettings?
    private var monitor: NWPathMonitor?

    init(_ tunnel: PacketTunnelProvider) { self.tunnel = tunnel }

    func reset() { networkSettings = nil }

    // MARK: - tun

    func openTun(_ options: LibboxTunOptionsProtocol?, ret0_: UnsafeMutablePointer<Int32>?) throws {
        try runBlocking { [self] in try await openTun0(options, ret0_) }
    }

    private func openTun0(_ options: LibboxTunOptionsProtocol?, _ ret0_: UnsafeMutablePointer<Int32>?) async throws {
        guard let options, let ret0_ else { throw NSError(domain: "Hydra", code: 10, userInfo: [NSLocalizedDescriptionKey: "openTun: nil"]) }
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        if options.getAutoRoute() {
            settings.mtu = NSNumber(value: options.getMTU())
            let dnsServer = try options.getDNSServerAddress()
            let dns = NEDNSSettings(servers: [dnsServer.value])
            dns.matchDomains = [""]
            dns.matchDomainsNoSearch = true
            settings.dnsSettings = dns

            var v4Addr: [String] = [], v4Mask: [String] = []
            let a4 = options.getInet4Address()!
            while a4.hasNext() { let p = a4.next()!; v4Addr.append(p.address()); v4Mask.append(p.mask()) }
            let v4 = NEIPv4Settings(addresses: v4Addr, subnetMasks: v4Mask)
            var v4Routes: [NEIPv4Route] = [], v4Exclude: [NEIPv4Route] = []
            let r4 = options.getInet4RouteAddress()!
            if r4.hasNext() {
                while r4.hasNext() { let p = r4.next()!; v4Routes.append(NEIPv4Route(destinationAddress: p.address(), subnetMask: p.mask())) }
            } else {
                v4Routes.append(NEIPv4Route.default())
            }
            let x4 = options.getInet4RouteExcludeAddress()!
            while x4.hasNext() { let p = x4.next()!; v4Exclude.append(NEIPv4Route(destinationAddress: p.address(), subnetMask: p.mask())) }
            v4.includedRoutes = v4Routes
            v4.excludedRoutes = v4Exclude
            settings.ipv4Settings = v4

            var v6Addr: [String] = [], v6Prefix: [NSNumber] = []
            let a6 = options.getInet6Address()!
            while a6.hasNext() { let p = a6.next()!; v6Addr.append(p.address()); v6Prefix.append(NSNumber(value: p.prefix())) }
            if !v6Addr.isEmpty {
                let v6 = NEIPv6Settings(addresses: v6Addr, networkPrefixLengths: v6Prefix)
                var v6Routes: [NEIPv6Route] = [], v6Exclude: [NEIPv6Route] = []
                let r6 = options.getInet6RouteAddress()!
                if r6.hasNext() {
                    while r6.hasNext() {
                        let p = r6.next()!
                        v6Routes.append(NEIPv6Route(destinationAddress: p.address(), networkPrefixLength: NSNumber(value: p.prefix())))
                    }
                } else {
                    v6Routes.append(NEIPv6Route.default())
                }
                let x6 = options.getInet6RouteExcludeAddress()!
                while x6.hasNext() {
                    let p = x6.next()!
                    v6Exclude.append(NEIPv6Route(destinationAddress: p.address(), networkPrefixLength: NSNumber(value: p.prefix())))
                }
                v6.includedRoutes = v6Routes
                v6.excludedRoutes = v6Exclude
                settings.ipv6Settings = v6
            }
        }
        networkSettings = settings
        try await tunnel.setTunnelNetworkSettings(settings)

        if let fd = tunnel.packetFlow.value(forKeyPath: "socket.fileDescriptor") as? Int32 {
            ret0_.pointee = fd
            return
        }
        let fd = LibboxGetTunnelFileDescriptor()
        guard fd != -1 else { throw NSError(domain: "Hydra", code: 11, userInfo: [NSLocalizedDescriptionKey: "нет fd туннеля"]) }
        ret0_.pointee = fd
    }

    // MARK: - сеть

    func startDefaultInterfaceMonitor(_ listener: LibboxInterfaceUpdateListenerProtocol?) throws {
        guard let listener else { return }
        let m = NWPathMonitor()
        monitor = m
        let first = DispatchSemaphore(value: 0)
        m.pathUpdateHandler = { [weak self] path in
            self?.update(listener, path)
            first.signal()
            m.pathUpdateHandler = { [weak self] path in self?.update(listener, path) }
        }
        m.start(queue: DispatchQueue.global())
        first.wait()
    }

    private func update(_ listener: LibboxInterfaceUpdateListenerProtocol, _ path: Network.NWPath) {
        guard path.status != .unsatisfied, let iface = path.availableInterfaces.first else {
            listener.updateDefaultInterface("", interfaceIndex: -1, isExpensive: false, isConstrained: false)
            return
        }
        listener.updateDefaultInterface(iface.name, interfaceIndex: Int32(iface.index),
                                        isExpensive: path.isExpensive, isConstrained: path.isConstrained)
    }

    func closeDefaultInterfaceMonitor(_: LibboxInterfaceUpdateListenerProtocol?) throws {
        monitor?.cancel()
        monitor = nil
    }

    func getInterfaces() throws -> LibboxNetworkInterfaceIteratorProtocol {
        guard let monitor else { throw NSError(domain: "Hydra", code: 12, userInfo: [NSLocalizedDescriptionKey: "монитор сети не запущен"]) }
        let path = monitor.currentPath
        guard path.status != .unsatisfied else { return InterfaceIterator([]) }
        return InterfaceIterator(path.availableInterfaces.map { it in
            let n = LibboxNetworkInterface()
            n.name = it.name
            n.index = Int32(it.index)
            switch it.type {
            case .wifi: n.type = LibboxInterfaceTypeWIFI
            case .cellular: n.type = LibboxInterfaceTypeCellular
            case .wiredEthernet: n.type = LibboxInterfaceTypeEthernet
            default: n.type = LibboxInterfaceTypeOther
            }
            return n
        })
    }

    final class InterfaceIterator: NSObject, LibboxNetworkInterfaceIteratorProtocol {
        private var it: IndexingIterator<[LibboxNetworkInterface]>
        private var nextValue: LibboxNetworkInterface?
        init(_ a: [LibboxNetworkInterface]) { it = a.makeIterator() }
        func hasNext() -> Bool { nextValue = it.next(); return nextValue != nil }
        func next() -> LibboxNetworkInterface? { nextValue }
    }

    func underNetworkExtension() -> Bool { true }
    /// Kill Switch на iOS — includeAllNetworks в конфигурации туннеля (его ставит приложение).
    func includeAllNetworks() -> Bool { HydraKitBridge.killSwitch }
    func usePlatformAutoDetectControl() -> Bool { false }
    func autoDetectControl(_: Int32) throws {}
    func useProcFS() -> Bool { false }

    func findConnectionOwner(_: Int32, sourceAddress _: String?, sourcePort _: Int32, destinationAddress _: String?,
                             destinationPort _: Int32, ret0_ _: UnsafeMutablePointer<Int32>?) throws {
        throw NSError(domain: "Hydra", code: 13, userInfo: [NSLocalizedDescriptionKey: "not supported on iOS"])
    }
    func packageName(byUid _: Int32, error _: NSErrorPointer) -> String { "" }
    func uid(byPackageName _: String?, ret0_ _: UnsafeMutablePointer<Int32>?) throws {
        throw NSError(domain: "Hydra", code: 14, userInfo: [NSLocalizedDescriptionKey: "not supported on iOS"])
    }

    func writeLog(_ message: String?) {
        guard let message, !message.isEmpty else { return }
        tunnel.log(message)
    }

    func clearDNSCache() {
        guard let networkSettings else { return }
        tunnel.reasserting = true
        tunnel.setTunnelNetworkSettings(nil) { _ in }
        tunnel.setTunnelNetworkSettings(networkSettings) { _ in }
        tunnel.reasserting = false
    }

    func readWIFIState() -> LibboxWIFIState? { nil }
    func send(_: LibboxNotification?) throws {}
    func localDNSTransport() -> (any LibboxLocalDNSTransportProtocol)? { nil }
    func systemCertificates() -> (any LibboxStringIteratorProtocol)? { nil }

    // MARK: - CommandServerHandler

    func serviceReload() throws {}
    func postServiceClose() { tunnel.serviceClosed() }
    func getSystemProxyStatus() -> LibboxSystemProxyStatus? { LibboxSystemProxyStatus() }
    func setSystemProxyEnabled(_: Bool) throws {}
}

/// Чтение настроек из App Group для синхронных колбэков libbox.
enum HydraKitBridge {
    static var killSwitch: Bool { HydraStoreAccess.state().app.killSwitch }
}

enum HydraStoreAccess {
    static func state() -> HydraKit.HydraState { HydraKit.HydraStore.shared().load() }
}

/// async → sync для колбэков libbox, которые вызываются из Go синхронно.
func runBlocking<T>(_ block: @escaping () async throws -> T) throws -> T {
    let semaphore = DispatchSemaphore(value: 0)
    let box = ResultBox<T>()
    Task.detached {
        do { box.result = .success(try await block()) } catch { box.result = .failure(error) }
        semaphore.signal()
    }
    semaphore.wait()
    return try box.result!.get()
}

private final class ResultBox<T>: @unchecked Sendable {
    var result: Result<T, Error>?
}
