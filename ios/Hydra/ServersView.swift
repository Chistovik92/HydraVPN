import CoreImage.CIFilterBuiltins
import HydraKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Серверы и подписки — как ServersScreen на Android (0.6.22): шапка с поиском/сортировкой/пингом,
/// одна кнопка «Добавить» с меню, подписка — карточка со шкалой трафика, серверы — компактные строки.
struct ServersView: View {
    @EnvironmentObject var model: AppModel
    let onSelected: () -> Void
    @State private var query = ""
    @State private var sortByPing = false
    @State private var showLink = false
    @State private var showManual = false
    @State private var showScanner = false
    @State private var showFile = false
    @State private var photo: PhotosPickerItem?
    @State private var share: (title: String, link: String)?
    var theme: HydraTheme { HydraTheme(rawValue: model.state.app.theme) ?? .ambient }

    var body: some View {
        NavigationStack {
            List {
                if model.state.servers.isEmpty && model.state.subscriptions.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "server.rack").font(.largeTitle).foregroundStyle(theme.accent)
                        Text(L("srv_empty_title")).font(.headline)
                        Text(L("srv_empty_desc")).font(.footnote).foregroundStyle(Color.hydraMuted).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 32).listRowBackground(Color.clear)
                }
                ForEach(model.state.subscriptions) { sub in
                    Section {
                        SubscriptionCard(sub: sub, count: servers(of: sub.id).count, onShare: { share = (sub.displayName, sub.url) })
                        if !sub.collapsed {
                            ForEach(servers(of: sub.id)) { row($0) }
                        }
                    }
                }
                let standalone = servers(of: nil)
                if !standalone.isEmpty {
                    Section(model.state.subscriptions.isEmpty ? "" : L("sub_standalone")) {
                        ForEach(standalone) { row($0) }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(theme.bg.ignoresSafeArea())
            .searchable(text: $query, prompt: L("srv_search_hint"))
            .navigationTitle(L("tab_servers"))
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { sortByPing.toggle() } label: {
                        Image(systemName: "arrow.up.arrow.down").foregroundStyle(sortByPing ? theme.accent : .secondary)
                    }.accessibilityLabel(L("srv_sort_ping"))
                    Button { model.pingAll() } label: { Image(systemName: "gauge.with.dots.needle.33percent") }
                        .accessibilityLabel(L("servers_refresh_ping"))
                    if !model.state.subscriptions.isEmpty {
                        Button { model.refreshAllSubscriptions() } label: { Image(systemName: "arrow.clockwise") }
                            .accessibilityLabel(L("sub_refresh_all"))
                    }
                    addMenu
                }
            }
            .sheet(isPresented: $showLink) { LinkImportSheet() }
            .sheet(isPresented: $showManual) { ManualServerSheet() }
            .sheet(isPresented: $showScanner) {
                QRScannerView { text in showScanner = false; model.importText(text) }
            }
            .sheet(isPresented: Binding(get: { share != nil }, set: { if !$0 { share = nil } })) {
                if let share { ShareSheet(title: share.title, link: share.link, hideSecrets: model.state.app.hideSecrets) }
            }
            .fileImporter(isPresented: $showFile, allowedContentTypes: [.plainText, .data, .item]) { result in
                guard case .success(let url) = result else { return }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                if let text = try? String(contentsOf: url, encoding: .utf8) { model.importText(text) }
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        let codes = QRDecoder.decode(data)
                        if codes.isEmpty { model.message = L("import_no_qr") } else { codes.forEach { model.importText($0) } }
                    }
                    photo = nil
                }
            }
        }
    }

    private var addMenu: some View {
        Menu {
            Button { model.importText(UIPasteboard.general.string ?? "") } label: { Label(L("srv_add_paste"), systemImage: "doc.on.clipboard") }
            Button { showScanner = true } label: { Label(L("srv_add_scan"), systemImage: "qrcode.viewfinder") }
            PhotosPicker(selection: $photo, matching: .images) { Label(L("srv_add_photo"), systemImage: "photo") }
            Button { showLink = true } label: { Label(L("srv_add_link"), systemImage: "link") }
            Button { showFile = true } label: { Label(L("ios_import_file"), systemImage: "doc") }
            Button { showManual = true } label: { Label(L("srv_add_manual"), systemImage: "square.and.pencil") }
        } label: {
            Image(systemName: "plus.circle.fill").foregroundStyle(theme.accent)
        }
        .accessibilityLabel(L("srv_add"))
    }

    private func servers(of subId: Int64?) -> [ServerProfile] {
        var list = model.state.servers.filter { $0.subscriptionId == subId }
        let q = query.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty {
            list = list.filter { $0.name.localizedCaseInsensitiveContains(q) || ($0.serverProtocol?.displayName ?? "").localizedCaseInsensitiveContains(q) }
        }
        if sortByPing { list.sort { ($0.pingMs < 0 ? Int.max : $0.pingMs) < ($1.pingMs < 0 ? Int.max : $1.pingMs) } }
        return list
    }

    @ViewBuilder private func row(_ p: ServerProfile) -> some View {
        let selected = p.id == model.selected?.id
        let supported = p.serverProtocol?.engine == .singBox
        Button {
            model.select(p.id)
            onSelected()
        } label: {
            HStack(spacing: 12) {
                Text(ServerLocation.flag(p)).font(.title3).frame(width: 36, height: 36).background(Circle().fill(Color.white.opacity(0.06)))
                VStack(alignment: .leading, spacing: 2) {
                    Text(ServerLocation.bareName(p).isEmpty ? p.address : ServerLocation.bareName(p)).lineLimit(1)
                    HStack(spacing: 6) {
                        Text(p.serverProtocol?.shortCode ?? p.protocolId.uppercased()).font(.caption2.weight(.bold))
                            .padding(.horizontal, 5).padding(.vertical, 1).background(Capsule().fill(theme.accent.opacity(0.15)))
                        if !supported { Text(L("srv_engine_off")).font(.caption2).foregroundStyle(Color.hydraWarn) }
                    }
                }
                Spacer()
                pingText(p)
                if selected { Image(systemName: "checkmark").foregroundStyle(theme.accent).accessibilityLabel(L("a11y_selected")) }
            }
            .opacity(supported ? 1 : 0.5)
        }
        .foregroundStyle(.primary)
        .listRowBackground(selected ? theme.accent.opacity(0.1) : theme.card)
        .swipeActions {
            Button(role: .destructive) { model.deleteServer(p) } label: { Label(L("action_delete"), systemImage: "trash") }
            Button { share = (p.name, LinkBuilder.link(p) ?? "") } label: { Label(L("share_action"), systemImage: "square.and.arrow.up") }
                .tint(.blue)
            Button { model.ping(p) } label: { Label(L("sub_ping"), systemImage: "gauge.with.dots.needle.33percent") }.tint(.gray)
        }
    }

    @ViewBuilder private func pingText(_ p: ServerProfile) -> some View {
        if model.measuring.contains(p.id) {
            ProgressView().controlSize(.small)
        } else if p.pingMs >= 0 {
            Text(L("servers_ping_ms", p.pingMs)).font(.caption.monospacedDigit())
                .foregroundStyle(p.pingMs < 150 ? .green : p.pingMs < 400 ? Color.hydraWarn : Color.hydraDanger)
        } else if p.pingMs == -1 {
            EmptyView()
        }
    }
}

