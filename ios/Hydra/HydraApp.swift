import BackgroundTasks
import HydraKit
import LocalAuthentication
import SwiftUI

@main
struct HydraApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase
    @State private var unlocked = false
    @State private var backgroundedAt: Date?
    static let refreshTask = "ru.gidravpn.hydra.subscriptions"

    init() {
        TrafficClient.setupLibbox()
        // Автообновление подписок в фоне (как WorkManager на Android): iOS сама выбирает момент.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTask, using: nil) { task in
            Task { @MainActor in
                await AppModel().refreshDueNow()
                HydraApp.scheduleRefresh()
                task.setTaskCompleted(success: true)
            }
        }
    }

    static func scheduleRefresh() {
        let r = BGAppRefreshTaskRequest(identifier: refreshTask)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 3600)
        try? BGTaskScheduler.shared.submit(r)
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if model.state.app.appLock && !unlocked {
                    LockView(onUnlock: authenticate)
                } else {
                    RootView()
                }
            }
            .environmentObject(model)
            .preferredColorScheme(.dark)
            // hydra://open — тап по виджету: просто открыть приложение; остальное — импорт ссылки.
            .onOpenURL { url in if url.scheme != "hydra" { model.importText(url.absoluteString) } }
            // При включённой блокировке содержимое не показывается в переключателе приложений.
            .overlay { if model.state.app.appLock && phase != .active { LockView(onUnlock: nil) } }
        }
        .onChange(of: phase) { _, p in
            switch p {
            case .background:
                backgroundedAt = Date()
                HydraApp.scheduleRefresh()
            case .active:
                if let t = backgroundedAt, Date().timeIntervalSince(t) > 30 { unlocked = false }
                backgroundedAt = nil
                model.refreshDueSubscriptions()
                Task { await model.refreshStatus() }
            default: break
            }
        }
    }

    private func authenticate() {
        let ctx = LAContext()
        var error: NSError?
        // .deviceOwnerAuthentication — Face ID / Touch ID с запасным вариантом кода-пароля.
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            unlocked = true   // на устройстве нет ни биометрии, ни кода — не запираем владельца
            return
        }
        ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: L("lock_prompt_subtitle")) { ok, _ in
            DispatchQueue.main.async { if ok { unlocked = true } }
        }
    }
}

struct LockView: View {
    let onUnlock: (() -> Void)?
    var body: some View {
        ZStack {
            HydraTheme.ambient.bg.ignoresSafeArea()
            if let onUnlock {
                VStack(spacing: 14) {
                    Image(systemName: "lock.shield").font(.system(size: 56)).foregroundStyle(HydraTheme.ambient.accent)
                    Text(L("lock_title")).font(.title2.weight(.semibold))
                    Text(L("lock_hint")).font(.footnote).foregroundStyle(Color.hydraMuted).multilineTextAlignment(.center)
                    Button(L("lock_unlock"), action: onUnlock).buttonStyle(.borderedProminent).tint(HydraTheme.ambient.accent)
                }
                .padding(32)
                .onAppear(perform: onUnlock)
            }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @State private var tab = 0
    var theme: HydraTheme { HydraTheme(rawValue: model.state.app.theme) ?? .ambient }

    var body: some View {
        TabView(selection: $tab) {
            HomeView(goServers: { tab = 1 }).tabItem { Label(L("tab_main"), systemImage: "house.fill") }.tag(0)
            ServersView(onSelected: { tab = 0 }).tabItem { Label(L("tab_servers"), systemImage: "server.rack") }.tag(1)
            ProfileView().tabItem { Label(L("tab_profile"), systemImage: "person.fill") }.tag(2)
            SettingsView().tabItem { Label(L("tab_settings"), systemImage: "gearshape.fill") }.tag(3)
        }
        .tint(theme.accent)
        .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }
}
