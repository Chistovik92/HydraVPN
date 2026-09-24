import HydraKit
import SwiftUI
import UniformTypeIdentifiers

/// Настройки — те же разделы, что на Android (SettingsScreen): туннель, безопасность,
/// маршрутизация, раздельное туннелирование, хотспот, журнал, тема, язык, резервная копия, о приложении.
struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    var theme: HydraTheme { HydraTheme(rawValue: model.state.app.theme) ?? .ambient }

    var body: some View {
        NavigationStack {
            List {
                link("set_tunnel", "set_tunnel_sub", "cpu") { TunnelSettings() }
                link("set_security", "set_security_sub", "shield.lefthalf.filled") { SecuritySettings() }
                link("set_routing", "set_routing_sub", "arrow.triangle.branch") { RoutingSettingsView() }
                link("set_split", "set_split_sub", "arrow.left.arrow.right") { SplitSettings() }
                link("hotspot_hub_title", "hotspot_hub_subtitle", "antenna.radiowaves.left.and.right") { HotspotSettings() }
                link("set_logs", "set_logs_sub", "doc.text") { LogsView() }
                link("theme_title", nil, "paintpalette") { ThemeSettings() }
                link("set_language", "set_language_sub", "globe") { LanguageSettings() }
                link("set_backup", "set_backup_sub", "externaldrive") { BackupSettings() }
                link("set_about", "set_about_sub", "info.circle") { AboutSettings() }
            }
            .scrollContentBackground(.hidden)
            .background(theme.bg.ignoresSafeArea())
            .navigationTitle(L("tab_settings"))
        }
    }

    private func link<D: View>(_ title: String, _ sub: String?, _ icon: String, @ViewBuilder _ dest: () -> D) -> some View {
        NavigationLink(destination: dest().scrollContentBackground(.hidden).background(theme.bg.ignoresSafeArea())) {
            Label {
                VStack(alignment: .leading) {
                    Text(L(title))
                    if let sub { Text(L(sub)).font(.caption).foregroundStyle(Color.hydraMuted) }
                }
            } icon: { Image(systemName: icon).foregroundStyle(theme.accent) }
        }
        .listRowBackground(theme.card)
    }
}

// MARK: - разделы

struct TunnelSettings: View {
    var body: some View {
        List {
            Section(footer: Text(L("ios_limits_desc"))) {
                engine("eng_singbox", "eng_singbox_desc", on: true)
                engine("eng_xray", "eng_xray_desc", on: false)
                engine("eng_awg", "eng_awg_desc", on: false)
                engine("eng_ppp", "eng_ppp_desc", on: false)
                engine("eng_olcrtc", "eng_olcrtc_desc", on: false)
                engine("eng_openflux", "eng_openflux_desc", on: false)
            }
        }
        .navigationTitle(L("set_tunnel"))
    }

    func engine(_ t: String, _ d: String, on: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(L(t)).font(.headline)
                Spacer()
                Image(systemName: on ? "checkmark.circle.fill" : "minus.circle").foregroundStyle(on ? .green : Color.hydraMuted)
            }
            Text(L(d)).font(.caption).foregroundStyle(Color.hydraMuted)
        }
        .opacity(on ? 1 : 0.55)
    }
}

struct SecuritySettings: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        List {
            Section(footer: Text(L("ios_killswitch_desc"))) {
                Toggle("Kill Switch", isOn: bind(\.app.killSwitch, reconnect: true))
            }
            Section(footer: Text(L("ios_ondemand_desc"))) {
                Toggle(L("ios_ondemand"), isOn: bind(\.app.onDemand, reconnect: false))
            }
            Section(footer: Text(L("sec_app_lock_desc"))) {
                Toggle(L("sec_app_lock"), isOn: Binding(get: { model.state.app.appLock }, set: { v in model.mutate { $0.app.appLock = v } }))
            }
            Section(footer: Text(L("sec_hide_secrets_desc"))) {
                Toggle(L("sec_hide_secrets"), isOn: Binding(get: { model.state.app.hideSecrets }, set: { v in model.mutate { $0.app.hideSecrets = v } }))
            }
            Section(L("ios_widgets_title")) { Text(L("ios_widgets_desc")).font(.footnote) }
        }
        .navigationTitle(L("set_security"))
    }

    /// Kill Switch и On-Demand — часть системной VPN-конфигурации: сохранить и переустановить её.
    private func bind(_ kp: WritableKeyPath<HydraState, Bool>, reconnect: Bool) -> Binding<Bool> {
        Binding(get: { model.state[keyPath: kp] }, set: { v in
            model.mutate { $0[keyPath: kp] = v }
            model.applyTunnelSettings(reconnect: reconnect)
        })
    }
}

