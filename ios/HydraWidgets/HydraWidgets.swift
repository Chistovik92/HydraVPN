import AppIntents
import HydraKit
import NetworkExtension
import SwiftUI
import WidgetKit

/// Виджеты Hydra (Фаза 12) — аналог виджета и плитки в шторке на Android: подключённая локация,
/// статус и кнопка «Подключить / Отключить»; на iOS 18 — кнопка в Пункте управления.
@main
struct HydraWidgets: WidgetBundle {
    var body: some Widget {
        StatusWidget()
        if #available(iOSApplicationExtension 18.0, *) {
            HydraControl()
        }
    }
}

struct StatusEntry: TimelineEntry {
    let date: Date
    let location: String
    let up: Bool
    let status: String
}

struct StatusProvider: TimelineProvider {
    func placeholder(in _: Context) -> StatusEntry {
        StatusEntry(date: .now, location: "🇩🇪 Germany · Frankfurt-1", up: true, status: L("connected"))
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        Task { @MainActor in completion(await entry()) }
    }

    func getTimeline(in _: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        // Обновляется из приложения/интентов при смене состояния; раз в 15 мин — на всякий случай.
        Task { @MainActor in completion(Timeline(entries: [await entry()], policy: .after(.now.addingTimeInterval(900)))) }
    }

    @MainActor
    private func entry() async -> StatusEntry {
        let state = HydraStore.shared().load()
        let status = await VPNController.status()
        let text: String = switch status {
        case .connected: L("connected")
        case .connecting: L("connecting")
        case .reasserting: L("notif_reconnecting")
        default: L("disconnected")
        }
        return StatusEntry(date: .now,
                           location: state.selectedServer.map { ServerLocation.label($0) } ?? L("widget_no_server"),
                           up: status.isUp, status: text)
    }
}

struct StatusWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "HydraStatus", provider: StatusProvider()) { e in
            StatusWidgetView(entry: e)
                .containerBackground(HydraTheme.ambient.bg, for: .widget)
                .widgetURL(URL(string: "hydra://open"))
        }
        .configurationDisplayName("Hydra VPN")
        .description(L("widget_description"))
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryInline])
    }
}

struct StatusWidgetView: View {
    @Environment(\.widgetFamily) var family
    let entry: StatusEntry
    var accent: Color { HydraTheme.ambient.accent }

    var body: some View {
        switch family {
        case .accessoryInline:
            Text(entry.up ? entry.location : "Hydra · \(entry.status)")
        case .accessoryRectangular:
            VStack(alignment: .leading) {
                Text("Hydra VPN").font(.headline)
                Text(entry.location).lineLimit(1)
                Text(entry.status).font(.caption)
            }
        default:
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Image(systemName: entry.up ? "lock.shield.fill" : "lock.open").foregroundStyle(entry.up ? accent : .gray)
                    Text("Hydra").font(.caption.weight(.bold)).foregroundStyle(.white)
                }
                Text(entry.location).font(.subheadline.weight(.semibold)).foregroundStyle(.white).lineLimit(2)
                Text(entry.status).font(.caption).foregroundStyle(entry.up ? accent : .gray)
                Spacer(minLength: 0)
                Button(intent: ToggleHydraIntent()) {
                    Text(entry.up ? L("widget_disconnect") : L("widget_connect"))
                        .font(.caption.weight(.bold)).frame(maxWidth: .infinity)
                }
                .tint(entry.up ? .white.opacity(0.9) : accent)
            }
        }
    }
}

// MARK: - Пункт управления (iOS 18)

@available(iOSApplicationExtension 18.0, *)
struct HydraControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "HydraControl", provider: HydraControlValue()) { up in
            ControlWidgetToggle("Hydra VPN", isOn: up, action: SetHydraIntent()) { on in
                Label(on ? L("connected") : L("disconnected"), systemImage: on ? "lock.shield.fill" : "lock.open")
            }
        }
        .displayName("Hydra VPN")
    }
}

@available(iOSApplicationExtension 18.0, *)
struct HydraControlValue: ControlValueProvider {
    var previewValue: Bool { false }
    func currentValue() async throws -> Bool { await VPNController.status().isUp }
}

@available(iOSApplicationExtension 18.0, *)
struct SetHydraIntent: SetValueIntent {
    static var title: LocalizedStringResource = "Hydra VPN"
    @Parameter(title: "Connected") var value: Bool

    @MainActor
    func perform() async throws -> some IntentResult {
        if value { try await VPNController.connect(HydraStore.shared().load()) } else { await VPNController.disconnect() }
        return .result()
    }
}
