import NetworkExtension

class PacketTunnelProvider: NEPacketTunnelProvider {
    
    private var singBox: SingBoxBridge?
    
    override func startTunnel(options: [String : NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        guard let protocolConfiguration = self.protocolConfiguration as? NETunnelProviderProtocol,
              let providerConfiguration = protocolConfiguration.providerConfiguration else {
            completionHandler(NSError(domain: "HydraVPN", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid configuration"]))
            return
        }
        
        guard let serverAddress = providerConfiguration["serverAddress"] as? String,
              let serverPort = providerConfiguration["serverPort"] as? Int else {
            completionHandler(NSError(domain: "HydraVPN", code: 2, userInfo: [NSLocalizedDescriptionKey: "Missing server configuration"]))
            return
        }
        
        // Configure tunnel settings
        let tunnelNetworkSettings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: serverAddress)
        
        // IPv4 settings
        let ipv4Settings = NEIPv4Settings(addresses: ["172.19.0.2"], subnetMasks: ["255.255.255.0"])
        ipv4Settings.includedRoutes = [NEIPv4Route.default()]
        tunnelNetworkSettings.ipv4Settings = ipv4Settings
        
        // DNS settings
        let dnsSettings = NEDNSSettings(servers: ["1.1.1.1"])
        tunnelNetworkSettings.dnsSettings = dnsSettings
        
        // MTU
        tunnelNetworkSettings.mtu = NSNumber(value: 1408)
        
        setTunnelNetworkSettings(tunnelNetworkSettings) { [weak self] error in
            if let error = error {
                completionHandler(error)
                return
            }
            
            // Start sing-box
            self?.startSingBox(serverAddress: serverAddress, serverPort: serverPort, completionHandler: completionHandler)
        }
    }
    
    private func startSingBox(serverAddress: String, serverPort: Int, completionHandler: @escaping (Error?) -> Void) {
        // In real implementation, would start sing-box with generated config
        // sing-box has native iOS support via Libbox.xcframework
        
        let config: [String: Any] = [
            "log": ["level": "info"],
            "inbounds": [
                [
                    "type": "tun",
                    "tag": "tun-in",
                    "interface_name": "hydra-tun",
                    "mtu": 1408,
                    "address": ["172.19.0.1/28"],
                    "auto_route": true,
                    "strict_route": true,
                    "stack": "gvisor"
                ]
            ],
            "outbounds": [
                [
                    "type": "vless",
                    "tag": "proxy",
                    "server": serverAddress,
                    "server_port": serverPort,
                    "uuid": "user-uuid-here"
                ],
                [
                    "type": "direct",
                    "tag": "direct"
                ]
            ]
        ]
        
        // Start sing-box with config
        // singBox = SingBoxBridge(config: config)
        // singBox?.start()
        
        completionHandler(nil)
    }
    
    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        // Stop sing-box
        // singBox?.stop()
        // singBox = nil
        
        completionHandler()
    }
    
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        // Handle messages from main app
        completionHandler?(nil)
    }
    
    override func sleep(completionHandler: @escaping () -> Void) {
        // Handle sleep
        completionHandler()
    }
    
    override func wake() {
        // Handle wake
    }
}