struct RoutingSettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var customDns = ""
    @State private var dnsInvalid = false
    @State private var newProfile = ""
    @State private var naming = false
    @State private var countryQuery = ""

    var r: RoutingSettings { model.state.routing }

    var body: some View {
        List {
            Section(header: Text(L("profiles_title")), footer: Text(L("profiles_desc"))) {
                if model.state.routingProfiles.isEmpty { Text(L("profiles_empty")).foregroundStyle(Color.hydraMuted) }
                ForEach(model.state.routingProfiles) { p in
                    HStack {
                        Text(p.name)
                        Spacer()
                        Button(L("profiles_apply")) { model.applyRoutingProfile(p) }.buttonStyle(.borderless)
                    }
                    .swipeActions { Button(role: .destructive) { model.deleteRoutingProfile(p) } label: { Text(L("profiles_delete")) } }
                }
                Button(L("profiles_save_current")) { naming = true }
            }

            Section(header: Text(L("dns_label")), footer: Text(L("apply_next_connect"))) {
                Picker(L("dns_label"), selection: set(\.dnsProvider)) {
                    ForEach(DnsProvider.allCases, id: \.self) { p in
                        Text(p == .system ? L("dns_system") : p == .custom ? L("dns_custom") : "\(p.label) · \(p.address ?? "")").tag(p)
                    }
                }
                .pickerStyle(.inline).labelsHidden()
                if r.dnsProvider == .custom {
                    TextField("https://dns.example/dns-query", text: $customDns)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .onAppear { customDns = r.dnsCustomAddress }
                        .onSubmit {
                            if DnsEndpoint.parse(customDns) != nil { dnsInvalid = false; model.mutate { $0.routing.dnsCustomAddress = customDns } }
                            else { dnsInvalid = true }
                        }
                    Text(L(dnsInvalid ? "dns_invalid" : "dns_supported")).font(.caption).foregroundStyle(dnsInvalid ? Color.hydraDanger : Color.hydraMuted)
                }
            }

            Section(header: Text(L("geo_label")), footer: Text(L("geo_desc"))) {
                Picker(L("geo_label"), selection: set(\.geoMode)) {
                    Text(L("geo_off")).tag(GeoRoutingMode.off)
                    Text(L("geo_direct")).tag(GeoRoutingMode.direct)
                    Text(L("geo_via_proxy")).tag(GeoRoutingMode.viaProxy)
                }
                .pickerStyle(.inline).labelsHidden()
                if r.geoMode != .off {
                    Text(r.geoCountries.isEmpty ? L("geo_none_selected") : r.geoCountries.sorted().map(ServerLocation.defaultCountryName).joined(separator: ", "))
                        .font(.footnote).foregroundStyle(r.geoCountries.isEmpty ? Color.hydraDanger : .secondary)
                    TextField(L("geo_search"), text: $countryQuery)
                    ForEach(countries, id: \.self) { cc in
                        Button {
                            model.mutate { s in
                                if let i = s.routing.geoCountries.firstIndex(of: cc) { s.routing.geoCountries.remove(at: i) } else { s.routing.geoCountries.append(cc) }
                            }
                        } label: {
                            HStack {
                                Text(ServerLocation.defaultCountryName(cc))
                                Spacer()
                                if r.geoCountries.contains(cc) { Image(systemName: "checkmark") }
                            }
                        }.foregroundStyle(.primary)
                    }
                }
            }

            Section(header: Text(L("frag_label")), footer: Text(L("frag_desc"))) {
                Picker(L("frag_label"), selection: set(\.tlsFragment)) {
                    Text(L("geo_off")).tag(TlsFragmentMode.off)
                    Text(L("frag_record")).tag(TlsFragmentMode.record)
                    Text(L("frag_tcp")).tag(TlsFragmentMode.tcp)
                }.pickerStyle(.inline).labelsHidden()
            }

            Section(header: Text(L("mtu_label")), footer: Text(L("mtu_desc"))) {
                Picker(L("mtu_label"), selection: set(\.mtu)) {
                    Text(L("mtu_auto")).tag(MtuPreset.auto)
                    Text(L("mtu_1500")).tag(MtuPreset.mtu1500)
                    Text(L("mtu_1400")).tag(MtuPreset.mtu1400)
                    Text(L("mtu_1280")).tag(MtuPreset.mtu1280)
                }.pickerStyle(.inline).labelsHidden()
            }

            Section(header: Text("IPv6"), footer: Text(L("ipv6_desc"))) {
                Picker("IPv6", selection: set(\.ipv6)) {
                    Text(L("ipv6_block")).tag(Ipv6Mode.block)
                    Text(L("ipv6_enable")).tag(Ipv6Mode.enable)
                }.pickerStyle(.inline).labelsHidden()
                Text(L(r.ipv6 == .block ? "ipv6_block_desc" : "ipv6_enable_desc")).font(.caption).foregroundStyle(Color.hydraMuted)
            }

            Section(header: Text(L("leak_title")), footer: Text(L("leak_desc"))) {
                if model.status != .connected { Text(L("leak_connect_first")).foregroundStyle(Color.hydraDanger).font(.footnote) }
                Link(L("leak_open", "ipleak.net"), destination: URL(string: "https://ipleak.net/")!)
                Link(L("leak_open", "browserleaks.com"), destination: URL(string: "https://browserleaks.com/dns")!)
            }
        }
        .navigationTitle(L("set_routing"))
        .alert(L("profiles_name_title"), isPresented: $naming) {
            TextField(L("profiles_name_hint"), text: $newProfile)
            Button(L("action_save")) { model.saveRoutingProfile(newProfile); newProfile = "" }
            Button(L("action_cancel"), role: .cancel) {}
        }
    }

    private var countries: [String] {
        let q = countryQuery.trimmingCharacters(in: .whitespaces)
        // .srs лежат в бандле расширения туннеля (HydraTunnel.appex/geoip) — берём список оттуда.
        let tunnel = Bundle.main.builtInPlugInsURL.flatMap { Bundle(url: $0.appendingPathComponent("HydraTunnel.appex")) }
        let all = (tunnel ?? Bundle.main).paths(forResourcesOfType: "srs", inDirectory: "geoip").map { ($0 as NSString).lastPathComponent.replacingOccurrences(of: ".srs", with: "") }
        let pool = all.isEmpty ? ["ru", "by", "kz", "ua", "cn", "ir", "tr", "de", "nl", "us"] : all
        if q.isEmpty { return r.geoCountries.sorted() }
        return pool.filter { $0.caseInsensitiveCompare(q) == .orderedSame || ServerLocation.defaultCountryName($0).localizedCaseInsensitiveContains(q) }
            .sorted().prefix(30).map { $0 }
    }

    private func set<T>(_ kp: WritableKeyPath<RoutingSettings, T>) -> Binding<T> {
        Binding(get: { model.state.routing[keyPath: kp] }, set: { v in model.mutate { $0.routing[keyPath: kp] = v } })
    }
}

