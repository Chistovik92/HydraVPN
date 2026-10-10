package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.dpi.CustomSiteList
import ru.gidravpn.hydra.data.dpi.DpiArgs
import ru.gidravpn.hydra.data.dpi.DpiProbe
import ru.gidravpn.hydra.data.dpi.DpiStrategies
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.routing.RouteConfig
import ru.gidravpn.hydra.data.routing.RouteGroup
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RouteRule
import ru.gidravpn.hydra.data.routing.RouteTarget
import ru.gidravpn.hydra.data.routing.viaOf
import ru.gidravpn.hydra.data.tgws.TgWsPreset
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.DropdownField
import ru.gidravpn.hydra.ui.components.PickerDialog
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

/*
 * Карточки раздела «Маршрутизация» (0.7.14). Раньше они лежали одной простынёй на экране «Обход DPI и выходы»;
 * теперь каждая — на своей вкладке (см. RoutingHubScreen), а выбор из списков сделан выпадающими полями.
 */

/** Мастер подбора обхода: что должно открываться → перебор стратегий → лучшие. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DpiWizardCard(vm: MainViewModel) {
    val probe by vm.dpiProbe.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dpi_wiz_title), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.dpi_wiz_sub), color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        val cfg by vm.routeConfig.collectAsState()
        val ps = cfg.dpi.probe
        var extra by rememberSaveable { mutableStateOf("") }
        var full by rememberSaveable { mutableStateOf(false) }
        var listsDialog by remember { mutableStateOf(false) }
        var showAll by rememberSaveable { mutableStateOf(false) }
        val groupLabels = siteGroupLabels()
        val chosen = ps.selectedSites().keys.joinToString(", ") { groupLabels[it] ?: it }.ifEmpty { stringResource(R.string.dpi_ps_none) }
        DropdownLike(stringResource(R.string.dpi_ps_lists), chosen) { listsDialog = true }
        OutlinedTextField(value = extra, onValueChange = { extra = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            label = { Text(stringResource(R.string.dpi_wiz_site), fontSize = 11.sp, color = TextMuted) },
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
        if (listsDialog) SiteListsDialog(vm, onDismiss = { listsDialog = false })
        Spacer(Modifier.height(6.dp))
        DropdownField(
            stringResource(R.string.dpi_wiz_depth),
            stringResource(if (full) R.string.dpi_wiz_full else R.string.dpi_wiz_quick),
            listOf(false to stringResource(R.string.dpi_wiz_quick), true to stringResource(R.string.dpi_wiz_full)),
            { full = it }, selected = full,
        )
        Spacer(Modifier.height(10.dp))
        if (probe.running) Chip(stringResource(R.string.dpi_wiz_stop), false) { vm.cancelProbe() }
        else Chip(stringResource(R.string.dpi_wiz_start), false) { vm.probeDpi(extra, full) }

        if (probe.running) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { if (probe.total == 0) 0f else probe.done.toFloat() / probe.total },
                modifier = Modifier.fillMaxWidth(), color = AccentCyan)
            Text(stringResource(R.string.dpi_wiz_progress, probe.done, probe.total), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        probe.error?.let { Text(it, color = Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }
        probe.baseline?.let { b ->
            Text(if (b.percent >= 95) stringResource(R.string.dpi_wiz_base_ok, b.percent) else stringResource(R.string.dpi_wiz_base, b.percent),
                color = if (b.percent >= 95) AccentCyan else TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        val shown = if (showAll) probe.results else probe.results.take(3)
        shown.forEachIndexed { i, r ->
            Spacer(Modifier.height(8.dp))
            StrategyResultCard(vm, i, r, groupLabels, best = i == 0)
        }
        if (probe.results.size > 3) {
            Spacer(Modifier.height(8.dp))
            Chip(if (showAll) stringResource(R.string.dpi_res_less) else stringResource(R.string.dpi_res_all, probe.results.size), showAll) { showAll = !showAll }
        }
        if (probe.finished) {
            val best = probe.results.firstOrNull()
            Text(if (best == null || best.ok == 0) stringResource(R.string.dpi_wiz_none) else stringResource(R.string.dpi_wiz_done),
                color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            // Telegram не открылся ни с одной стратегией — предлагаем другой путь.
            val tgFailed = "telegram" in ps.groups && (best == null || best.groups.any { it.name == "telegram" && it.ok < it.total })
            if (tgFailed && !TgWsPreset.isApplied(vm.routeConfig.value.rules)) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tgws_wizard_hint), color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Chip(stringResource(R.string.tgws_preset), false) { vm.toggleTgWsPreset() }
            }
        }
    }
}

/** Движок обхода DPI: тумблер и выбор стратегии из списка (60 готовых + своя строка). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DpiEngineCard(vm: MainViewModel) {
    val cfg by vm.routeConfig.collectAsState()
    var picker by remember { mutableStateOf(false) }
    var custom by remember(cfg.dpi.strategy) { mutableStateOf(cfg.dpi.strategy) }
    val idx = DpiStrategies.PRESETS.indexOf(cfg.dpi.strategy)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dpi_enable), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.dpi_enable_sub), color = TextMuted, fontSize = 11.sp)
            }
            Switch(checked = cfg.dpi.enabled, onCheckedChange = { v -> vm.setDpi { it.copy(enabled = v) } })
        }
        Spacer(Modifier.height(12.dp))
        DropdownLike(
            label = stringResource(R.string.dpi_strategy_label),
            value = if (idx >= 0) stringResource(R.string.dpi_strategy_n, idx + 1, DpiStrategies.PRESETS.size) else stringResource(R.string.dpi_strategy_custom),
            onClick = { picker = true },
        )
        // Строка стратегии целиком (раньше обрезалась до двух строк) — выделяется и копируется.
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(cfg.dpi.strategy, color = TextSecondary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.dpi_strategy_manual), color = TextSecondary, fontSize = 12.sp)
        OutlinedTextField(
            value = custom, onValueChange = { custom = it }, modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 12.sp),
            isError = !DpiArgs.isUsable(custom),
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.dpi_apply), false) { vm.setDpi { it.copy(strategy = custom.trim().ifBlank { DpiStrategies.DEFAULT }) } }
            Chip(stringResource(R.string.dpi_add_profile), false) { vm.addByeDpiProfile(cfg.dpi.strategy) }
        }
    }
    if (picker) {
        PickerDialog(
            title = stringResource(R.string.dpi_pick_strategy), items = DpiStrategies.PRESETS, selected = cfg.dpi.strategy,
            itemTitle = { i, s -> "${i + 1}. $s" },
            onPick = { s -> custom = s; vm.setDpi { it.copy(strategy = s) }; picker = false },
            onDismiss = { picker = false },
        )
    }
}

/** Поле-«выпадашка», открывающее своё окно выбора (длинные списки: стратегии, приложения). */
@Composable
internal fun DropdownLike(label: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(InputBg).border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("▾", color = TextMuted, fontSize = 14.sp)
        }
    }
}

