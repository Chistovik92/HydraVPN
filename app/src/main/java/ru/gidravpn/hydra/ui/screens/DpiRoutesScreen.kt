package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import ru.gidravpn.hydra.data.dpi.DpiArgs
import ru.gidravpn.hydra.data.dpi.DpiStrategies
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.routing.RouteGroup
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RouteRule
import ru.gidravpn.hydra.data.routing.RouteTarget
import ru.gidravpn.hydra.data.routing.viaOf
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

/**
 * «Обход DPI и выходы» (0.7.13): ByeDPI для трафика напрямую, правила «приложение / домен / IP / страна → выход»
 * и цепочки «сервер через обход DPI / через другой сервер».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DpiRoutesScreen(vm: MainViewModel) {
    val cfg by vm.routeConfig.collectAsState()
    val servers by vm.servers.collectAsState()
    val probe by vm.dpiProbe.collectAsState()
    var showPresets by remember { mutableStateOf(false) }
    var custom by remember(cfg.dpi.strategy) { mutableStateOf(cfg.dpi.strategy) }
    val nodes = servers.filter { it.protocol?.engine == Engine.SINGBOX }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.dpi_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Text(stringResource(R.string.dpi_intro), color = TextMuted, fontSize = 12.sp)

        // --- мастер подбора ---
        Card(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dpi_wiz_title), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.dpi_wiz_sub), color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            var picked by rememberSaveable { mutableStateOf(setOf("youtube", "discord", "telegram")) }
            var extra by rememberSaveable { mutableStateOf("") }
            var full by rememberSaveable { mutableStateOf(false) }
            val groupLabels = mapOf(
                "youtube" to stringResource(R.string.dpi_g_youtube), "discord" to stringResource(R.string.dpi_g_discord),
                "telegram" to stringResource(R.string.dpi_g_telegram), "general" to stringResource(R.string.dpi_g_general),
                "custom" to stringResource(R.string.dpi_wiz_custom),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("youtube", "discord", "telegram", "general").forEach { g ->
                    Chip((if (g in picked) "✓ " else "") + groupLabels.getValue(g), g in picked) { picked = if (g in picked) picked - g else picked + g }
                }
            }
            OutlinedTextField(value = extra, onValueChange = { extra = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                label = { Text(stringResource(R.string.dpi_wiz_site), fontSize = 11.sp, color = TextMuted) },
                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.dpi_wiz_quick), !full) { full = false }
                Chip(stringResource(R.string.dpi_wiz_full), full) { full = true }
            }
            Spacer(Modifier.height(10.dp))
            if (probe.running) Chip(stringResource(R.string.dpi_wiz_stop), false) { vm.cancelProbe() }
            else Chip(stringResource(R.string.dpi_wiz_start), false) { vm.probeDpi(picked, extra, full) }

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
            probe.results.forEachIndexed { i, r ->
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, if (i == 0) AccentCyan else Border, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Text(stringResource(R.string.dpi_wiz_variant, i + 1, r.ok, r.total, r.percent), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(r.groups.joinToString(" · ") { "${groupLabels[it.name] ?: it.name} ${it.ok}/${it.total}" }, color = TextMuted, fontSize = 11.sp)
                    Text(r.strategy, color = TextSecondary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(stringResource(R.string.dpi_wiz_use), false) { vm.useDpiStrategy(r.strategy) }
                        Chip(stringResource(R.string.dpi_wiz_save), false) { vm.addByeDpiProfile(r.strategy) }
                    }
                }
            }
            if (probe.finished) {
                val best = probe.results.firstOrNull()
                Text(if (best == null || best.ok == 0) stringResource(R.string.dpi_wiz_none) else stringResource(R.string.dpi_wiz_done),
                    color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }

        // --- обход DPI ---
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.dpi_enable), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.dpi_enable_sub), color = TextMuted, fontSize = 11.sp)
                }
                Switch(checked = cfg.dpi.enabled, onCheckedChange = { v -> vm.setDpi { it.copy(enabled = v) } })
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.dpi_strategy), color = TextSecondary, fontSize = 12.sp)
            OutlinedTextField(
                value = custom, onValueChange = { custom = it }, modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 12.sp),
                isError = !DpiArgs.isUsable(custom),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.dpi_apply), false) { vm.setDpi { it.copy(strategy = custom.trim().ifBlank { DpiStrategies.DEFAULT }) } }
                Chip(stringResource(R.string.dpi_presets), showPresets) { showPresets = !showPresets }
                Chip(stringResource(R.string.dpi_add_profile), false) { vm.addByeDpiProfile(cfg.dpi.strategy) }
            }
            if (showPresets) {
                Spacer(Modifier.height(8.dp))
                DpiStrategies.PRESETS.forEachIndexed { i, s ->
                    Text("${i + 1}. $s", color = if (s == cfg.dpi.strategy) AccentCyan else TextSecondary, fontSize = 11.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickableNoRipple { custom = s; vm.setDpi { it.copy(strategy = s) } }.padding(vertical = 5.dp))
                }
            }
        }

        // --- правила ---
        Card(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dpi_rules_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.dpi_rules_sub), color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            val rules = cfg.rules.filter { it.kind != RouteKind.PROCESS }
            if (rules.isEmpty()) Text(stringResource(R.string.dpi_rules_empty), color = TextMuted, fontSize = 12.sp)
            rules.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val not = if (r.invert) "НЕ " else ""
                    val grp = if (r.group.isNotBlank()) " [${r.group}]" else ""
                    Text("$not${kindLabel(r.kind)}  ${r.value}$grp  →  ${targetLabel(r.target, servers, cfg.groups)}", color = TextPrimary, fontSize = 12.sp,
                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("✕", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickableNoRipple { vm.removeRouteRule(r) }.padding(8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            var kind by remember { mutableStateOf(RouteKind.APP) }
            var value by remember { mutableStateOf("") }
            var target by remember { mutableStateOf(RouteTarget.DIRECT) }
            var invert by remember { mutableStateOf(false) }
            var group by remember { mutableStateOf("") }
            val kinds = RouteKind.entries.filter { it != RouteKind.PROCESS }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                kinds.forEach { k -> Chip(kindLabel(k), kind == k) { kind = k } }
            }
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dpi_rule_value), fontSize = 11.sp, color = TextMuted) },
                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(RouteTarget.PROXY, RouteTarget.DIRECT, RouteTarget.DPI, RouteTarget.BLOCK) + cfg.groups.map { it.tag } + nodes.map { RouteTarget.node(it.id) })
                    .forEach { t -> Chip(targetLabel(t, servers, cfg.groups), target == t) { target = t } }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.dpi_not), invert) { invert = !invert }
                OutlinedTextField(value = group, onValueChange = { group = it }, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.dpi_and_group), fontSize = 11.sp, color = TextMuted) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.dpi_rule_add), false) {
                    if (value.isNotBlank()) { vm.addRouteRule(RouteRule(kind, value.trim(), target, invert, group.trim())); value = "" }
                }
                Chip(stringResource(R.string.dpi_preset_blocked), false) { vm.applyBlockedRuPreset() }
            }
        }

        // --- группы ---
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
            var gname by remember { mutableStateOf("") }
            var gtype by remember { mutableStateOf(RouteGroup.TYPE_URLTEST) }
            var members by remember { mutableStateOf(setOf<String>()) }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = gname, onValueChange = { gname = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dpi_group_name), fontSize = 11.sp, color = TextMuted) },
                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(stringResource(R.string.dpi_g_auto), gtype == RouteGroup.TYPE_URLTEST) { gtype = RouteGroup.TYPE_URLTEST }
                Chip(stringResource(R.string.dpi_g_manual), gtype == RouteGroup.TYPE_SELECTOR) { gtype = RouteGroup.TYPE_SELECTOR }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(RouteTarget.PROXY, RouteTarget.DIRECT, RouteTarget.DPI) + nodes.map { RouteTarget.node(it.id) }).forEach { t ->
                    Chip(targetLabel(t, servers, cfg.groups), t in members) { members = if (t in members) members - t else members + t }
                }
            }
            Spacer(Modifier.height(6.dp))
            Chip(stringResource(R.string.dpi_group_add), false) {
                if (gname.isNotBlank() && members.isNotEmpty()) { vm.addRouteGroup(RouteGroup(gname.trim(), gtype, members.toList())); gname = ""; members = emptySet() }
            }
        }

        // --- цепочки ---
        Card(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dpi_chain_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.dpi_chain_sub), color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            val chainable = nodes.filter { it.protocolId in setOf("vless", "vmess", "trojan", "ss") }
            if (chainable.isEmpty()) Text(stringResource(R.string.dpi_chain_none_servers), color = TextMuted, fontSize = 12.sp)
            chainable.forEach { s ->
                Text(s.name, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val via = viaOf(s)
                    Chip(stringResource(R.string.dpi_chain_direct), via == null) { vm.setServerVia(s, null) }
                    Chip(stringResource(R.string.dpi_t_dpi), via == RouteTarget.DPI) { vm.setServerVia(s, RouteTarget.DPI) }
                    nodes.filter { it.id != s.id }.take(6).forEach { n ->
                        Chip(n.name, via == RouteTarget.node(n.id)) { vm.setServerVia(s, RouteTarget.node(n.id)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(stringResource(R.string.dpi_chain_udp_note), color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun kindLabel(k: RouteKind): String = stringResource(when (k) {
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
private fun targetLabel(t: String, servers: List<ServerProfile>, groups: List<RouteGroup> = emptyList()): String = when (t) {
    RouteTarget.PROXY -> stringResource(R.string.dpi_t_proxy)
    RouteTarget.DIRECT -> stringResource(R.string.dpi_t_direct)
    RouteTarget.DPI -> stringResource(R.string.dpi_t_dpi)
    RouteTarget.BLOCK -> stringResource(R.string.dpi_t_block)
    else -> RouteTarget.nodeId(t)?.let { id -> servers.firstOrNull { it.id == id }?.name }
        ?: groups.firstOrNull { it.tag == t }?.let { "◎ ${it.name}" } ?: t
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
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
