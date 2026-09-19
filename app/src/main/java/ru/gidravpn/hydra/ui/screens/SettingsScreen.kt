package ru.gidravpn.hydra.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import ru.gidravpn.hydra.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.ui.LauncherIconChoice
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.Label
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*
import ru.gidravpn.hydra.vpn.HydraQsTileService

/** Подэкраны Настроек — Split и Логи переехали сюда из верхнего уровня навигации. */
private enum class SettingsSection { HUB, TUNNEL, SECURITY, ROUTING, SPLIT, HOTSPOT, LOGS, THEME, LANGUAGE, BACKUP, ABOUT }

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var section by remember { mutableStateOf(SettingsSection.HUB) }

    when (section) {
        SettingsSection.HUB -> SettingsHub(onSelect = { section = it })
        SettingsSection.TUNNEL -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { TunnelInfoContent(vm) }
        SettingsSection.SECURITY -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { SecurityContent(vm) }
        SettingsSection.ROUTING -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { RoutingContent(vm) }
        SettingsSection.SPLIT -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { SplitTunnelScreen(vm) }
        SettingsSection.HOTSPOT -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { HotspotContent(vm) }
        SettingsSection.LANGUAGE -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { LanguageContent() }
        SettingsSection.LOGS -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { LogsScreen(vm) }
        SettingsSection.THEME -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { ThemeContent(vm) }
        SettingsSection.BACKUP -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { BackupContent(vm) }
        SettingsSection.ABOUT -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { AboutContent() }
    }
}

@Composable
private fun SettingsHub(onSelect: (SettingsSection) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.tab_settings), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        HubRow("🌐", stringResource(R.string.set_tunnel), stringResource(R.string.set_tunnel_sub), AccentViolet) { onSelect(SettingsSection.TUNNEL) }
        HubRow("🛡️", stringResource(R.string.set_security), stringResource(R.string.set_security_sub), Danger) { onSelect(SettingsSection.SECURITY) }
        HubRow("🧭", stringResource(R.string.set_routing), stringResource(R.string.set_routing_sub), AccentIndigo) { onSelect(SettingsSection.ROUTING) }
        HubRow("🔀", stringResource(R.string.set_split), stringResource(R.string.set_split_sub), AccentCyan) { onSelect(SettingsSection.SPLIT) }
        HubRow("📡", stringResource(R.string.hotspot_hub_title), stringResource(R.string.hotspot_hub_subtitle), AccentIndigo) { onSelect(SettingsSection.HOTSPOT) }
        HubRow("📋", stringResource(R.string.set_logs), stringResource(R.string.set_logs_sub), TextSecondary) { onSelect(SettingsSection.LOGS) }
        HubRow("🎨", stringResource(R.string.theme_title), "Ambient · Stealth · AMOLED · Material You", AccentCyan) { onSelect(SettingsSection.THEME) }
        HubRow("🌍", stringResource(R.string.set_language), stringResource(R.string.set_language_sub), AccentIndigo) { onSelect(SettingsSection.LANGUAGE) }
        HubRow("💾", stringResource(R.string.set_backup), stringResource(R.string.set_backup_sub), AccentViolet) { onSelect(SettingsSection.BACKUP) }
        HubRow("ℹ️", stringResource(R.string.set_about), stringResource(R.string.set_about_sub), TextSecondary) { onSelect(SettingsSection.ABOUT) }
    }
}