/** Telegram через WebSocket (порт tg-ws-proxy): когда ByeDPI не помогает с Telegram. */
@Composable
internal fun TgWsCard(vm: MainViewModel) {
    val cfg by vm.routeConfig.collectAsState()
    val on = TgWsPreset.isApplied(cfg.rules)
    Card(Modifier.fillMaxWidth(), borderColor = if (on) AccentCyan else Border) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tgws_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.tgws_sub), color = TextMuted, fontSize = 11.sp)
            }
            Switch(checked = on, onCheckedChange = { vm.toggleTgWsPreset() })
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.tgws_note), color = TextMuted, fontSize = 11.sp)
    }
}

/** Варианты выхода для выпадающего списка: VPN, напрямую, обход DPI, Telegram WS, блок, группы, серверы. */
@Composable
internal fun exitOptions(cfg: RouteConfig, servers: List<ServerProfile>, withBlock: Boolean = true): List<Pair<String, String>> {
    val nodes = servers.filter { it.protocol?.engine == Engine.SINGBOX }
    val base = listOf(RouteTarget.PROXY, RouteTarget.DIRECT, RouteTarget.DPI, RouteTarget.TGWS) + (if (withBlock) listOf(RouteTarget.BLOCK) else emptyList())
    return (base + cfg.groups.map { it.tag } + nodes.map { RouteTarget.node(it.id) }).map { it to targetLabel(it, servers, cfg.groups) }
}