struct SubscriptionCard: View {
    @EnvironmentObject var model: AppModel
    let sub: Subscription
    let count: Int
    let onShare: () -> Void
    @State private var confirmDelete = false
    var theme: HydraTheme { HydraTheme(rawValue: model.state.app.theme) ?? .ambient }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(String(sub.displayName.prefix(1)).uppercased()).font(.headline)
                    .frame(width: 36, height: 36).background(Circle().fill(theme.accent.opacity(0.2)))
                VStack(alignment: .leading) {
                    Text(sub.displayName).font(.headline).lineLimit(1)
                    Text(L("sub_servers_count", count) + " · " + L("sub_updated", updated)).font(.caption).foregroundStyle(Color.hydraMuted).lineLimit(1)
                }
                Spacer()
                Menu {
                    Button { model.refresh(subscriptionId: sub.id) } label: { Label(L("sub_refresh_short"), systemImage: "arrow.clockwise") }
                    Button { model.pingAll(model.state.servers.filter { $0.subscriptionId == sub.id }) } label: {
                        Label(L("sub_ping"), systemImage: "gauge.with.dots.needle.33percent")
                    }
                    Button(action: onShare) { Label(L("share_action"), systemImage: "square.and.arrow.up") }
                    Toggle(L("sub_auto_update"), isOn: Binding(get: { sub.autoUpdate }, set: { var s = sub; s.autoUpdate = $0; model.updateSubscription(s) }))
                    Button(role: .destructive) { confirmDelete = true } label: { Label(L("action_delete"), systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle").accessibilityLabel(L("a11y_more_actions"))
                }
                Button {
                    var s = sub; s.collapsed.toggle(); model.updateSubscription(s)
                } label: { Image(systemName: sub.collapsed ? "chevron.down" : "chevron.up") }
                    .buttonStyle(.borderless)
            }
            if sub.totalBytes > 0 {
                ProgressView(value: min(1, Double(sub.usedBytes) / Double(sub.totalBytes)))
                    .tint(Double(sub.usedBytes) > Double(sub.totalBytes) * 0.9 ? Color.hydraWarn : theme.accent)
            }
            HStack {
                Text(traffic).font(.caption).foregroundStyle(Color.hydraMuted)
                Spacer()
                if sub.expireAt > 0 {
                    Text(L("sub_until", Date(timeIntervalSince1970: TimeInterval(sub.expireAt)).formatted(date: .abbreviated, time: .omitted)))
                        .font(.caption).foregroundStyle(Color.hydraMuted)
                }
            }
            if model.refreshing.contains(sub.id) { ProgressView().controlSize(.small) }
            if !sub.lastError.isEmpty { Text(sub.lastError).font(.caption2).foregroundStyle(Color.hydraDanger) }
        }
        .listRowBackground(theme.card)
        .confirmationDialog(L("sub_delete_title"), isPresented: $confirmDelete) {
            Button(L("action_delete"), role: .destructive) { model.deleteSubscription(sub) }
        } message: { Text(L("sub_delete_msg", sub.displayName, count)) }
    }

    var updated: String {
        guard sub.lastUpdated > 0 else { return L("sub_never") }
        return RelativeDateTimeFormatter().localizedString(for: Date(timeIntervalSince1970: TimeInterval(sub.lastUpdated) / 1000), relativeTo: Date())
    }

    var traffic: String {
        var parts: [String] = []
        if sub.totalBytes > 0 { parts.append("\(Format.bytes(sub.usedBytes)) / \(Format.bytes(sub.totalBytes))") }
        else if sub.usedBytes > 0 { parts.append(Format.bytes(sub.usedBytes)) }
        if sub.autoUpdate { parts.append(L("sub_auto_every", sub.autoUpdateHours)) }
        return parts.joined(separator: " · ")
    }
}

