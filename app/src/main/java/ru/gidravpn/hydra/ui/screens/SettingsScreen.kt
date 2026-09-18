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
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.Label
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*
import ru.gidravpn.hydra.vpn.HydraQsTileService

/** Подэкраны Настроек — Split и Логи переехали сюда из верхнего уровня навигации. */
private enum class SettingsSection { HUB, TUNNEL, SECURITY, ROUTING, SPLIT, LOGS, THEME, ABOUT }

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var section by remember { mutableStateOf(SettingsSection.HUB) }

    when (section) {
        SettingsSection.HUB -> SettingsHub(onSelect = { section = it })
        SettingsSection.TUNNEL -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { TunnelInfoContent(vm) }
        SettingsSection.SECURITY -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { SecurityContent(vm) }
        SettingsSection.ROUTING -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { RoutingContent(vm) }
        SettingsSection.SPLIT -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { SplitTunnelScreen(vm) }
        SettingsSection.LOGS -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { LogsScreen(vm) }
        SettingsSection.THEME -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { ThemeContent(vm) }
        SettingsSection.ABOUT -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { AboutContent() }
    }
}

@Composable
private fun SettingsHub(onSelect: (SettingsSection) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Настройки", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        HubRow("🌐", "Туннель", "Протоколы и движки", AccentViolet) { onSelect(SettingsSection.TUNNEL) }
        HubRow("🛡️", "Безопасность", "Kill Switch, автоподключение", Danger) { onSelect(SettingsSection.SECURITY) }
        HubRow("🧭", "Маршрутизация", "DNS, geoip по РФ, фрагментация, MTU", AccentIndigo) { onSelect(SettingsSection.ROUTING) }
        HubRow("🔀", "Split-туннелинг", "Приложения через VPN / мимо VPN", AccentCyan) { onSelect(SettingsSection.SPLIT) }
        HubRow("📋", "Логи", "Журнал подключения", TextSecondary) { onSelect(SettingsSection.LOGS) }
        HubRow("🎨", "Тема", "Hydra Emerald / Monochrome Stealth", AccentCyan) { onSelect(SettingsSection.THEME) }
        HubRow("ℹ️", "О приложении", "Версия, лицензия", TextSecondary) { onSelect(SettingsSection.ABOUT) }
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
                "← Настройки", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
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
        Text("Туннель", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        XrayEngineToggle(vm)

        InfoGroup("SSTP / L2TP (userspace PPP)", AccentCyan) {
            Text("Полностью на Kotlin, без нативных .aar: PPP-стек (LCP, MS-CHAPv2, IPCP), SSTP поверх TLS с crypto-binding, L2TP по UDP (без IPsec/ESP). Нужен тест на устройстве.",
                color = TextMuted, fontSize = 12.sp)
        }
        InfoGroup("PPTP", Danger) {
            Text("Недоступно: данные в GRE (IP-протокол 47) требуют raw-сокетов/root, стек удалён из Android 12/13. Альтернативы: SSTP, L2TP, WireGuard.",
                color = TextMuted, fontSize = 12.sp)
        }
        InfoGroup("Xray Core", AccentIndigo) {
            Text("Транспорт: XTLS Vision / WS / gRPC. Flow: xtls-rprx-vision. Движок: libXray.aar.",
                color = TextMuted, fontSize = 12.sp)
        }
        InfoGroup("sing-box", AccentViolet) {
            Text("Протоколы: VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC v5. Движок: libbox.aar.",
                color = TextMuted, fontSize = 12.sp)
        }
        InfoGroup("WireGuard / AmneziaWG", Success) {
            Text("Обычный WireGuard — через sing-box (libbox.aar). AmneziaWG 1.0/1.5/2.0 — отдельный движок amneziawg-go.aar: обфускация Jc/Jmin/Jmax/S1/S2/H1–H4 и маркеры I1–I5. Генерация .conf/uapi готова.",
                color = TextMuted, fontSize = 12.sp)
        }
        InfoGroup("WDTT и olcRTC (BETA)", AccentViolet) {
            Text("Ознакомительные движки. WDTT — WireGuard через TURN-релей облака ВК (libclient.so + VK-авторизация). olcRTC — TCP поверх WebRTC DataChannel (olcrtc.aar + tun2socks). Отмечены плашкой BETA в интерфейсе.",
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
                    Text("Xray Core для VLESS/VMess/Trojan/SS", color = TextPrimary,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    ru.gidravpn.hydra.ui.components.BetaBadge()
                }
                Text(
                    if (available) "Вместо sing-box — ради его реализации XTLS Vision (отдельный процесс, sing-box остаётся мостом к TUN). VLESS проверен на реальном устройстве; VMess/Trojan/SS и долгая стабильность — ещё нет."
                    else "Нужен app/libs/libXray.aar — см. docs/BUILD.md, раздел 2.2.",
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
        Text("Безопасность", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        SecurityToggleCard(
            title = "Kill Switch",
            description = "Если попытка подключения обрывается с ошибкой, туннель не " +
                "откатывается на прямое соединение — трафик блокируется, пока вы не " +
                "отключите VPN вручную. Не защищает от системного отзыва VPN (другое " +
                "VPN-приложение, «Отключить» в системных настройках) — для полной " +
                "гарантии на уровне ОС включите ниже «Блокировать соединения без VPN».",
            checked = killSwitch,
            onCheckedChange = { vm.setKillSwitch(it) }
        )

        SecurityToggleCard(
            title = "Автоподключение при запуске приложения",
            description = "Открыли Hydra — она сама поднимет туннель к последнему серверу, " +
                "если системное согласие на VPN уже выдавалось раньше.",
            checked = autoApp,
            onCheckedChange = { vm.setAutoConnectOnAppStart(it) }
        )

        SecurityToggleCard(
            title = "Автоподключение при загрузке устройства",
            description = "Туннель поднимется сразу после перезагрузки телефона, без " +
                "открытия приложения. Тоже требует ранее выданного VPN-согласия — " +
                "диалог из фона показать нельзя.",
            checked = autoBoot,
            onCheckedChange = { vm.setAutoConnectOnBoot(it) }
        )

        InfoGroup("Плитка в шторке уведомлений", AccentCyan) {
            Text(
                "Подключает/отключает последний использованный сервер прямо из панели " +
                    "быстрых настроек, без открытия приложения.",
                color = TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Добавить плитку →"
                else "Как добавить вручную →",
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

        InfoGroup("Системный Always-on VPN", TextSecondary) {
            Text(
                "Единственный способ гарантированно заблокировать трафик и при крахе " +
                    "самого приложения/сервиса, не только при ошибке подключения. " +
                    "Настройки → Сеть → VPN → Hydra → шестерёнка → «Постоянная VPN-сеть» + " +
                    "«Блокировать соединения без VPN».",
                color = TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Открыть настройки VPN →", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
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
        Text("Маршрутизация", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        Label("DNS (внутри туннеля)")
        ru.gidravpn.hydra.data.model.DnsProvider.entries.forEach { provider ->
            RoutingOptionCard(
                title = provider.label,
                subtitle = when {
                    provider.address != null -> "${provider.address} · DoH"
                    provider == ru.gidravpn.hydra.data.model.DnsProvider.CUSTOM ->
                        if (dnsCustom.isNotBlank()) dnsCustom else "IP, хост или DoH/DoT-URL"
                    else -> "Резолвер устройства, без шифрования — запросы идут мимо туннеля"
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
                if (invalid) "Не похоже на адрес DNS. Примеры: 94.140.14.14, dns.example.com, " +
                    "https://dns.example.com/dns-query/токен, tls://dns.example.com"
                else "Поддерживаются IP, хост (→ DoH), https://… (DoH, можно с путём и токеном), tls://… (DoT), udp://…",
                color = if (invalid) Danger else TextMuted, fontSize = 11.sp
            )
        }
        Text(
            "Применяется при следующем подключении.",
            color = TextMuted, fontSize = 11.sp
        )

        Spacer(Modifier.height(8.dp))
        Label("GeoIP-маршрутизация (РФ)")
        Text(
            "Базы geoip-ru/geosite-ru (sing-box rule-set, precompiled из MetaCubeX/meta-rules-dat). " +
                "Работает и для sing-box, и для Xray Core — TUN и маршрутизацией в обоих случаях владеет " +
                "sing-box. Не проверено на реальном устройстве.",
            color = TextMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        ru.gidravpn.hydra.data.model.GeoRoutingMode.entries.forEach { mode ->
            RoutingOptionCard(
                title = mode.label,
                subtitle = mode.description,
                selected = geoMode == mode,
                onClick = { vm.setGeoRoutingMode(mode) }
            )
        }

        Spacer(Modifier.height(8.dp))
        Label("Фрагментация TLS (обход DPI)")
        Text(
            "Режет рукопожатие с прокси-сервером, чтобы DPI не увидел имя сайта в одном пакете. " +
                "Только движок sing-box и только VLESS/VMess/Trojan с TLS — не действует на " +
                "Hysteria2/TUIC (там QUIC) и при включённом Xray Core. Не проверено на реальных серверах.",
            color = TextMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        val fragment by vm.tlsFragment.collectAsState()
        ru.gidravpn.hydra.data.model.TlsFragmentMode.entries.forEach { mode ->
            RoutingOptionCard(
                title = mode.label,
                subtitle = mode.description,
                selected = fragment == mode,
                onClick = { vm.setTlsFragment(mode) }
            )
        }

        Spacer(Modifier.height(8.dp))
        Label("MTU туннеля")
        Text(
            "Меняйте, только если часть сайтов «висит» при загрузке, а мелкие запросы проходят — " +
                "признак потери крупных пакетов. Для SSTP/L2TP действует не выше 1400.",
            color = TextMuted, fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        val mtu by vm.mtu.collectAsState()
        ru.gidravpn.hydra.data.model.MtuPreset.entries.forEach { preset ->
            RoutingOptionCard(
                title = preset.label,
                subtitle = if (preset == ru.gidravpn.hydra.data.model.MtuPreset.AUTO) "Как было до этой настройки"
                    else "${preset.value} байт",
                selected = mtu == preset,
                onClick = { vm.setMtu(preset) }
            )
        }
        Text("Применяется при следующем подключении.", color = TextMuted, fontSize = 11.sp)
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
    ) { Text("Сохранить", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ThemeContent(vm: MainViewModel) {
    val current by vm.themeMode.collectAsState()
    val dynamicIcon by vm.dynamicLauncherIcon.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Тема", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        ru.gidravpn.hydra.ui.theme.ThemeMode.entries.forEach { mode ->
            ThemeOptionCard(mode, selected = mode == current) { vm.setThemeMode(mode) }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Менять и ярлык на рабочем столе", color = TextPrimary,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Иконка приложения будет соответствовать теме. Лаунчер при этом " +
                            "пересоздаёт ярлык: он может на миг пропасть, уехать в конец " +
                            "списка приложений, а вынесенные вручную ярлыки — слететь.",
                        color = TextMuted, fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = dynamicIcon,
                    onCheckedChange = { vm.setDynamicLauncherIcon(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = AccentCyan)
                )
            }
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
                // Живое превью иконки этой темы — та же, что уйдёт на рабочий стол.
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
                    Text(mode.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(mode.description, color = TextMuted, fontSize = 11.sp)
                }
            }
            if (selected) Text("✓", color = AccentCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AboutContent() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("О приложении", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        InfoGroup("Hydra", TextSecondary) {
            Text("Hydra ${ru.gidravpn.hydra.BuildConfig.VERSION_NAME} — мультипротокольный VPN-клиент. Лицензия GPL-3.0.",
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