/// По IP/доменам — работает через правила sing-box. По приложениям на iOS нельзя (только MDM).
struct SplitSettings: View {
    @EnvironmentObject var model: AppModel
    @State private var type: NetRuleType = .domainSuffix
    @State private var value = ""

    var body: some View {
        List {
            Section(footer: Text(L("split_apply_live"))) {
                Picker(L("split_by_net"), selection: Binding(get: { model.state.routing.netMode }, set: { v in model.mutate { $0.routing.netMode = v }; model.applyTunnelSettings() })) {
                    Text(L("split_all_traffic")).tag(SplitMode.off)
                    Text(L("split_only_selected")).tag(SplitMode.include)
                    Text(L("split_except_selected")).tag(SplitMode.exclude)
                }.pickerStyle(.segmented)
            }
            if model.state.routing.netMode != .off {
                Section {
                    Picker(L("split_by_net"), selection: $type) {
                        Text(L("rule_ip_cidr")).tag(NetRuleType.ipCidr)
                        Text(L("rule_domain")).tag(NetRuleType.domain)
                        Text(L("rule_domain_suffix")).tag(NetRuleType.domainSuffix)
                        Text(L("rule_domain_keyword")).tag(NetRuleType.domainKeyword)
                    }
                    HStack {
                        TextField(L(type == .ipCidr ? "split_example_ip" : "split_example_domain"), text: $value)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Button(L("action_add")) {
                            let v = value.trimmingCharacters(in: .whitespaces)
                            guard !v.isEmpty else { return }
                            model.mutate { s in
                                if !s.routing.netRules.contains(where: { $0.type == type && $0.value.caseInsensitiveCompare(v) == .orderedSame }) {
                                    s.routing.netRules.append(NetworkRule(type: type, value: v))
                                }
                            }
                            value = ""
                            model.applyTunnelSettings()
                        }
                    }
                }
                Section {
                    ForEach(model.state.routing.netRules, id: \.self) { rule in
                        HStack { Text(rule.value); Spacer(); Text(L(ruleLabel(rule.type))).font(.caption).foregroundStyle(Color.hydraMuted) }
                    }
                    .onDelete { idx in model.mutate { $0.routing.netRules.remove(atOffsets: idx) }; model.applyTunnelSettings() }
                }
            } else {
                Text(L("split_net_off_hint")).font(.footnote).foregroundStyle(Color.hydraMuted)
            }
            Section(L("split_by_apps")) { Text(L("ios_limits_desc")).font(.footnote).foregroundStyle(Color.hydraMuted) }
        }
        .navigationTitle(L("split_title"))
    }

