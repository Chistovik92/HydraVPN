import HydraKit
import SwiftUI

/// «Обход DPI и выходы» (0.7.13) — то же, что на Android и ПК: мастер подбора, ByeDPI для трафика напрямую, правила
/// «что → через какой выход», группы выходов и цепочки. Правил по приложениям на iOS нет (система не сообщает пакет).
struct DpiRoutesView: View {
    @EnvironmentObject var model: AppModel
    @StateObject private var wizard = DpiWizardModel()

    @State private var picked: Set<String> = ["youtube", "discord", "telegram"]
    @State private var extraSite = ""
    @State private var full = false
    @State private var strategy = ""
    @State private var showPresets = false

    // правило
    @State private var kind: RouteKind = .suffix
    @State private var value = ""
    @State private var target = RouteTarget.direct
    @State private var invert = false
    @State private var group = ""
    // группа
    @State private var gName = ""
    @State private var gType = RouteGroup.urltest
    @State private var gMembers: Set<String> = []
    // цепочка
    @State private var chainServer: Int64?

    private var cfg: RouteConfig { model.state.routing.routeConfig }
    private var servers: [ServerProfile] { model.state.servers }
    private var nodes: [ServerProfile] { servers.filter { $0.serverProtocol?.engine == .singBox } }
    private let ruleKinds = RouteKind.allCases.filter { $0 != .app && $0 != .process }

    var body: some View {
        List {
            wizardSection
            dpiSection
            rulesSection
            groupsSection
            chainsSection
        }
        .navigationTitle(L("set_dpi"))
        .onAppear { strategy = cfg.dpi.strategy }
    }

    // MARK: - изменение настроек

    private func setRoutes(_ f: (inout RouteConfig) -> Void) {
        model.mutate { var r = $0.routing.routeConfig; f(&r); $0.routing.routes = r }
        model.applyTunnelSettings()
    }

    private func targetName(_ t: String) -> String {
        switch t {
        case RouteTarget.proxy: L("dpi_t_proxy")
        case RouteTarget.direct: L("dpi_t_direct")
        case RouteTarget.dpi: L("dpi_t_dpi")
        case RouteTarget.block: L("dpi_t_block")
        default:
            RouteTarget.nodeId(t).flatMap { id in servers.first { $0.id == id }?.name }
                ?? cfg.groups.first { $0.tag == t }.map { "◎ \($0.name)" } ?? t
        }
    }

    /// Строка правила; собрана по частям — длинное выражение из `+` и интерполяций компилятор не успевает разобрать.
    private func ruleTitle(_ r: RouteRule) -> String {
        var parts: [String] = []
        if r.invert { parts.append(L("dpi_not")) }
        parts.append(kindName(r.kind))
        parts.append(r.value)
        if !r.group.isEmpty { parts.append("[" + r.group + "]") }
        parts.append("→")
        parts.append(targetName(r.target))
        return parts.joined(separator: " ")
    }

    private func kindName(_ k: RouteKind) -> String {
        switch k {
        case .app: L("dpi_k_app")
        case .process: L("dpi_k_process")
        case .domain: L("dpi_k_domain")
        case .suffix: L("dpi_k_suffix")
        case .keyword: L("dpi_k_keyword")
        case .regex: L("dpi_k_regex")
        case .cidr: L("dpi_k_cidr")
        case .srcCidr: L("dpi_k_src")
        case .port: L("dpi_k_port")
        case .proto: L("dpi_k_protocol")
        case .network: L("dpi_k_network")
        case .geoip: L("dpi_k_geoip")
        case .geosite: L("dpi_k_geosite")
        }
    }

    private var allTargets: [String] {
        [RouteTarget.proxy, RouteTarget.direct, RouteTarget.dpi, RouteTarget.block] + cfg.groups.map(\.tag) + nodes.map { RouteTarget.node($0.id) }
    }

    // MARK: - мастер подбора

