import HydraKit
import SwiftUI

/// Роутеры HydraVPN for Router: сопряжение, состояние, узлы, секции, подписки, журнал
/// (как вкладка «Роутеры» на Android и ПК).
struct RoutersView: View {
    @EnvironmentObject var app: AppModel
    @StateObject private var model = RouterModel()
    @State private var link = ""
    @State private var token = ""
    @State private var name = ""
    @State private var showScanner = false
    @State private var section = ""
    @State private var subURL = ""
    @State private var confirmRestart = false
    @State private var deleteIndex: Int?
    var theme: HydraTheme { HydraTheme(rawValue: app.state.app.theme) ?? .ambient }

    var body: some View {
        NavigationStack {
            List {
                addSection
                if model.links.isEmpty {
                    Section { Text(L("rt_empty")).font(.footnote).foregroundStyle(.secondary) }
                } else {
                    pickerSection
                    if let cur = model.current {
                        statusSection(cur)
                        nodesSection
                        sectionsSection
                        subscriptionsSection
                        logSection
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(theme.bg.ignoresSafeArea())
            .navigationTitle(L("tab_routers"))
            .toolbar {
                if model.current != nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button { Task { await model.refresh() } } label: { Label(L("rt_refresh"), systemImage: "arrow.clockwise") }
                            Button { Task { await model.reloadConfig() } } label: { Label(L("rt_reload"), systemImage: "doc.badge.gearshape") }
                            Button(role: .destructive) { confirmRestart = true } label: { Label(L("rt_restart"), systemImage: "power") }
                            if let cur = model.current {
                                Button(role: .destructive) { model.remove(cur) } label: { Label(L("rt_forget"), systemImage: "trash") }
                            }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
            }
            .sheet(isPresented: $showScanner) {
                QRScannerView { text in link = text; showScanner = false }
            }
            .alert(L("rt_restart_q"), isPresented: $confirmRestart) {
                Button(L("rt_yes"), role: .destructive) { Task { await model.restart() } }
                Button(L("action_cancel"), role: .cancel) {}
            } message: { Text(L("rt_restart_msg", model.current?.name ?? "")) }
            .alert(L("rt_delete_sub_q"), isPresented: Binding(get: { deleteIndex != nil }, set: { if !$0 { deleteIndex = nil } })) {
                Button(L("rt_yes"), role: .destructive) { if let i = deleteIndex { Task { await model.deleteSubscription(i) } }; deleteIndex = nil }
                Button(L("action_cancel"), role: .cancel) { deleteIndex = nil }
            } message: { Text(L("rt_delete_sub_msg")) }
            .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
                Button("OK", role: .cancel) {}
            }
            // Пока вкладка открыта — опрашиваем выбранный роутер.
            .task(id: model.selected) {
                while model.selected != nil && !Task.isCancelled {
                    await model.refresh()
                    try? await Task.sleep(nanoseconds: 10_000_000_000)
                }
            }
        }
    }

    // MARK: - разделы

    private var addSection: some View {
        Section {
            TextField(L("rt_link_hint"), text: $link)
                .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
            SecureField(L("rt_token_hint"), text: $token)
            TextField(L("rt_name_hint"), text: $name)
            HStack {
                Button { showScanner = true } label: { Label(L("rt_scan"), systemImage: "qrcode.viewfinder") }
                Spacer()
                Button(L("rt_add")) {
                    if model.add(link: link, token: token, name: name) { link = ""; token = ""; name = "" }
                }
                .disabled(link.trimmingCharacters(in: .whitespaces).isEmpty)
                .buttonStyle(.borderedProminent)
            }
        }
    }

    private var pickerSection: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(model.links) { l in
                        Button { model.select(l.baseURL) } label: {
                            Text(l.name + (l.insecure ? " · " + L("rt_no_tls") : ""))
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(l.baseURL == model.selected ? theme.accent.opacity(0.25) : Color.secondary.opacity(0.15), in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private func statusSection(_ cur: RouterLink) -> some View {
        Section(L("rt_state")) {
            HStack {
                Text(model.online ? L("rt_online") : (model.busy && model.error == nil ? L("rt_connecting") : L("rt_offline")))
                    .bold().foregroundStyle(model.online ? theme.accent : (model.busy && model.error == nil ? Color.orange : Color.red))
                if model.busy { ProgressView().controlSize(.small) }
            }
            let bits = [model.status.version.isEmpty ? nil : L("rt_version", model.status.version),
                        model.status.state.isEmpty ? nil : L("rt_service", model.status.state),
                        model.status.uptime.isEmpty ? nil : L("rt_uptime", model.status.uptime)].compactMap { $0 }
            if !bits.isEmpty { Text(bits.joined(separator: " · ")).font(.footnote).foregroundStyle(.secondary) }
            Text("\(cur.host):\(cur.port)").font(.footnote).foregroundStyle(.secondary)
            if cur.insecure { Text(L("rt_insecure")).font(.footnote).foregroundStyle(.orange) }
            if let e = model.error { Text(e).font(.footnote).foregroundStyle(.red) }
            if !model.status.lastError.isEmpty { Text(L("rt_last_error", model.status.lastError)).font(.footnote).foregroundStyle(.orange) }
        }
    }

    private var nodesSection: some View {
        let groups = model.nodes.filter { $0.isGroup && $0.type.lowercased() == "selector" }
        let byName = Dictionary(model.nodes.map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
        return Section(L("rt_nodes")) {
            if let e = model.nodesError { Text(L("rt_nodes_error", e)).font(.footnote).foregroundStyle(.orange) }
            if groups.isEmpty && model.nodesError == nil { Text(L("rt_nodes_empty")).font(.footnote).foregroundStyle(.secondary) }
            ForEach(groups, id: \.name) { g in
                Text(g.name).font(.subheadline.weight(.medium))
                ForEach(g.members, id: \.self) { m in
                    let ms = model.delays[m] ?? byName[m]?.delayMs ?? 0
                    HStack {
                        Button { Task { await model.selectNode(group: g.name, node: m) } } label: {
                            HStack {
                                Image(systemName: g.now == m ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(g.now == m ? theme.accent : .secondary)
                                Text(m).lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                        Spacer()
                        if ms > 0 { Text(L("rt_ms", ms)).font(.caption).foregroundStyle(theme.accent) }
                        else if ms < 0 { Text(L("rt_no_answer")).font(.caption).foregroundStyle(.red) }
                        Button(L("rt_test")) { Task { await model.testNode(m) } }.buttonStyle(.bordered).controlSize(.small)
                    }
                }
            }
        }
    }

    private var sectionsSection: some View {
        Section(L("rt_sections")) {
            if model.sections.isEmpty { Text(L("rt_sections_empty")).font(.footnote).foregroundStyle(.secondary) }
            ForEach(model.sections, id: \.name) { s in
                VStack(alignment: .leading) {
                    Text(s.title)
                    Text("\(s.action) · \(s.provider)" + (s.enabled ? "" : " · " + L("rt_section_off"))).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var subscriptionsSection: some View {
        Section(L("rt_subs")) {
            if model.subscriptions.isEmpty { Text(L("rt_subs_empty")).font(.footnote).foregroundStyle(.secondary) }
            ForEach(model.subscriptions, id: \.index) { s in
                VStack(alignment: .leading, spacing: 4) {
                    Text(s.url).lineLimit(1).truncationMode(.middle)
                    Text(s.autoUpdate ? L("rt_sub_info", s.section, String(format: "%.0f", s.updateIntervalHours)) : L("rt_sub_info_manual", s.section))
                        .font(.caption).foregroundStyle(.secondary)
                    HStack {
                        Button(L("rt_sub_refresh")) { Task { await model.refreshSubscription(s.index) } }.buttonStyle(.bordered).controlSize(.small)
                        Button(L("rt_sub_delete"), role: .destructive) { deleteIndex = s.index }.buttonStyle(.bordered).controlSize(.small)
                    }
                }
            }
            if model.sections.isEmpty {
                Text(L("rt_need_section")).font(.footnote).foregroundStyle(.secondary)
            } else {
                Picker(L("rt_section_label"), selection: $section) {
                    ForEach(model.sections, id: \.name) { Text($0.title).tag($0.name) }
                }
                .onAppear { if section.isEmpty { section = model.sections.first?.name ?? "" } }
                .onChange(of: model.sections) { _, s in if !s.contains(where: { $0.name == section }) { section = s.first?.name ?? "" } }
                TextField(L("rt_sub_url"), text: $subURL)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                Button(L("rt_add")) {
                    let u = subURL.trimmingCharacters(in: .whitespaces)
                    subURL = ""
                    Task { await model.addSubscription(section: section, url: u) }
                }
                .disabled(subURL.trimmingCharacters(in: .whitespaces).isEmpty || section.isEmpty)
                let own = app.state.subscriptions.filter { $0.url.lowercased().hasPrefix("http") }
                if !own.isEmpty {
                    Text(L("rt_send_from_hydra")).font(.footnote).foregroundStyle(.secondary)
                    ForEach(own) { s in
                        Button { Task { await model.addSubscription(section: section, url: s.url) } } label: {
                            Label(s.displayName, systemImage: "paperplane")
                        }
                        .disabled(section.isEmpty)
                    }
                }
            }
        }
    }

    private var logSection: some View {
        Section(L("rt_log")) {
            if model.logs.isEmpty { Text(L("rt_log_empty")).font(.footnote).foregroundStyle(.secondary) }
            ForEach(Array(model.logs.suffix(40).enumerated()), id: \.offset) { _, e in
                let time = e.time.split(separator: "T").last.map { String($0.prefix(8)) } ?? ""
                Text("\(time) \(e.level.uppercased()) \(e.message)")
                    .font(.caption2.monospaced())
                    .foregroundStyle(e.level.lowercased() == "error" ? Color.red : (e.level.lowercased().hasPrefix("warn") ? Color.orange : Color.secondary))
                    .textSelection(.enabled)
            }
        }
    }
}