@Composable
private fun HubRow(icon: String, title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickableNoRipple(onClick)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 20.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = TextMuted, fontSize = 11.sp)
                }
            }
            Text("→", color = TextMuted, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SettingsSubScreen(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.set_back), color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickableNoRipple(onBack).padding(8.dp)
            )
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun TunnelInfoContent(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.set_tunnel), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        XrayEngineToggle(vm)

        InfoGroup("SSTP / L2TP (userspace PPP)", AccentCyan) {
            Text(stringResource(R.string.tunnel_sstp_l2tp),
                color = TextMuted, fontSize = 12.sp)
        }
        InfoGroup("PPTP", Danger) {
            Text(stringResource(R.string.tunnel_pptp),
                color = TextMuted, fontSize = 12.sp)
        }
        InfoGroup("Xray Core", AccentIndigo) {
            Text(stringResource(R.string.tunnel_xray),
                color = TextMuted, fontSize = 12.sp)
        }
        InfoGroup("sing-box", AccentViolet) {
            Text(stringResource(R.string.tunnel_singbox),
                color = TextMuted, fontSize = 12.sp)
        }
        InfoGroup("WireGuard / AmneziaWG", Success) {
            Text(stringResource(R.string.tunnel_wg),
                color = TextMuted, fontSize = 12.sp)
        }
        InfoGroup("WDTT / olcRTC (BETA)", AccentViolet) {
            Text(stringResource(R.string.tunnel_beta),
                color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun XrayEngineToggle(vm: MainViewModel) {
    val available = ru.gidravpn.hydra.BuildConfig.XRAY_AVAILABLE
    val preferXray by vm.preferXray.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.xray_toggle_title), color = TextPrimary,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    ru.gidravpn.hydra.ui.components.BetaBadge()
                }
                Text(
                    if (available) stringResource(R.string.xray_toggle_desc)
                    else stringResource(R.string.xray_toggle_missing),
                    color = TextMuted, fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = preferXray && available,
                onCheckedChange = { vm.setPreferXray(it) },
                enabled = available,
                colors = SwitchDefaults.colors(checkedTrackColor = AccentCyan)
            )
        }
    }
}

@Composable
private fun SecurityContent(vm: MainViewModel) {
    val context = LocalContext.current
    val killSwitch by vm.killSwitch.collectAsState()
    val autoApp by vm.autoConnectOnAppStart.collectAsState()
    val autoBoot by vm.autoConnectOnBoot.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.set_security), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        SecurityToggleCard(
            title = "Kill Switch",
description = stringResource(R.string.sec_killswitch_desc),
            checked = killSwitch,
            onCheckedChange = { vm.setKillSwitch(it) }
        )

        SecurityToggleCard(
            title = stringResource(R.string.sec_auto_app),
description = stringResource(R.string.sec_auto_app_desc),
            checked = autoApp,
            onCheckedChange = { vm.setAutoConnectOnAppStart(it) }
        )

        SecurityToggleCard(
            title = stringResource(R.string.sec_auto_boot),
description = stringResource(R.string.sec_auto_boot_desc),
            checked = autoBoot,
            onCheckedChange = { vm.setAutoConnectOnBoot(it) }
        )

        InfoGroup(stringResource(R.string.sec_tile_title), AccentCyan) {
            Text(
stringResource(R.string.sec_tile_desc),
                color = TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) stringResource(R.string.sec_tile_add)
                else stringResource(R.string.sec_tile_manual),
                color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickableNoRipple {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val statusBarManager = context.getSystemService(android.app.StatusBarManager::class.java)
                        statusBarManager?.requestAddTileService(
                            android.content.ComponentName(context, HydraQsTileService::class.java),
                            "Hydra VPN",
                            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_tile_vpn),
                            androidx.core.content.ContextCompat.getMainExecutor(context),
                            {}
                        )
                    }
                    // На Android 12 и ниже requestAddTileService() недоступен — плитка
                    // добавляется вручную: Шторка → карандаш «Редактировать» → найти
                    // «Hydra VPN» в списке и перетащить наверх.
                }
            )
        }

        InfoGroup(stringResource(R.string.sec_always_on_title), TextSecondary) {
            Text(
stringResource(R.string.sec_always_on_desc),
                color = TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.sec_open_vpn_settings), color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickableNoRipple {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                }
            )
        }
    }
}