/**
 * Правила «что → через какой выход». [apps] != null — правила приложений (выбор из установленных),
 * иначе — сайты, IP, страны, порты.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RulesCard(vm: MainViewModel, apps: List<AppEntry>?) {
    val cfg by vm.routeConfig.collectAsState()
    val servers by vm.servers.collectAsState()
    val presetRules = remember { TgWsPreset.rules().toSet() }
    val rules = cfg.rules.filter { r ->
        r !in presetRules && if (apps != null) r.kind == RouteKind.APP else (r.kind != RouteKind.APP && r.kind != RouteKind.PROCESS)
    }
    val tgOn = apps == null && TgWsPreset.isApplied(cfg.rules)
    val exits = exitOptions(cfg, servers)
    var kind by remember { mutableStateOf(RouteKind.DOMAIN) }
    var value by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(RouteTarget.DIRECT) }
    var invert by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    var appPicker by remember { mutableStateOf(false) }
    val kinds = RouteKind.entries.filter { it != RouteKind.PROCESS && it != RouteKind.APP }

    Card(Modifier.fillMaxWidth()) {
        Text(stringResource(if (apps != null) R.string.rh_app_rules_title else R.string.rh_site_rules_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(if (apps != null) R.string.rh_app_rules_sub else R.string.rh_site_rules_sub), color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        if (rules.isEmpty() && !tgOn) Text(stringResource(R.string.dpi_rules_empty), color = TextMuted, fontSize = 12.sp)
        if (tgOn) RuleRow("Telegram  →  ${targetLabel(RouteTarget.TGWS, servers, cfg.groups)}", onRemove = { vm.toggleTgWsPreset() })
        rules.forEach { r ->
            val not = if (r.invert) "НЕ " else ""
            val grp = if (r.group.isNotBlank()) " [${r.group}]" else ""
            val what = if (r.kind == RouteKind.APP) (apps?.firstOrNull { it.packageName == r.value }?.label ?: r.value) else "${kindLabel(r.kind)}  ${r.value}"
            RuleRow("$not$what$grp  →  ${targetLabel(r.target, servers, cfg.groups)}", onRemove = { vm.removeRouteRule(r) })
        }
        Spacer(Modifier.height(10.dp))
        if (apps != null) {
            val shown = apps.firstOrNull { it.packageName == value }?.label ?: stringResource(R.string.rh_pick_app)
            DropdownLike(stringResource(R.string.rh_app), shown) { appPicker = true }
        } else {
            DropdownField(stringResource(R.string.rh_kind), kindLabel(kind), kinds.map { it to kindLabel(it) }, { kind = it }, selected = kind)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(kindHint(kind)), fontSize = 11.sp, color = TextMuted) },
                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
        }
        Spacer(Modifier.height(8.dp))
        DropdownField(stringResource(R.string.rh_exit), targetLabel(target, servers, cfg.groups), exits, { target = it }, selected = target)
        if (apps == null) {
            Spacer(Modifier.height(6.dp))
            Text((if (more) "▾ " else "▸ ") + stringResource(R.string.rh_more), color = AccentCyan, fontSize = 12.sp,
                modifier = Modifier.clickableNoRipple { more = !more }.padding(vertical = 6.dp))
            if (more) {
                Text(stringResource(R.string.rh_more_hint), color = TextMuted, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Chip(stringResource(R.string.dpi_not), invert) { invert = !invert }
                    OutlinedTextField(value = group, onValueChange = { group = it }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.dpi_and_group), fontSize = 11.sp, color = TextMuted) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.dpi_rule_add), false) {
                if (value.isNotBlank()) {
                    vm.addRouteRule(RouteRule(if (apps != null) RouteKind.APP else kind, value.trim(), target, invert, group.trim()))
                    value = ""
                }
            }
            if (apps == null) {
                Chip(stringResource(R.string.dpi_preset_blocked), false) { vm.applyBlockedRuPreset() }
                Chip(stringResource(R.string.tgws_preset), tgOn) { vm.toggleTgWsPreset() }
            }
        }
    }
    if (appPicker && apps != null) {
        PickerDialog(
            title = stringResource(R.string.rh_pick_app), items = apps, selected = apps.firstOrNull { it.packageName == value },
            itemTitle = { _, a -> a.label }, subtitle = { it.packageName },
            onPick = { value = it.packageName; appPicker = false }, onDismiss = { appPicker = false },
        )
    }
}

@Composable
private fun RuleRow(text: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("✕", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickableNoRipple(onRemove).padding(8.dp))
    }
}

/** Группы выходов: «авто» (самый быстрый живой) или «ручной»; члены выбираются галочками в окне. */
@Composable
internal fun GroupsCard(vm: MainViewModel) {
    val cfg by vm.routeConfig.collectAsState()
    val servers by vm.servers.collectAsState()
    var gname by remember { mutableStateOf("") }
    var gtype by remember { mutableStateOf(RouteGroup.TYPE_URLTEST) }
    var members by remember { mutableStateOf(setOf<String>()) }
    var picker by remember { mutableStateOf(false) }
    val nodes = servers.filter { it.protocol?.engine == Engine.SINGBOX }
    val options = listOf(RouteTarget.PROXY, RouteTarget.DIRECT, RouteTarget.DPI, RouteTarget.TGWS) + nodes.map { RouteTarget.node(it.id) }
    val autoFull = stringResource(R.string.rh_group_auto_full)
    val manualFull = stringResource(R.string.rh_group_manual_full)
    Card(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dpi_groups_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.dpi_groups_sub), color = TextMuted, fontSize = 11.sp)
        cfg.groups.forEach { g ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("◎ ${g.name} · ${if (g.type == RouteGroup.TYPE_URLTEST) stringResource(R.string.dpi_g_auto) else stringResource(R.string.dpi_g_manual)}: " +
                    g.members.map { targetLabel(it, servers, cfg.groups) }.joinToString(", "), color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("✕", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickableNoRipple { vm.removeRouteGroup(g) }.padding(8.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = gname, onValueChange = { gname = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.dpi_group_name), fontSize = 11.sp, color = TextMuted) },
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
        Spacer(Modifier.height(8.dp))
        DropdownField(stringResource(R.string.rh_group_type), if (gtype == RouteGroup.TYPE_URLTEST) autoFull else manualFull,
            listOf(RouteGroup.TYPE_URLTEST to autoFull, RouteGroup.TYPE_SELECTOR to manualFull), { gtype = it }, selected = gtype)
        Spacer(Modifier.height(8.dp))
        val membersText = if (members.isEmpty()) stringResource(R.string.rh_group_pick) else members.map { targetLabel(it, servers, cfg.groups) }.joinToString(", ")
        DropdownLike(stringResource(R.string.rh_group_members), membersText) { picker = true }
        Spacer(Modifier.height(10.dp))
        Chip(stringResource(R.string.dpi_group_add), false) {
            if (gname.isNotBlank() && members.isNotEmpty()) { vm.addRouteGroup(RouteGroup(gname.trim(), gtype, members.toList())); gname = ""; members = emptySet() }
        }
    }
    if (picker) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { picker = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(CardBg).border(1.dp, Border, RoundedCornerShape(20.dp)).padding(vertical = 16.dp)) {
                Text(stringResource(R.string.rh_group_members), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(options.size) { i ->
                        val t = options[i]
                        Row(Modifier.fillMaxWidth().clickableNoRipple { members = if (t in members) members - t else members + t }.padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = t in members, onCheckedChange = null)
                            Spacer(Modifier.width(10.dp))
                            Text(targetLabel(t, servers, cfg.groups), color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }
                Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Chip(stringResource(R.string.rh_done), false) { picker = false } }
            }
        }
    }
}