    private var wizardSection: some View {
        Section(header: Text(L("dpi_wiz_title")), footer: Text(L("dpi_wiz_sub"))) {
            ForEach(["youtube", "discord", "telegram", "general"], id: \.self) { g in
                Toggle(groupLabel(g), isOn: Binding(get: { picked.contains(g) }, set: { on in if on { picked.insert(g) } else { picked.remove(g) } }))
            }
            TextField(L("dpi_wiz_site"), text: $extraSite).textInputAutocapitalization(.never).autocorrectionDisabled()
            Picker("", selection: $full) {
                Text(L("dpi_wiz_quick")).tag(false)
                Text(L("dpi_wiz_full")).tag(true)
            }.pickerStyle(.segmented)
            if model.status.isUp { Text(L("dpi_wiz_vpn_on")).font(.caption).foregroundStyle(.orange) }
            if wizard.running {
                Button(L("dpi_wiz_stop")) { wizard.cancel() }
                ProgressView(value: Double(wizard.done), total: Double(max(wizard.total, 1)))
                Text(L("dpi_wiz_progress", wizard.done, wizard.total)).font(.caption).foregroundStyle(Color.hydraMuted)
            } else {
                Button(L("dpi_wiz_start")) { wizard.start(groups: picked, extra: extraSite, full: full) }
            }
            if let e = wizard.error { Text(e).font(.caption).foregroundStyle(.red) }
            if let b = wizard.baseline {
                Text(b.percent >= 95 ? L("dpi_wiz_base_ok", b.percent) : L("dpi_wiz_base", b.percent))
                    .font(.caption).foregroundStyle(b.percent >= 95 ? Color.green : Color.hydraMuted)
            }
            ForEach(Array(wizard.results.enumerated()), id: \.element.id) { i, r in
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("dpi_wiz_variant", i + 1, r.ok, r.total, r.percent)).font(.subheadline.weight(.semibold))
                    Text(r.groups.map { "\(groupLabel($0.name)) \($0.ok)/\($0.total)" }.joined(separator: " · ")).font(.caption).foregroundStyle(Color.hydraMuted)
                    Text(r.strategy).font(.caption2).foregroundStyle(Color.hydraMuted).lineLimit(2)
                    HStack {
                        Button(L("dpi_wiz_use")) {
                            setRoutes { $0.dpi.enabled = true; $0.dpi.strategy = r.strategy }
                            strategy = r.strategy
                        }.buttonStyle(.borderedProminent)
                        Button(L("dpi_wiz_save")) { addProfile(r.strategy) }.buttonStyle(.bordered)
                    }
                }
            }
            if wizard.finished {
                Text((wizard.results.first?.ok ?? 0) == 0 ? L("dpi_wiz_none") : L("dpi_wiz_done")).font(.caption).foregroundStyle(Color.hydraMuted)
            }
        }
    }

    private func groupLabel(_ g: String) -> String {
        switch g {
        case "youtube": L("dpi_g_youtube")
        case "discord": L("dpi_g_discord")
        case "telegram": L("dpi_g_telegram")
        case "general": L("dpi_g_general")
        default: L("dpi_wiz_custom")
        }
    }

    private func addProfile(_ strategy: String) {
        guard let link = ByeDpiLink.parse("byedpi://?s=" + (strategy.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")) else { return }
        var p = link
        p.name = L("dpi_profile_name")
        model.addServer(p)
    }

    // MARK: - обход DPI

    private var dpiSection: some View {
        Section(header: Text(L("dpi_enable")), footer: Text(L("dpi_intro"))) {
            Toggle(L("dpi_enable_sub"), isOn: Binding(get: { cfg.dpi.enabled }, set: { v in setRoutes { $0.dpi.enabled = v } }))
            TextField(L("dpi_strategy"), text: $strategy, axis: .vertical)
                .textInputAutocapitalization(.never).autocorrectionDisabled().font(.footnote.monospaced())
            HStack {
                Button(L("dpi_apply")) {
                    let s = strategy.trimmingCharacters(in: .whitespaces)
                    setRoutes { $0.dpi.strategy = s.isEmpty ? DpiStrategies.defaultStrategy : s }
                }.disabled(!DpiArgs.isUsable(strategy))
                Spacer()
                Button(L("dpi_presets")) { showPresets.toggle() }
                Spacer()
                Button(L("dpi_add_profile")) { addProfile(cfg.dpi.strategy) }
            }.buttonStyle(.borderless)
            if showPresets {
                ForEach(Array(DpiStrategies.presets.enumerated()), id: \.offset) { i, s in
                    Button { strategy = s; setRoutes { $0.dpi.strategy = s } } label: {
                        Text("\(i + 1). \(s)").font(.caption2.monospaced()).lineLimit(2)
                            .foregroundStyle(s == cfg.dpi.strategy ? Color.accentColor : Color.hydraMuted)
                    }
                }
            }
        }
    }

    // MARK: - правила

    private var rulesSection: some View {
        Section(header: Text(L("dpi_rules_title")), footer: Text(L("dpi_rules_sub"))) {
            if cfg.rules.isEmpty { Text(L("dpi_rules_empty")).foregroundStyle(Color.hydraMuted) }
            ForEach(Array(cfg.rules.enumerated()), id: \.offset) { _, r in
                Text(ruleTitle(r))
                    .font(.footnote)
                    .swipeActions { Button(role: .destructive) { setRoutes { $0.rules.removeAll { $0 == r } } } label: { Text(L("profiles_delete")) } }
            }
            Picker(L("dpi_rules_title"), selection: $kind) { ForEach(ruleKinds, id: \.self) { Text(kindName($0)).tag($0) } }
            TextField(L("dpi_rule_value"), text: $value).textInputAutocapitalization(.never).autocorrectionDisabled()
            Picker(L("dpi_t_title"), selection: $target) { ForEach(allTargets, id: \.self) { Text(targetName($0)).tag($0) } }
            Toggle(L("dpi_not"), isOn: $invert)
            TextField(L("dpi_and_group"), text: $group).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button(L("dpi_rule_add")) {
                let v = value.trimmingCharacters(in: .whitespaces)
                guard !v.isEmpty else { return }
                setRoutes { $0.rules.append(RouteRule(kind: kind, value: v, target: target, invert: invert, group: group.trimmingCharacters(in: .whitespaces))) }
                value = ""
            }
            Button(L("dpi_preset_blocked")) { applyBlockedPreset() }
        }
    }

    /// «Заблокированное в РФ → обход DPI»: наборы ru-blocked есть только у runetfreedom — добавляются как свои источники geo-баз.
    private func applyBlockedPreset() {
        let src = GeoSources.runetfreedom
        model.mutate { s in
            var g = s.routing.geoSettings
            g.custom.removeAll { $0.name == "ru-blocked" }
            g.custom.append(CustomGeoSource(kind: .site, name: "ru-blocked", url: src.site[0].replacingOccurrences(of: "{name}", with: "ru-blocked")))
            g.custom.append(CustomGeoSource(kind: .ip, name: "ru-blocked", url: src.ip[0].replacingOccurrences(of: "{name}", with: "ru-blocked")))
            s.routing.geo = g
            var r = s.routing.routeConfig
            r.dpi.enabled = true
            for rule in [RouteRule(kind: .geosite, value: "ru-blocked", target: RouteTarget.dpi), RouteRule(kind: .geoip, value: "ru-blocked", target: RouteTarget.dpi)]
            where !r.rules.contains(rule) { r.rules.append(rule) }
            s.routing.routes = r
        }
        Task { await model.updateGeo(manual: true) }
    }

    // MARK: - группы

    private var groupsSection: some View {
        Section(header: Text(L("dpi_groups_title")), footer: Text(L("dpi_groups_sub"))) {
            ForEach(cfg.groups, id: \.name) { g in
                Text("◎ \(g.name) · \(g.type == RouteGroup.urltest ? L("dpi_g_auto") : L("dpi_g_manual")): " + g.members.map(targetName).joined(separator: ", "))
                    .font(.footnote)
                    .swipeActions { Button(role: .destructive) { setRoutes { $0.groups.removeAll { $0.name == g.name } } } label: { Text(L("profiles_delete")) } }
            }
            TextField(L("dpi_group_name"), text: $gName)
            Picker("", selection: $gType) {
                Text(L("dpi_g_auto")).tag(RouteGroup.urltest)
                Text(L("dpi_g_manual")).tag(RouteGroup.selector)
            }.pickerStyle(.segmented)
            ForEach([RouteTarget.proxy, RouteTarget.direct, RouteTarget.dpi] + nodes.map { RouteTarget.node($0.id) }, id: \.self) { t in
                Toggle(targetName(t), isOn: Binding(get: { gMembers.contains(t) }, set: { on in if on { gMembers.insert(t) } else { gMembers.remove(t) } }))
            }
            Button(L("dpi_group_add")) {
                let n = gName.trimmingCharacters(in: .whitespaces)
                guard !n.isEmpty, !gMembers.isEmpty else { return }
                let members = gMembers.sorted()
                setRoutes { c in
                    c.groups.removeAll { $0.tag == RouteTarget.group(n) }
                    c.groups.append(RouteGroup(name: n, type: gType, members: members))
                }
                gName = ""; gMembers = []
            }
        }
    }

    // MARK: - цепочки

    private var chainsSection: some View {
        Section(header: Text(L("dpi_chain_title")), footer: Text(L("dpi_chain_udp_note"))) {
            Text(L("dpi_chain_sub")).font(.caption).foregroundStyle(Color.hydraMuted)
            let chainable = servers.filter { ["vless", "vmess", "trojan", "ss"].contains($0.protocolId) }
            if chainable.isEmpty { Text(L("dpi_chain_none_servers")).foregroundStyle(Color.hydraMuted) }
            ForEach(chainable) { s in
                Picker(s.name, selection: Binding(get: { viaOf(s) ?? "" }, set: { v in
                    model.mutate { st in
                        if let i = st.servers.firstIndex(where: { $0.id == s.id }) { st.servers[i] = withVia(st.servers[i], v.isEmpty ? nil : v) }
                    }
                    model.applyTunnelSettings()
                })) {
                    Text(L("dpi_chain_direct")).tag("")
                    Text(L("dpi_t_dpi")).tag(RouteTarget.dpi)
                    ForEach(nodes.filter { $0.id != s.id }) { n in Text(n.name).tag(RouteTarget.node(n.id)) }
                }
            }
        }
    }
}

/// «Geo-базы» (0.7.13): источники, расписание, статус скачанных баз, откат, свои списки.
struct GeoView: View {
    @EnvironmentObject var model: AppModel
    @State private var busy = false
    @State private var message: String?
    @State private var name = ""
    @State private var cKind: GeoKind = .ip
    @State private var cType = CustomGeoSource.typeList
    @State private var cName = ""
    @State private var cUrl = ""

    private var g: GeoSettings { model.state.routing.geoSettings }
    private var entries: [GeoStore.Entry] {
        _ = model.geoVersion
        return GeoStore(directory: model.store.geoDirectory).entries()
    }

    private func set(_ f: (inout GeoSettings) -> Void) {
        model.mutate { var x = $0.routing.geoSettings; f(&x); $0.routing.geo = x }
    }

    var body: some View {
        List {
            Section(footer: Text(L("geo_intro"))) {
                Toggle(L("geo_auto"), isOn: Binding(get: { g.autoUpdate }, set: { v in set { $0.autoUpdate = v } }))
                Picker(L("geo_auto_sub", g.intervalHours), selection: Binding(get: { g.intervalHours }, set: { v in set { $0.intervalHours = v } })) {
                    ForEach([6, 12, 24, 72, 168], id: \.self) { Text(L("geo_hours", $0)).tag($0) }
                }
                Button(busy ? L("geo_updating") : L("geo_update_now")) {
                    busy = true
                    Task { message = await model.updateGeo(manual: true); busy = false }
                }.disabled(busy)
                if let m = message { Text(m).font(.caption).foregroundStyle(Color.hydraMuted) }
            }
            Section(header: Text(L("geo_sources")), footer: Text(L("geo_sources_note"))) {
                Picker(L("geo_source_ip"), selection: Binding(get: { g.ipSource }, set: { v in set { $0.ipSource = v } })) {
                    ForEach(GeoSources.all, id: \.id) { Text($0.title).tag($0.id) }
                }
                Picker(L("geo_source_site"), selection: Binding(get: { g.siteSource }, set: { v in set { $0.siteSource = v } })) {
                    ForEach(GeoSources.all, id: \.id) { Text($0.title).tag($0.id) }
                }
            }
            Section(header: Text(L("geo_extra")), footer: Text(L("geo_extra_sub"))) {
                TextField(L("geo_name_hint"), text: $name).textInputAutocapitalization(.never).autocorrectionDisabled()
                HStack {
                    Button(L("geo_add_ip")) { add(site: false) }
                    Spacer()
                    Button(L("geo_add_site")) { add(site: true) }
                }.buttonStyle(.borderless)
                ForEach(g.extraIp, id: \.self) { n in
                    Text("geoip: \(n)").swipeActions { Button(role: .destructive) { set { $0.extraIp.removeAll { $0 == n } } } label: { Text(L("profiles_delete")) } }
                }
                ForEach(g.extraSite, id: \.self) { n in
                    Text("geosite: \(n)").swipeActions { Button(role: .destructive) { set { $0.extraSite.removeAll { $0 == n } } } label: { Text(L("profiles_delete")) } }
                }
            }
            Section(header: Text(L("geo_custom")), footer: Text(L("geo_custom_sub"))) {
                Picker("", selection: $cKind) { Text("geoip").tag(GeoKind.ip); Text("geosite").tag(GeoKind.site) }.pickerStyle(.segmented)
                Picker("", selection: $cType) {
                    Text(".list").tag(CustomGeoSource.typeList); Text(".srs").tag(CustomGeoSource.typeSrs); Text(".dat").tag(CustomGeoSource.typeDat)
                }.pickerStyle(.segmented)
                TextField(L("geo_name_hint"), text: $cName).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("URL", text: $cUrl).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                Button(L("geo_custom_add")) {
                    let n = cName.trimmingCharacters(in: .whitespaces).lowercased(), u = cUrl.trimmingCharacters(in: .whitespaces)
                    guard !n.isEmpty, u.hasPrefix("https://") else { return }
                    set { x in
                        x.custom.removeAll { $0.kind == cKind && $0.name == n }
                        x.custom.append(CustomGeoSource(kind: cKind, name: n, url: u, type: cType))
                    }
                    cName = ""; cUrl = ""
                }
                ForEach(g.custom, id: \.self) { c in
                    Text("\(c.kind.rawValue)/\(c.name) ← \(c.url)").font(.caption).lineLimit(2)
                        .swipeActions { Button(role: .destructive) { set { $0.custom.removeAll { $0 == c } } } label: { Text(L("profiles_delete")) } }
                }
            }
            Section(header: Text(L("geo_downloaded"))) {
                if entries.isEmpty { Text(L("geo_none")).foregroundStyle(Color.hydraMuted) }
                ForEach(entries) { e in
                    HStack {
                        VStack(alignment: .leading) {
                            Text("\(e.kind.rawValue)/\(e.name)")
                            Text("\(e.source) · \(e.size / 1024) КБ · \(e.updatedAt.formatted(date: .abbreviated, time: .shortened)) · \(e.sha256.prefix(8))")
                                .font(.caption2).foregroundStyle(Color.hydraMuted)
                        }
                        Spacer()
                        if e.hasPrev {
                            Button { _ = GeoStore(directory: model.store.geoDirectory).rollback(e.kind, e.name); model.geoVersion += 1; model.applyTunnelSettings() } label: {
                                Image(systemName: "arrow.uturn.backward")
                            }.buttonStyle(.borderless)
                        }
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            GeoStore(directory: model.store.geoDirectory).remove(e.kind, e.name); model.geoVersion += 1; model.applyTunnelSettings()
                        } label: { Text(L("profiles_delete")) }
                    }
                }
            }
        }
        .navigationTitle(L("set_geo"))
    }

    private func add(site: Bool) {
        let n = name.trimmingCharacters(in: .whitespaces).lowercased()
        guard !n.isEmpty else { return }
        set { x in
            if site { if !x.extraSite.contains(n) { x.extraSite.append(n) } } else if !x.extraIp.contains(n) { x.extraIp.append(n) }
        }
        name = ""
    }
}
