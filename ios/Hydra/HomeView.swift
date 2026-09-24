import HydraKit
import NetworkExtension
import SwiftUI

/// Главная — большая кнопка подключения, локация, время и трафик (как MainScreen на Android).
struct HomeView: View {
    @EnvironmentObject var model: AppModel
    let goServers: () -> Void
    var theme: HydraTheme { HydraTheme(rawValue: model.state.app.theme) ?? .ambient }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    card {
                        Text(L("main_protocol").uppercased()).font(.caption2.weight(.semibold)).foregroundStyle(Color.hydraMuted)
                        Text(model.selected?.serverProtocol?.displayName ?? "—").font(.body)
                    }

                    ConnectButton(status: model.status, accent: theme.accent) { model.toggle() }
                        .padding(.vertical, 12)

                    card {
                        row(L("info_status"), statusText)
                        row(L("info_server"), model.selected.map { ServerLocation.label($0) } ?? L("main_no_server"))
                        if let since = model.connectedSince {
                            HStack {
                                Text(L("info_time")).foregroundStyle(Color.hydraMuted)
                                Spacer()
                                Text(since, style: .timer).monospacedDigit()
                            }.font(.subheadline)
                        }
                        TrafficRows(traffic: model.traffic)
                    }
                    Button(L("main_change"), action: goServers).foregroundStyle(theme.accent)
                }
                .padding(20)
            }
            .background(theme.bg.ignoresSafeArea())
            .navigationTitle("HYDRA VPN")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    var statusText: String {
        switch model.status {
        case .connected: L("connected")
        case .connecting: L("connecting")
        case .reasserting: L("notif_reconnecting")
        case .disconnecting: L("btn_connecting")
        default: L("disconnected")
        }
    }

    @ViewBuilder func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8, content: content)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(theme.card, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.08)))
    }

    func row(_ k: String, _ v: String) -> some View {
        HStack { Text(k).foregroundStyle(Color.hydraMuted); Spacer(); Text(v).multilineTextAlignment(.trailing) }.font(.subheadline)
    }
}

/// Счётчики трафика — отдельный ObservableObject, поэтому свой подвид, подписанный на него.
struct TrafficRows: View {
    @ObservedObject var traffic: TrafficClient
    var body: some View {
        HStack { Text(L("info_down")).foregroundStyle(Color.hydraMuted); Spacer(); Text(Format.bytes(traffic.down)) }.font(.subheadline)
        HStack { Text(L("info_up")).foregroundStyle(Color.hydraMuted); Spacer(); Text(Format.bytes(traffic.up)) }.font(.subheadline)
    }
}

struct ConnectButton: View {
    let status: NEVPNStatus
    let accent: Color
    let action: () -> Void
    @State private var pulse = false

    var body: some View {
        let up = status == .connected
        let busy = status == .connecting || status == .reasserting || status == .disconnecting
        Button(action: action) {
            ZStack {
                Circle().stroke(accent.opacity(0.25), lineWidth: 2).frame(width: 220, height: 220)
                    .scaleEffect(pulse && busy ? 1.08 : 1)
                Circle().fill(up ? accent.opacity(0.18) : Color.white.opacity(0.05)).frame(width: 180, height: 180)
                Circle().stroke(up ? accent : Color.white.opacity(0.2), lineWidth: 4).frame(width: 180, height: 180)
                VStack(spacing: 6) {
                    Image(systemName: "power").font(.system(size: 44, weight: .semibold))
                    Text(up ? L("btn_disconnect") : busy ? L("btn_connecting") : L("btn_connect"))
                        .font(.caption.weight(.bold))
                }
                .foregroundStyle(up ? accent : .white)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(up ? L("btn_disconnect") : L("btn_connect"))
        .onAppear { withAnimation(.easeInOut(duration: 1).repeatForever()) { pulse = true } }
    }
}