/** Цепочки «сервер через обход DPI / через другой сервер» — по выпадающему списку на каждый сервер. */
@Composable
internal fun ChainsCard(vm: MainViewModel) {
    val servers by vm.servers.collectAsState()
    val nodes = servers.filter { it.protocol?.engine == Engine.SINGBOX }
    Card(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dpi_chain_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.dpi_chain_sub), color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        val chainable = nodes.filter { it.protocolId in setOf("vless", "vmess", "trojan", "ss") }
        if (chainable.isEmpty()) Text(stringResource(R.string.dpi_chain_none_servers), color = TextMuted, fontSize = 12.sp)
        val direct = stringResource(R.string.dpi_chain_direct)
        chainable.forEach { s ->
            val via = viaOf(s)
            val opts = listOf<String?>(null, RouteTarget.DPI) + nodes.filter { it.id != s.id }.map { RouteTarget.node(it.id) }
            DropdownField<String?>(
                label = s.name, value = if (via == null) direct else targetLabel(via, servers),
                options = opts.map { it to (if (it == null) direct else targetLabel(it, servers)) },
                onSelect = { vm.setServerVia(s, it) }, selected = via,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(stringResource(R.string.dpi_chain_udp_note), color = TextMuted, fontSize = 11.sp)
    }
}

internal fun kindHint(k: RouteKind): Int = when (k) {
    RouteKind.DOMAIN -> R.string.rh_hint_domain
    RouteKind.SUFFIX -> R.string.rh_hint_suffix
    RouteKind.KEYWORD -> R.string.rh_hint_keyword
    RouteKind.REGEX -> R.string.rh_hint_regex
    RouteKind.CIDR -> R.string.rh_hint_cidr
    RouteKind.SRC_CIDR -> R.string.rh_hint_src
    RouteKind.PORT -> R.string.rh_hint_port
    RouteKind.PROTOCOL -> R.string.rh_hint_protocol
    RouteKind.NETWORK -> R.string.rh_hint_network
    RouteKind.GEOIP -> R.string.rh_hint_geoip
    RouteKind.GEOSITE -> R.string.rh_hint_geosite
    else -> R.string.dpi_rule_value
}

@Composable
internal fun kindLabel(k: RouteKind): String = stringResource(when (k) {
    RouteKind.APP -> R.string.dpi_k_app
    RouteKind.PROCESS -> R.string.dpi_k_process
    RouteKind.DOMAIN -> R.string.dpi_k_domain
    RouteKind.SUFFIX -> R.string.dpi_k_suffix
    RouteKind.KEYWORD -> R.string.dpi_k_keyword
    RouteKind.REGEX -> R.string.dpi_k_regex
    RouteKind.SRC_CIDR -> R.string.dpi_k_src
    RouteKind.PROTOCOL -> R.string.dpi_k_protocol
    RouteKind.NETWORK -> R.string.dpi_k_network
    RouteKind.CIDR -> R.string.dpi_k_cidr
    RouteKind.PORT -> R.string.dpi_k_port
    RouteKind.GEOIP -> R.string.dpi_k_geoip
    RouteKind.GEOSITE -> R.string.dpi_k_geosite
})

@Composable
internal fun targetLabel(t: String, servers: List<ServerProfile>, groups: List<RouteGroup> = emptyList()): String = when (t) {
    RouteTarget.PROXY -> stringResource(R.string.dpi_t_proxy)
    RouteTarget.DIRECT -> stringResource(R.string.dpi_t_direct)
    RouteTarget.DPI -> stringResource(R.string.dpi_t_dpi)
    RouteTarget.TGWS -> stringResource(R.string.dpi_t_tgws)
    RouteTarget.BLOCK -> stringResource(R.string.dpi_t_block)
    else -> RouteTarget.nodeId(t)?.let { id -> servers.firstOrNull { it.id == id }?.name }
        ?: groups.firstOrNull { it.tag == t }?.let { "◎ ${it.name}" } ?: t
}

@Composable
internal fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (active) AccentCyan.copy(alpha = 0.15f) else CardBg)
            .border(1.dp, if (active) AccentCyan else Border, RoundedCornerShape(10.dp))
            .clickableNoRipple(onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (active) AccentCyan else TextSecondary, fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// 0.7.15: настройки подбора как в ByeByeDPI, полный просмотр стратегий, Telegram → локальный прокси
// ---------------------------------------------------------------------------------------------------------------------

/** Названия встроенных списков доменов. */
@Composable
internal fun siteGroupLabels(): Map<String, String> = mapOf(
    "youtube" to stringResource(R.string.dpi_g_youtube), "discord" to stringResource(R.string.dpi_g_discord),
    "telegram" to stringResource(R.string.dpi_g_telegram), "general" to stringResource(R.string.dpi_g_general),
    "cloudflare" to stringResource(R.string.dpi_g_cloudflare), "googlevideo" to stringResource(R.string.dpi_g_googlevideo),
    "social" to stringResource(R.string.dpi_g_social), "turkiye" to stringResource(R.string.dpi_g_turkiye),
    "custom" to stringResource(R.string.dpi_wiz_custom),
)

/** Окно «Списки доменов»: галочки у встроенных и своих списков (с первыми доменами), «Добавить список», удаление своих. */
@Composable
internal fun SiteListsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val cfg by vm.routeConfig.collectAsState()
    val ps = cfg.dpi.probe
    val labels = siteGroupLabels()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var domains by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedCornerShape(20.dp)).background(CardBg).border(1.dp, Border, RoundedCornerShape(20.dp)).padding(vertical = 16.dp)) {
            Text(stringResource(R.string.dpi_ps_lists), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                val builtIn = DpiStrategies.GROUP_ORDER
                items(builtIn.size) { i ->
                    val g = builtIn[i]
                    SiteListRow(labels[g] ?: g, DpiStrategies.SITES[g].orEmpty(), g in ps.groups, null) {
                        vm.setProbe { p -> p.copy(groups = if (g in p.groups) p.groups - g else p.groups + g) }
                    }
                }
                items(ps.custom.size) { i ->
                    val c = ps.custom[i]
                    SiteListRow(c.name, c.domains, c.name in ps.groups, { vm.setProbe { p -> p.copy(custom = p.custom - c, groups = p.groups - c.name) } }) {
                        vm.setProbe { p -> p.copy(groups = if (c.name in p.groups) p.groups - c.name else p.groups + c.name) }
                    }
                }
                item {
                    if (adding) Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.dpi_ps_list_name), fontSize = 11.sp, color = TextMuted) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
                        OutlinedTextField(value = domains, onValueChange = { domains = it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), minLines = 3,
                            label = { Text(stringResource(R.string.dpi_ps_list_domains), fontSize = 11.sp, color = TextMuted) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 12.sp))
                        Spacer(Modifier.height(8.dp))
                        Chip(stringResource(R.string.dpi_ps_list_save), false) {
                            val list = domains.split('\n', ' ', ',', ';').map { it.trim().removePrefix("https://").removePrefix("http://").substringBefore('/') }.filter { it.isNotEmpty() }.distinct()
                            val n = name.trim()
                            if (n.isNotEmpty() && list.isNotEmpty() && n !in labels.keys && n.lowercase() !in builtIn) {
                                vm.setProbe { p -> p.copy(custom = p.custom.filterNot { it.name == n } + CustomSiteList(n, list), groups = p.groups + n) }
                                name = ""; domains = ""; adding = false
                            }
                        }
                    } else Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Chip(stringResource(R.string.dpi_ps_list_add), false) { adding = true } }
                }
            }
            Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Chip(stringResource(R.string.rh_done), false, onDismiss) }
        }
    }
}