struct LinkImportSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    @State private var link = ""
    @State private var name = ""
    var body: some View {
        NavigationStack {
            Form {
                Section(footer: Text(L("import_hint"))) {
                    TextField(L("import_link_field"), text: $link, axis: .vertical).lineLimit(1...6)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField(L("import_sub_name_field"), text: $name)
                }
            }
            .navigationTitle(L("import_title"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("import_action")) { model.importText(link, subscriptionName: name.isEmpty ? nil : name); dismiss() }
                        .disabled(link.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}

struct ManualServerSheet: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    @State private var name = ""
    @State private var address = ""
    @State private var port = "443"
    @State private var proto: ServerProtocol = .vless
    var body: some View {
        NavigationStack {
            Form {
                TextField(L("field_name"), text: $name)
                TextField(L("field_address"), text: $address).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField(L("field_port"), text: $port).keyboardType(.numberPad)
                Picker(L("main_protocol"), selection: $proto) {
                    ForEach(ServerProtocol.allCases.filter { $0.engine == .singBox }, id: \.self) { Text($0.displayName).tag($0) }
                }
            }
            .navigationTitle(L("servers_new_title"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_save")) {
                        model.addServer(ServerProfile(name: name.isEmpty ? L("default_server_name") : name, protocolId: proto.rawValue,
                                                      address: address, port: Int(port) ?? 443))
                        dismiss()
                    }.disabled(address.isEmpty || Int(port) == nil)
                }
            }
        }
    }
}

/// «Поделиться»: QR и ссылка. На экране ссылка маскируется («Скрывать ключи»), QR и отправка — полные.
struct ShareSheet: View {
    let title: String
    let link: String
    let hideSecrets: Bool
    @State private var reveal = false
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let img = QRDecoder.image(link) {
                    Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
                        .frame(maxWidth: 280).padding(8).background(Color.white).clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel(L("share_qr_desc"))
                } else {
                    Text(L("share_too_long")).foregroundStyle(Color.hydraDanger)
                }
                Text(hideSecrets && !reveal ? SecretMask.mask(link) : link)
                    .font(.caption.monospaced()).lineLimit(4).textSelection(.enabled)
                if hideSecrets { Button(L(reveal ? "secret_hide" : "secret_show")) { reveal.toggle() } }
                Text(L("share_warning")).font(.footnote).foregroundStyle(Color.hydraMuted).multilineTextAlignment(.center)
                ShareLink(item: link) { Label(L("share_send"), systemImage: "square.and.arrow.up") }.buttonStyle(.borderedProminent)
                Button(L("hotspot_copy")) { UIPasteboard.general.string = link }
                Spacer()
            }
            .padding()
            .navigationTitle(title)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { dismiss() } } }
        }
    }
}

/// QR: генерация (CoreImage) и чтение с картинки (CIDetector) — без сторонних библиотек.
enum QRDecoder {
    static func image(_ text: String) -> UIImage? {
        guard text.utf8.count <= 2900 else { return nil }
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }

    static func decode(_ data: Data) -> [String] {
        guard let ci = CIImage(data: data),
              let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        else { return [] }
        return detector.features(in: ci).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
    }
}
