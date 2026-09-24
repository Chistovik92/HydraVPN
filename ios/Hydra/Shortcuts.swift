import AppIntents

/// Быстрые команды Hydra в приложении «Команды» и Spotlight — аналог App Shortcuts на Android.
struct HydraShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: ConnectHydraIntent(), phrases: ["Connect \(.applicationName)", "Подключить \(.applicationName)"],
                    shortTitle: "Connect", systemImageName: "lock.shield")
        AppShortcut(intent: DisconnectHydraIntent(), phrases: ["Disconnect \(.applicationName)", "Отключить \(.applicationName)"],
                    shortTitle: "Disconnect", systemImageName: "lock.open")
    }
}