@Composable
private fun SiteListRow(title: String, domains: List<String>, on: Boolean, onDelete: (() -> Unit)?, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickableNoRipple(onToggle).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(domains.take(4).joinToString("\n") + if (domains.size > 4) "\n…" else "", color = TextMuted, fontSize = 11.sp)
        }
        if (onDelete != null) Text("✕", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickableNoRipple(onDelete).padding(8.dp))
        Checkbox(checked = on, onCheckedChange = null)
    }
}

/** Результат подбора целиком: сводка по спискам, ПОЛНАЯ строка стратегии (можно скопировать), «Использовать», «Сохранить профилем». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StrategyResultCard(vm: MainViewModel, i: Int, r: DpiProbe.Result, labels: Map<String, String>, best: Boolean) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, if (best) AccentCyan else Border, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(stringResource(R.string.dpi_wiz_variant, i + 1, r.ok, r.total, r.percent), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(r.groups.joinToString(" · ") { "${labels[it.name] ?: it.name} ${it.ok}/${it.total}" }, color = TextMuted, fontSize = 11.sp)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(r.strategy, color = TextSecondary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.dpi_wiz_use), false) { vm.useDpiStrategy(r.strategy) }
            Chip(stringResource(R.string.dpi_wiz_save), false) { vm.addByeDpiProfile(r.strategy) }
            Chip(stringResource(if (copied) R.string.dpi_res_copied else R.string.dpi_res_copy), copied) { clip.setText(androidx.compose.ui.text.AnnotatedString(r.strategy)); copied = true }
        }
    }
}

/** «Настройки подбора» — те же параметры, что в ByeByeDPI: пауза, число запросов, параллельность, таймаут, SNI, свой список стратегий. */
@Composable
internal fun DpiProbeSettingsCard(vm: MainViewModel) {
    val cfg by vm.routeConfig.collectAsState()
    val ps = cfg.dpi.probe
    var sni by remember(cfg.dpi.fakeSni) { mutableStateOf(cfg.dpi.fakeSni) }
    var own by remember(ps.customStrategies) { mutableStateOf(ps.customStrategies) }
    val sec = stringResource(R.string.dpi_ps_sec)
    Card(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dpi_ps_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.dpi_ps_sub), color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        DropdownField(stringResource(R.string.dpi_ps_delay), "${ps.delaySec} $sec", (0..10).map { it to "$it $sec" }, { v -> vm.setProbe { it.copy(delaySec = v) } }, selected = ps.delaySec)
        Text(stringResource(R.string.dpi_ps_delay_hint), color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        DropdownField(stringResource(R.string.dpi_ps_requests), "${ps.requests}", (1..5).map { it to "$it" }, { v -> vm.setProbe { it.copy(requests = v) } }, selected = ps.requests)
        Text(stringResource(R.string.dpi_ps_requests_hint), color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        DropdownField(stringResource(R.string.dpi_ps_parallel), "${ps.parallel}", listOf(1, 2, 4, 8, 12, 16, 20, 32, 50).map { it to "$it" }, { v -> vm.setProbe { it.copy(parallel = v) } }, selected = ps.parallel)
        Text(stringResource(R.string.dpi_ps_parallel_hint), color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        DropdownField(stringResource(R.string.dpi_ps_timeout), "${ps.timeoutSec} $sec", (1..15).map { it to "$it $sec" }, { v -> vm.setProbe { it.copy(timeoutSec = v) } }, selected = ps.timeoutSec)
        Text(stringResource(R.string.dpi_ps_timeout_hint), color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        OutlinedTextField(value = sni, onValueChange = { sni = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.dpi_ps_sni), fontSize = 11.sp, color = TextMuted) },
            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
        Text(stringResource(R.string.dpi_ps_sni_hint), color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(6.dp))
        Chip(stringResource(R.string.dpi_apply), false) { vm.setDpi { it.copy(fakeSni = sni.ifBlank { DpiStrategies.FAKE_SNI }) } }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dpi_ps_own), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.dpi_ps_own_hint), color = TextMuted, fontSize = 10.sp)
            }
            Switch(checked = ps.customStrategiesOn, onCheckedChange = { v -> vm.setProbe { it.copy(customStrategiesOn = v) } })
        }
        if (ps.customStrategiesOn) {
            OutlinedTextField(value = own, onValueChange = { own = it }, minLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            Spacer(Modifier.height(6.dp))
            Chip(stringResource(R.string.dpi_apply), false) { vm.setProbe { it.copy(customStrategies = own) } }
        }
    }
}

