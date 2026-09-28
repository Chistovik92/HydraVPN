import SwiftUI

@main
struct HydraVPNApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @StateObject private var vpnManager = VPNManager()
    
    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                // Status
                VStack(spacing: 8) {
                    Circle()
                        .fill(vpnManager.isConnected ? Color.green : Color.red)
                        .frame(width: 80, height: 80)
                    
                    Text(vpnManager.isConnected ? "Connected" : "Disconnected")
                        .font(.title2)
                        .fontWeight(.bold)
                    
                    if vpnManager.isConnected {
                        Text("↑ \(formatBytes(vpnManager.txBytes))  ↓ \(formatBytes(vpnManager.rxBytes))")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .padding()
                
                // Server selection
                NavigationLink(destination: ServerListView(vpnManager: vpnManager)) {
                    HStack {
                        Image(systemName: "server.rack")
                        Text("Servers")
                        Spacer()
                        Text("\(vpnManager.servers.count)")
                            .foregroundColor(.secondary)
                        Image(systemName: "chevron.right")
                            .foregroundColor(.secondary)
                    }
                    .padding()
                    .background(Color(.systemGray6))
                    .cornerRadius(10)
                }
                
                // Connect button
                Button(action: {
                    if vpnManager.isConnected {
                        vpnManager.disconnect()
                    } else {
                        vpnManager.connect()
                    }
                }) {
                    Text(vpnManager.isConnected ? "DISCONNECT" : "CONNECT")
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(vpnManager.isConnected ? Color.red : Color.blue)
                        .cornerRadius(10)
                }
                
                Spacer()
            }
            .padding()
            .navigationTitle("HydraVPN")
        }
    }
    
    private func formatBytes(_ bytes: Int64) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        if bytes < 1024 * 1024 { return String(format: "%.1f KB", Double(bytes) / 1024.0) }
        if bytes < 1024 * 1024 * 1024 { return String(format: "%.1f MB", Double(bytes) / (1024.0 * 1024)) }
        return String(format: "%.1f GB", Double(bytes) / (1024.0 * 1024 * 1024))
    }
}

struct ServerListView: View {
    @ObservedObject var vpnManager: VPNManager
    
    var body: some View {
        List(vpnManager.servers) { server in
            ServerRowView(server: server, isSelected: server.id == vpnManager.selectedServerId)
                .onTapGesture {
                    vpnManager.selectedServerId = server.id
                }
        }
        .navigationTitle("Servers")
    }
}

struct ServerRowView: View {
    let server: Server
    let isSelected: Bool
    
    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(server.name)
                    .font(.headline)
                Text(server.protocol)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Spacer()
            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(.blue)
            }
        }
        .padding(.vertical, 4)
    }
}

struct Server: Identifiable {
    let id: String
    let name: String
    let protocol: String
    let address: String
    let port: Int
}

class VPNManager: ObservableObject {
    @Published var isConnected = false
    @Published var servers: [Server] = []
    @Published var selectedServerId: String?
    @Published var rxBytes: Int64 = 0
    @Published var txBytes: Int64 = 0
    
    func connect() {
        // In real implementation, would start NEPacketTunnelProvider
        isConnected = true
    }
    
    func disconnect() {
        // In real implementation, would stop NEPacketTunnelProvider
        isConnected = false
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}