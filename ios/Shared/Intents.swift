import AppIntents
import HydraKit
import WidgetKit

/// Подключить / отключить Hydra — для виджета, Пункта управления и приложения «Команды»
/// (аналог ярлыков на иконке и плитки в шторке на Android).
struct ConnectHydraIntent: AppIntent {
    static var title: LocalizedStringResource = "Connect Hydra"
    static var description = IntentDescription("Connects to the last selected Hydra server.")

    @MainActor
    func perform() async throws -> some IntentResult {
        try await VPNController.connect(HydraStore.shared().load())
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

struct DisconnectHydraIntent: AppIntent {
    static var title: LocalizedStringResource = "Disconnect Hydra"

    @MainActor
    func perform() async throws -> some IntentResult {
        await VPNController.disconnect()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

struct ToggleHydraIntent: AppIntent {
    static var title: LocalizedStringResource = "Toggle Hydra"

    @MainActor
    func perform() async throws -> some IntentResult {
        if await VPNController.status().isUp {
            await VPNController.disconnect()
        } else {
            try await VPNController.connect(HydraStore.shared().load())
        }
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}
