package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

/** Подэкраны Настроек — Split и Логи переехали сюда из верхнего уровня навигации. */
private enum class SettingsSection { HUB, TUNNEL, SPLIT, LOGS, THEME, ABOUT }

@Composable
fun SettingsScreen(vm: MainViewModel) {
    var section by remember { mutableStateOf(SettingsSection.HUB) }

    when (section) {
        SettingsSection.HUB -> SettingsHub(onSelect = { section = it })
        SettingsSection.TUNNEL -> SettingsSubScreen(onBack = { section = SettingsSection.HUB }) { TunnelInfoContent() }
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
private fun TunnelInfoContent() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Туннель", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

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
private fun ThemeContent(vm: MainViewModel) {
    val current by vm.themeMode.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Тема", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        ru.gidravpn.hydra.ui.theme.ThemeMode.entries.forEach { mode ->
            ThemeOptionCard(mode, selected = mode == current) { vm.setThemeMode(mode) }
        }
    }
}

@Composable
private fun ThemeOptionCard(
    mode: ru.gidravpn.hydra.ui.theme.ThemeMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = when (mode) {
        ru.gidravpn.hydra.ui.theme.ThemeMode.EMERALD -> EmeraldPalette
        ru.gidravpn.hydra.ui.theme.ThemeMode.STEALTH -> StealthPalette
    }
    Card(
        Modifier.fillMaxWidth().clickableNoRipple(onClick),
        borderColor = if (selected) AccentCyan else Border
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(palette.bg, palette.surface, palette.accent).forEach { swatch ->
                    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
                        drawCircle(swatch)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column {
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