@Composable
private fun SecurityToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = TextMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(checkedTrackColor = AccentCyan)
            )
        }
    }
}

@Composable
private fun RoutingContent(vm: MainViewModel) {
    val dnsProvider by vm.dnsProvider.collectAsState()
    val dnsCustom by vm.dnsCustomAddress.collectAsState()
    val geoMode by vm.geoRoutingMode.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.set_routing), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        Label(stringResource(R.string.dns_label))
        ru.gidravpn.hydra.data.model.DnsProvider.entries.forEach { provider ->
            RoutingOptionCard(
                title = provider.labelRes?.let { stringResource(it) } ?: provider.label,
                subtitle = when {
                    provider.address != null -> "${provider.address} · DoH"
                    provider == ru.gidravpn.hydra.data.model.DnsProvider.CUSTOM ->
                        if (dnsCustom.isNotBlank()) dnsCustom else stringResource(R.string.dns_custom_hint)
                    else -> stringResource(R.string.dns_system_hint)
                },
                selected = dnsProvider == provider,
                onClick = { vm.setDnsProvider(provider) }
            )
        }
        if (dnsProvider == ru.gidravpn.hydra.data.model.DnsProvider.CUSTOM) {
            var text by remember(dnsCustom) { mutableStateOf(dnsCustom) }
            var invalid by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it; invalid = false },
                    label = { Text("https://dns.example/dns-query", color = TextMuted, fontSize = 12.sp) },
                    singleLine = true,
                    isError = invalid,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
                        focusedContainerColor = InputBg, unfocusedContainerColor = InputBg
                    ),
                    modifier = Modifier.weight(1f)
                )
                RoutingSaveButton {
                    if (ru.gidravpn.hydra.data.model.DnsEndpoint.parse(text) != null) vm.setDnsCustomAddress(text)
                    else invalid = true
                }
            }
            Text(
stringResource(if (invalid) R.string.dns_invalid else R.string.dns_supported),
                color = if (invalid) Danger else TextMuted, fontSize = 11.sp
            )
        }
        Text(
stringResource(R.string.apply_next_connect),
            color = TextMuted, fontSize = 11.sp
        )

        Spacer(Modifier.height(8.dp))
        Label(stringResource(R.string.geo_label))
        Text(
stringResource(R.string.geo_desc),
            color = TextMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        ru.gidravpn.hydra.data.model.GeoRoutingMode.entries.forEach { mode ->
            RoutingOptionCard(
                title = stringResource(mode.labelRes),
                subtitle = stringResource(mode.descriptionRes),
                selected = geoMode == mode,
                onClick = { vm.setGeoRoutingMode(mode) }
            )
        }
        if (geoMode != ru.gidravpn.hydra.data.model.GeoRoutingMode.OFF) GeoCountryPicker(vm)

        Spacer(Modifier.height(8.dp))
        Label(stringResource(R.string.frag_label))
        Text(
stringResource(R.string.frag_desc),
            color = TextMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        val fragment by vm.tlsFragment.collectAsState()
        ru.gidravpn.hydra.data.model.TlsFragmentMode.entries.forEach { mode ->
            RoutingOptionCard(
                title = stringResource(mode.labelRes),
                subtitle = stringResource(mode.descriptionRes),
                selected = fragment == mode,
                onClick = { vm.setTlsFragment(mode) }
            )
        }

        Spacer(Modifier.height(8.dp))
        Label(stringResource(R.string.mtu_label))
        Text(
stringResource(R.string.mtu_desc),
            color = TextMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        val mtu by vm.mtu.collectAsState()
        ru.gidravpn.hydra.data.model.MtuPreset.entries.forEach { preset ->
            RoutingOptionCard(
                title = stringResource(preset.labelRes),
                subtitle = if (preset == ru.gidravpn.hydra.data.model.MtuPreset.AUTO) stringResource(R.string.mtu_auto_sub)
                    else stringResource(R.string.mtu_bytes, preset.value),
                selected = mtu == preset,
                onClick = { vm.setMtu(preset) }
            )
        }
        Text(stringResource(R.string.apply_next_connect), color = TextMuted, fontSize = 11.sp)
    }
}

/** Выбранные страны сверху, ниже — поиск по всем ~250 (название по-русски или ISO-код). */
@Composable
private fun GeoCountryPicker(vm: MainViewModel) {
    val selected by vm.geoCountries.collectAsState()
    val all = remember { vm.geoAvailable.map { it to ru.gidravpn.hydra.data.model.countryName(it) } }
    var query by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.geo_countries), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (selected.isEmpty()) stringResource(R.string.geo_none_selected)
            else selected.sorted().joinToString(", ") { ru.gidravpn.hydra.data.model.countryName(it) },
            color = if (selected.isEmpty()) Danger else AccentCyan, fontSize = 12.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
stringResource(R.string.geo_domains_note, vm.geoWithDomains.sorted().joinToString(", ") { ru.gidravpn.hydra.data.model.countryName(it) }),
            color = TextMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text(stringResource(R.string.geo_search), color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
                focusedContainerColor = InputBg, unfocusedContainerColor = InputBg
            ),
            modifier = Modifier.fillMaxWidth()
        )
        val q = query.trim()
        val shown = if (q.isEmpty()) all.filter { it.first in selected }
        else all.filter { (code, name) -> name.contains(q, ignoreCase = true) || code.equals(q, ignoreCase = true) }
            .sortedBy { it.second }.take(30)
        shown.forEach { (code, name) ->
            Row(
                Modifier.fillMaxWidth().clickableNoRipple { vm.toggleGeoCountry(code) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Checkbox(
                    checked = code in selected, onCheckedChange = { vm.toggleGeoCountry(code) },
                    colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = AccentCyan)
                )
                Text(name, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    code.uppercase() + if (code in vm.geoWithDomains) " · IP+" + stringResource(R.string.geo_domains_short) else " · IP",
                    color = TextMuted, fontSize = 11.sp
                )
            }
        }
        if (q.isNotEmpty() && shown.isEmpty()) Text(stringResource(R.string.geo_not_found), color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun RoutingOptionCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickableNoRipple(onClick),
        borderColor = if (selected) AccentCyan else Border
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextMuted, fontSize = 11.sp)
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Text("✓", color = AccentCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RoutingSaveButton(onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(CardBg)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick).padding(horizontal = 16.dp, vertical = 14.dp)
    ) { Text(stringResource(R.string.action_save), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ThemeContent(vm: MainViewModel) {
    val current by vm.themeMode.collectAsState()
    val iconChoice by vm.launcherIcon.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.theme_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        ru.gidravpn.hydra.ui.theme.ThemeMode.entries.forEach { mode ->
            ThemeOptionCard(mode, selected = mode == current) {
                if (mode.isSupported) vm.setThemeMode(mode)
            }
        }

        Spacer(Modifier.height(8.dp))
        Label(stringResource(R.string.icon_title))
        Text(stringResource(R.string.icon_hint), color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        LauncherIconChoice.entries.forEach { choice ->
            RoutingOptionCard(
                title = stringResource(
                    when (choice) {
                        LauncherIconChoice.FOLLOW_THEME -> R.string.icon_follow_theme
                        LauncherIconChoice.AMBIENT -> R.string.icon_ambient
                        LauncherIconChoice.STEALTH -> R.string.icon_stealth
                        LauncherIconChoice.OCEAN -> R.string.icon_ocean
                        LauncherIconChoice.AMBER -> R.string.icon_amber
                    }
                ),
                subtitle = "",
                selected = iconChoice == choice,
                onClick = { vm.setLauncherIcon(choice) }
            )
        }
    }
}

@Composable
private fun ThemeOptionCard(
    mode: ru.gidravpn.hydra.ui.theme.ThemeMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().clickableNoRipple(onClick),
        borderColor = if (selected) AccentCyan else Border
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // Живое превью герба этой темы.
                Image(
                    painter = painterResource(
                        if (mode == ru.gidravpn.hydra.ui.theme.ThemeMode.STEALTH) R.drawable.ic_hydra_stealth
                        else R.drawable.ic_hydra_ambient
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(mode.labelRes), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(if (mode.isSupported) mode.descriptionRes else R.string.theme_unsupported),
                        color = TextMuted, fontSize = 11.sp
                    )
                }
            }
            if (selected) Text("✓", color = AccentCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Локальные IPv4-адреса устройства (Wi-Fi, точка доступа), без loopback и tun самого VPN. */
private fun localAddresses(): List<Pair<String, String>> = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("hydra") }
        .flatMap { ni ->
            ni.inetAddresses.toList()
                .filterIsInstance<java.net.Inet4Address>()
                .map { ni.name to (it.hostAddress ?: "") }
        }
        .filter { it.second.isNotBlank() }
}.getOrDefault(emptyList())

@Composable
private fun HotspotContent(vm: MainViewModel) {
    val context = LocalContext.current
    val hs by vm.hotspot.collectAsState()
    var portText by remember(hs.port) { mutableStateOf(hs.port.toString()) }
    var userText by remember(hs.username) { mutableStateOf(hs.username) }
    var passText by remember(hs.password) { mutableStateOf(hs.password) }
    var invalid by remember { mutableStateOf(false) }
    val addresses = remember(hs.enabled) { localAddresses() }

    val fieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
        focusedContainerColor = InputBg, unfocusedContainerColor = InputBg
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.hotspot_title), fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold, color = TextPrimary)

        SecurityToggleCard(
            title = stringResource(R.string.hotspot_enable),
            description = stringResource(R.string.hotspot_enable_desc),
            checked = hs.enabled,
            onCheckedChange = { vm.setHotspotEnabled(it) }
        )

        if (hs.enabled) {
            androidx.compose.material3.OutlinedTextField(
                value = portText, onValueChange = { portText = it.filter(Char::isDigit).take(5); invalid = false },
                label = { Text(stringResource(R.string.hotspot_port), color = TextMuted, fontSize = 12.sp) },
                singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.material3.OutlinedTextField(
                value = userText, onValueChange = { userText = it; invalid = false },
                label = { Text(stringResource(R.string.hotspot_login), color = TextMuted, fontSize = 12.sp) },
                singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth()
            )
            androidx.compose.material3.OutlinedTextField(
                value = passText, onValueChange = { passText = it; invalid = false },
                label = { Text(stringResource(R.string.hotspot_password), color = TextMuted, fontSize = 12.sp) },
                singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth()
            )
            if (invalid) Text(stringResource(R.string.hotspot_invalid), color = Danger, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutingSaveButton {
                    val port = portText.toIntOrNull()
                    if (port != null && port in ru.gidravpn.hydra.data.model.HotspotSettings.PORT_RANGE &&
                        userText.isNotBlank() &&
                        passText.length >= ru.gidravpn.hydra.data.model.HotspotSettings.MIN_PASSWORD
                    ) {
                        vm.setHotspotPort(port)
                        vm.setHotspotCredentials(userText, passText)
                    } else invalid = true
                }
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(CardBg)
                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                        .clickableNoRipple { vm.regenerateHotspotPassword() }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(stringResource(R.string.hotspot_regenerate), color = TextPrimary,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(4.dp))
            Label(stringResource(R.string.hotspot_addresses))
            if (addresses.isEmpty()) {
                Text(stringResource(R.string.hotspot_no_address), color = TextMuted, fontSize = 12.sp)
            } else {
                val copiedMsg = stringResource(R.string.hotspot_copied)
                addresses.forEach { (iface, ip) ->
                    val socks = "socks5://${hs.username}:${hs.password}@$ip:${hs.port}"
                    Card(Modifier.fillMaxWidth()) {
                        Text("$iface · $ip:${hs.port}", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("SOCKS5 / HTTP", color = TextMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.hotspot_copy), color = AccentCyan, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickableNoRipple {
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("proxy", socks))
                                android.widget.Toast.makeText(context, copiedMsg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        Text(stringResource(R.string.hotspot_apply_note), color = TextMuted, fontSize = 11.sp)
        Text(stringResource(R.string.hotspot_warning), color = Danger, fontSize = 11.sp)
    }
}

@Composable
private fun LanguageContent() {
    val context = LocalContext.current
    var lang by remember { mutableStateOf(ru.gidravpn.hydra.LocaleHelper.current(context)) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.lang_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        listOf(
            ru.gidravpn.hydra.LocaleHelper.SYSTEM to stringResource(R.string.lang_system),
            ru.gidravpn.hydra.LocaleHelper.RU to "Русский",
            ru.gidravpn.hydra.LocaleHelper.EN to "English",
        ).forEach { (code, label) ->
            RoutingOptionCard(title = label, subtitle = "", selected = lang == code, onClick = {
                if (lang != code) {
                    lang = code
                    ru.gidravpn.hydra.LocaleHelper.applyToApp(context.applicationContext, code)
                    var c: android.content.Context = context
                    while (c is android.content.ContextWrapper) {
                        if (c is android.app.Activity) { c.recreate(); break }
                        c = c.baseContext
                    }
                }
            })
        }
        Text(stringResource(R.string.lang_hint), color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun BackupContent(vm: MainViewModel) {
    val message by vm.backupMessage.collectAsState()
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.exportBackup(it) } }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> confirmImport = uri }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.set_backup), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        message?.let {
            Card(Modifier.fillMaxWidth().clickableNoRipple { vm.dismissBackupMessage() }, borderColor = AccentCyan) {
                Text(it, color = TextPrimary, fontSize = 13.sp)
                Text(stringResource(R.string.backup_dismiss), color = TextMuted, fontSize = 10.sp)
            }
        }

        InfoGroup(stringResource(R.string.backup_save_title), AccentCyan) {
            Text(
stringResource(R.string.backup_save_desc),
                color = TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            BackupActionButton(stringResource(R.string.backup_save_btn), AccentCyan) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                exportLauncher.launch("hydra-backup-$date.json")
            }
        }

        InfoGroup(stringResource(R.string.backup_restore_title), AccentViolet) {
            Text(
stringResource(R.string.backup_restore_desc),
                color = TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            BackupActionButton(stringResource(R.string.backup_restore_btn), AccentViolet) {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        }

        InfoGroup(stringResource(R.string.backup_reset_title), Danger) {
            Text(
stringResource(R.string.backup_reset_desc),
                color = TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            BackupActionButton(stringResource(R.string.backup_reset_btn), Danger) { confirmReset = true }
        }
    }

    confirmImport?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text(stringResource(R.string.backup_restore_q)) },
text = { Text(stringResource(R.string.backup_restore_q_desc)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.importBackup(uri); confirmImport = null }) {
                    Text(stringResource(R.string.backup_replace), color = Danger)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmImport = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (confirmReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.backup_reset_q)) },
            text = { Text(stringResource(R.string.backup_reset_q_desc)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.resetSettings(); confirmReset = false }) {
                    Text(stringResource(R.string.backup_reset_confirm), color = Danger)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun BackupActionButton(text: String, color: Color, onClick: () -> Unit) {
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickableNoRipple(onClick).padding(vertical = 4.dp))
}

@Composable
private fun AboutContent() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.set_about), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        InfoGroup("Hydra", TextSecondary) {
            Text(stringResource(R.string.about_text, ru.gidravpn.hydra.BuildConfig.VERSION_NAME),
                color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun InfoGroup(title: String, accent: Color, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}
