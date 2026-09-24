import HydraKit
import SwiftUI

/// Профиль — статистика сессии, HWID для панелей, ссылки (как ProfileScreen на Android).
struct ProfileView: View {
    @EnvironmentObject var model: AppModel
    @State private var copied = false
    var theme: HydraTheme { HydraTheme(rawValue: model.state.app.theme) ?? .ambient }

    var body: some View {
        NavigationStack {
            List {
                Section(L("profile_stats")) {
                    ProfileTraffic(traffic: model.traffic)
                    LabeledContent(L("profile_session")) {
                        if let since = model.connectedSince { Text(since, style: .timer).monospacedDigit() } else { Text("—") }
                    }
                    LabeledContent(L("info_server"), value: model.selected.map { ServerLocation.label($0) } ?? "—")
                }
                Section(footer: Text(L("profile_hwid_hint"))) {
                    Button {
                        UIPasteboard.general.string = HydraDevice.hwid
                        copied = true
                    } label: {
                        LabeledContent(L("profile_hwid")) {
                            Text(HydraDevice.hwid).font(.caption.monospaced()).lineLimit(1).truncationMode(.middle)
                        }
                    }
                    .foregroundStyle(.primary)
                    if copied { Text(L("hotspot_copied")).font(.caption).foregroundStyle(theme.accent) }
                }
                Section {
                    Link(L("profile_open_site"), destination: URL(string: "https://gidravpn.ru")!)
                    Link(L("profile_open_github"), destination: URL(string: "https://github.com/Chistovik92/HydraVPN")!)
                }
            }
            .scrollContentBackground(.hidden)
            .background(theme.bg.ignoresSafeArea())
            .navigationTitle(L("tab_profile"))
        }
    }
}

private struct ProfileTraffic: View {
    @ObservedObject var traffic: TrafficClient
    var body: some View {
        LabeledContent(L("info_down"), value: Format.bytes(traffic.down))
        LabeledContent(L("info_up"), value: Format.bytes(traffic.up))
    }
}