/**
 * Передать Telegram локальный прокси Hydra (кнопка в настройках обхода и на главном экране): в Telegram откроется окно
 * «Включить прокси?». Порт — TG WS, если он включён, иначе ByeDPI. Прокси работает, пока Hydra подключена.
 */
@Composable
internal fun TelegramProxyCard(vm: MainViewModel, compact: Boolean = false, profilePort: Int? = null) {
    val cfg by vm.routeConfig.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tg = TgWsPreset.isApplied(cfg.rules)
    val port = if (tg) RouteTarget.TGWS_MT_PORT else (profilePort ?: cfg.dpi.port)
    // TG WS - MTProto-прокси (его без сбоев принимает и Telegram для Android), ByeDPI и профили - SOCKS5.
    val link = if (tg) ru.gidravpn.hydra.data.tgws.TgWsProxy.mtProxyLink(port) else ru.gidravpn.hydra.data.tgws.TgWsProxy.socksLink(port)
    var missing by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        if (!compact) {
            Text(stringResource(R.string.tgp_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.tgp_sub), color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
        }
        Text(stringResource(if (tg) R.string.tgp_addr_mt else R.string.tgp_addr, port) + " · " + stringResource(if (tg) R.string.tgp_via_tgws else R.string.tgp_via_dpi), color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Chip(stringResource(R.string.tgp_button), false) { missing = !ru.gidravpn.hydra.ui.openTelegramProxy(ctx, link) }
        if (missing) Text(stringResource(R.string.tgp_missing), color = Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Text(stringResource(R.string.tgp_need_on), color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
