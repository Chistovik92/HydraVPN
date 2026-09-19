package ru.gidravpn.hydra.ui.screens

import androidx.compose.ui.res.stringResource
import ru.gidravpn.hydra.R

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import ru.gidravpn.hydra.data.model.SplitTunnel
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

/** Установленное приложение для списка split tunneling. */
data class AppEntry(val packageName: String, val label: String, val isSystem: Boolean)

private enum class SplitSection { APPS, NET }

@Composable
fun SplitTunnelScreen(vm: MainViewModel) {
    val split by vm.splitTunnel.collectAsState()
    var section by remember { mutableStateOf(SplitSection.APPS) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(stringResource(R.string.split_title), fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(stringResource(R.string.split_by_apps), section == SplitSection.APPS) { section = SplitSection.APPS }
            ModeChip(stringResource(R.string.split_by_net), section == SplitSection.NET) { section = SplitSection.NET }
        }
        Spacer(Modifier.height(16.dp))

        when (section) {
            SplitSection.APPS -> AppsSection(vm, split)
            SplitSection.NET -> NetRulesSection(vm, split)
        }
    }
}

@Composable
private fun AppsSection(vm: MainViewModel, split: SplitTunnel) {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { installedApps(context) }
    }
    val filtered = remember(search, showSystem, apps) {
        apps.filter {
            (showSystem || !it.isSystem) &&
                    (search.isBlank() || it.label.contains(search, true) ||
                            it.packageName.contains(search, true))
        }
    }

    Text(split.summary(context), color = TextMuted, fontSize = 12.sp)
    if (apps.isNotEmpty()) {
        Text(stringResource(R.string.split_apps_count, apps.count { showSystem || !it.isSystem }),
            color = TextMuted, fontSize = 11.sp)
    }
    Spacer(Modifier.height(16.dp))

    // Режим
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip(stringResource(R.string.split_all_traffic), split.mode == SplitTunnelMode.OFF) { vm.setSplitMode(SplitTunnelMode.OFF) }
        ModeChip(stringResource(R.string.split_only_selected), split.mode == SplitTunnelMode.INCLUDE) { vm.setSplitMode(SplitTunnelMode.INCLUDE) }
        ModeChip(stringResource(R.string.split_except_selected), split.mode == SplitTunnelMode.EXCLUDE) { vm.setSplitMode(SplitTunnelMode.EXCLUDE) }
    }
    Spacer(Modifier.height(16.dp))

    if (split.mode != SplitTunnelMode.OFF) {
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            label = { Text(stringResource(R.string.split_search), color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
                focusedContainerColor = InputBg, unfocusedContainerColor = InputBg
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.split_system_apps), color = TextMuted, fontSize = 12.sp)
            Switch(
                checked = showSystem, onCheckedChange = { showSystem = it },
                colors = SwitchDefaults.colors(checkedTrackColor = AccentCyan)
            )
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    checked = app.packageName in split.packages,
                    enabled = split.mode != SplitTunnelMode.OFF,
                    onClick = { vm.toggleSplitApp(app.packageName) },
                )
            }
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.split_apps_off_hint),
                color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.apply_next_connect), color = TextMuted, fontSize = 11.sp)
        }
    }
}

private val ipCidrRegex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?$")

private fun normalizeNetRuleValue(type: NetRuleType, raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return when (type) {
        NetRuleType.IP_CIDR -> trimmed.takeIf { ipCidrRegex.matches(it) }
        else -> trimmed.removePrefix("https://").removePrefix("http://").substringBefore("/")
    }
}

@Composable
private fun NetRulesSection(vm: MainViewModel, split: SplitTunnel) {
    var type by remember { mutableStateOf(NetRuleType.DOMAIN) }
    var value by remember { mutableStateOf("") }

    Text(split.netSummary(LocalContext.current), color = TextMuted, fontSize = 12.sp)
    Spacer(Modifier.height(16.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip(stringResource(R.string.split_all_traffic), split.netMode == SplitTunnelMode.OFF) { vm.setNetMode(SplitTunnelMode.OFF) }
        ModeChip(stringResource(R.string.split_only_selected), split.netMode == SplitTunnelMode.INCLUDE) { vm.setNetMode(SplitTunnelMode.INCLUDE) }
        ModeChip(stringResource(R.string.split_except_selected), split.netMode == SplitTunnelMode.EXCLUDE) { vm.setNetMode(SplitTunnelMode.EXCLUDE) }
    }
    Spacer(Modifier.height(16.dp))

    if (split.netMode != SplitTunnelMode.OFF) {
        Text(
            stringResource(R.string.split_net_engine_note),
            color = TextMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NetRuleType.entries.forEach { t -> ModeChip(stringResource(t.labelRes), type == t) { type = t } }
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                label = { Text(stringResource(if (type == NetRuleType.IP_CIDR) R.string.split_example_ip else R.string.split_example_domain), color = TextMuted, fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
                    focusedContainerColor = InputBg, unfocusedContainerColor = InputBg
                ),
                modifier = Modifier.weight(1f)
            )
            OutlinedActionButton(stringResource(R.string.action_add)) {
                normalizeNetRuleValue(type, value)?.let {
                    vm.addNetRule(NetworkRule(type, it))
                    value = ""
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(split.netRules, key = { it.type.name + it.value }) { rule ->
                NetRuleRow(rule, onDelete = { vm.removeNetRule(rule) })
            }
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.split_net_off_hint),
                color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.apply_next_connect), color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun NetRuleRow(rule: NetworkRule, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(stringResource(rule.type.labelRes), color = TextMuted, fontSize = 10.sp)
            Text(rule.value, color = TextPrimary, fontSize = 13.sp)
        }
        Text("✕", color = Danger, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickableNoRipple(onDelete).padding(8.dp))
    }
}

@Composable
private fun OutlinedActionButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(CardBg)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick).padding(horizontal = 16.dp, vertical = 14.dp)
    ) { Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ModeChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) AccentCyan.copy(alpha = 0.15f) else CardBg)
            .border(1.dp, if (active) AccentCyan else Border, RoundedCornerShape(10.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (active) AccentCyan else TextSecondary,
            fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) AccentCyan.copy(alpha = 0.08f) else CardBg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(app.label, color = TextPrimary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName + if (app.isSystem) " • " + stringResource(R.string.split_system_tag) else "",
                color = TextMuted, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(
            checked = checked, onCheckedChange = { onClick() },
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = AccentCyan, checkmarkColor = Bg)
        )
    }
}

/** Запускабельные приложения (launcher intent) + флаг системных. */
private fun installedApps(context: android.content.Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(
                    packageName = pkg,
                    label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg),
                    isSystem = runCatching {
                        (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    }.getOrDefault(false),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
