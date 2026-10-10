package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.DropdownField
import ru.gidravpn.hydra.ui.theme.*

private enum class RouteTab(val label: Int) { APPS(R.string.rh_tab_apps), SITES(R.string.rh_tab_sites), DPI(R.string.rh_tab_dpi), EXITS(R.string.rh_tab_exits) }

/**
 * «Маршрутизация» (0.7.14) — одно место вместо трёх разрозненных экранов (раздельное туннелирование, обход DPI, правила).
 * Четыре вкладки отвечают на четыре вопроса: какие приложения идут через VPN и куда, что делать с сайтами и адресами,
 * как обойти блокировки, и через какие выходы всё это пускать.
 */
@Composable
fun RoutingHubScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(RouteTab.APPS) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            Text(stringResource(R.string.rh_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(stringResource(R.string.rh_intro), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteTab.entries.forEach { t -> Chip(stringResource(t.label), tab == t) { tab = t } }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                RouteTab.APPS -> AppsTab(vm)
                RouteTab.SITES -> TabColumn(vm) {
                    NetSplitCard(vm)
                    RulesCard(vm, apps = null)
                }
                RouteTab.DPI -> TabColumn(vm) {
                    Text(stringResource(R.string.dpi_intro), color = TextMuted, fontSize = 12.sp)
                    DpiWizardCard(vm)
                    DpiEngineCard(vm)
                    TgWsCard(vm)
                }
                RouteTab.EXITS -> TabColumn(vm) {
                    Text(stringResource(R.string.rh_exits_intro), color = TextMuted, fontSize = 12.sp)
                    GroupsCard(vm)
                    ChainsCard(vm)
                }
            }
        }
    }
}

@Composable
private fun TabColumn(vm: MainViewModel, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

private fun modeOptions(all: String, only: String, except: String) = listOf(SplitTunnelMode.OFF to all, SplitTunnelMode.INCLUDE to only, SplitTunnelMode.EXCLUDE to except)

/** Вкладка «Приложения»: кто идёт через VPN (список с галочками) и свой выход для приложения. */
@Composable
private fun AppsTab(vm: MainViewModel) {
    val split by vm.splitTunnel.collectAsState()
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { installedApps(context) } }
    val filtered = remember(search, showSystem, apps) {
        apps.filter { (showSystem || !it.isSystem) && (search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true)) }
    }
    var presetMsg by remember { mutableStateOf<String?>(null) }
    val doneText = stringResource(R.string.split_preset_done)
    val noneText = stringResource(R.string.split_preset_none)
    val all = stringResource(R.string.split_all_traffic)
    val only = stringResource(R.string.split_only_selected)
    val except = stringResource(R.string.split_except_selected)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rh_apps_vpn_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.rh_apps_vpn_sub), color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                DropdownField(stringResource(R.string.rh_apps_mode), modeOptions(all, only, except).first { it.first == split.mode }.second,
                    modeOptions(all, only, except), { vm.setSplitMode(it) }, selected = split.mode)
                Text(split.summary(context), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp))
                Chip(stringResource(R.string.split_preset_ru), false) {
                    vm.applyRuAppsPreset(apps.map { it.packageName }) { n -> presetMsg = if (n > 0) doneText.format(n) else noneText }
                }
                presetMsg?.let { Text(it, color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
                Text(stringResource(R.string.split_apply_live), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { Spacer(Modifier.height(8.dp)); RulesCard(vm, apps = apps) }
        if (split.mode != SplitTunnelMode.OFF) {
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, singleLine = true,
                    label = { Text(stringResource(R.string.split_search), color = TextMuted, fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = Border, focusedContainerColor = InputBg, unfocusedContainerColor = InputBg),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.split_system_apps) + if (apps.isNotEmpty()) " · " + stringResource(R.string.split_apps_count, apps.count { showSystem || !it.isSystem }) else "",
                        color = TextMuted, fontSize = 12.sp)
                    Switch(checked = showSystem, onCheckedChange = { showSystem = it }, colors = SwitchDefaults.colors(checkedTrackColor = AccentCyan))
                }
            }
            items(filtered, key = { it.packageName }) { app ->
                AppRow(app = app, checked = app.packageName in split.packages, enabled = true, onClick = { vm.toggleSplitApp(app.packageName) })
            }
        }
    }
}

/** Раздельное туннелирование по адресам и доменам (какие сайты идут через VPN / мимо него). */
@Composable
private fun NetSplitCard(vm: MainViewModel) {
    val split by vm.splitTunnel.collectAsState()
    var type by remember { mutableStateOf(NetRuleType.DOMAIN) }
    var value by remember { mutableStateOf("") }
    val all = stringResource(R.string.split_all_traffic)
    val only = stringResource(R.string.split_only_selected)
    val except = stringResource(R.string.split_except_selected)
    Card(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.rh_net_title), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.rh_net_sub), color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        DropdownField(stringResource(R.string.rh_apps_mode), modeOptions(all, only, except).first { it.first == split.netMode }.second,
            modeOptions(all, only, except), { vm.setNetMode(it) }, selected = split.netMode)
        Text(split.netSummary(LocalContext.current), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        if (split.netMode != SplitTunnelMode.OFF) {
            Text(stringResource(R.string.split_net_engine_note), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))
            DropdownField(stringResource(R.string.rh_kind), stringResource(type.labelRes), NetRuleType.entries.map { it to stringResource(it.labelRes) }, { type = it }, selected = type)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (type == NetRuleType.IP_CIDR) R.string.split_example_ip else R.string.split_example_domain), color = TextMuted, fontSize = 12.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = Border, focusedContainerColor = InputBg, unfocusedContainerColor = InputBg),
            )
            Spacer(Modifier.height(8.dp))
            Chip(stringResource(R.string.action_add), false) {
                normalizeNetRuleValue(type, value)?.let { vm.addNetRule(NetworkRule(type, it)); value = "" }
            }
            split.netRules.forEach { rule ->
                Spacer(Modifier.height(6.dp))
                NetRuleRow(rule, onDelete = { vm.removeNetRule(rule) })
            }
        }
    }
}