    func ruleLabel(_ t: NetRuleType) -> String {
        switch t { case .ipCidr: "rule_ip_cidr"; case .domain: "rule_domain"; case .domainSuffix: "rule_domain_suffix"; case .domainKeyword: "rule_domain_keyword" }
    }
}

struct HotspotSettings: View {
    @EnvironmentObject var model: AppModel
    @State private var showPass = false
    var body: some View {
        List {
            Section(footer: Text(L("hotspot_enable_desc"))) {
                Toggle(L("hotspot_enable"), isOn: Binding(get: { model.state.app.hotspotEnabled }, set: { v in
                    model.mutate { s in
                        s.app.hotspotEnabled = v
                        if v && s.app.hotspotPassword.isEmpty { s.app.hotspotPassword = String(UUID().uuidString.prefix(10)) }
                    }
                }))
            }
            if model.state.app.hotspotEnabled {
                Section {
                    LabeledContent(L("hotspot_port"), value: String(model.state.app.hotspotPort))
                    LabeledContent(L("hotspot_login"), value: model.state.app.hotspotUser)
                    LabeledContent(L("hotspot_password")) {
                        Text(model.state.app.hideSecrets && !showPass ? SecretMask.dots : model.state.app.hotspotPassword).monospaced()
                    }
                    if model.state.app.hideSecrets { Button(L(showPass ? "secret_hide" : "secret_show")) { showPass.toggle() } }
                    Button(L("hotspot_regenerate")) { model.mutate { $0.app.hotspotPassword = String(UUID().uuidString.prefix(10)) } }
                }
            }
            Section { Text(L("hotspot_apply_note")).font(.footnote); Text(L("hotspot_warning")).font(.footnote).foregroundStyle(Color.hydraDanger) }
        }
        .navigationTitle(L("hotspot_title"))
    }
}

struct LogsView: View {
    @EnvironmentObject var model: AppModel
    @State private var lines: [String] = []
    @State private var filter = 0
    var body: some View {
        List {
            Picker("", selection: $filter) {
                Text(L("log_filter_all")).tag(0); Text(L("log_filter_warn")).tag(1); Text(L("log_filter_error")).tag(2)
            }.pickerStyle(.segmented)
            ForEach(Array(shown.enumerated()), id: \.offset) { _, l in
                Text(l).font(.caption2.monospaced()).foregroundStyle(l.contains("Ошибка") || l.contains("ERROR") ? Color.hydraDanger : .primary)
            }
        }
        .navigationTitle(L("log_title"))
        .toolbar {
            ShareLink(item: lines.joined(separator: "\n")) { Image(systemName: "square.and.arrow.up") }
            Button(L("log_clear")) { model.store.clearLog(); lines = [] }
        }
        .onAppear { lines = model.store.readLog() }
        .refreshable { lines = model.store.readLog() }
    }
    var shown: [String] {
        switch filter {
        case 1: lines.filter { $0.contains("WARN") || $0.contains("Ошибка") || $0.contains("ERROR") }
        case 2: lines.filter { $0.contains("Ошибка") || $0.contains("ERROR") }
        default: lines
        }.reversed()
    }
}

struct ThemeSettings: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        List {
            ForEach(HydraTheme.allCases) { t in
                Button { model.mutate { $0.app.theme = t.rawValue } } label: {
                    HStack {
                        Circle().fill(t.accent).frame(width: 24, height: 24).overlay(Circle().stroke(t.bg, lineWidth: 4))
                        Text(L(t.labelKey))
                        Spacer()
                        if model.state.app.theme == t.rawValue { Image(systemName: "checkmark") }
                    }
                }.foregroundStyle(.primary)
            }
        }
        .navigationTitle(L("theme_title"))
    }
}

struct LanguageSettings: View {
    var body: some View {
        List {
            Section(footer: Text(L("ios_lang_hint"))) {
                Button(L("ios_open_settings")) { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
            }
        }
        .navigationTitle(L("lang_title"))
    }
}

struct BackupSettings: View {
    @EnvironmentObject var model: AppModel
    @State private var exporting = false
    @State private var importing = false
    @State private var confirmReset = false
    @State private var doc: BackupDocument?
    var body: some View {
        List {
            Section(footer: Text(L("backup_save_desc"))) {
                Button(L("backup_save_btn")) { if let d = model.exportBackup() { doc = BackupDocument(data: d); exporting = true } }
            }
            Section(footer: Text(L("backup_restore_desc"))) { Button(L("backup_restore_btn")) { importing = true } }
            Section(footer: Text(L("backup_reset_desc"))) {
                Button(L("backup_reset_btn"), role: .destructive) { confirmReset = true }
            }
        }
        .navigationTitle(L("set_backup"))
        .fileExporter(isPresented: $exporting, document: doc, contentType: .json,
                      defaultFilename: "hydra-backup-\(Date().formatted(.iso8601.year().month().day()))") { _ in }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let d = try? Data(contentsOf: url) { model.importBackup(d) }
        }
        .confirmationDialog(L("backup_reset_q"), isPresented: $confirmReset) {
            Button(L("backup_reset_confirm"), role: .destructive) { model.resetSettings() }
        } message: { Text(L("backup_reset_q_desc")) }
    }
}

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration _: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct AboutSettings: View {
    var body: some View {
        List {
            Section { Text(L("about_text", HydraDevice.version)) }
            Section(L("ios_limits_title")) { Text(L("ios_limits_desc")).font(.footnote) }
            Section { Link("GitHub", destination: URL(string: "https://github.com/Chistovik92/HydraVPN")!) }
        }
        .navigationTitle(L("set_about"))
    }
}
