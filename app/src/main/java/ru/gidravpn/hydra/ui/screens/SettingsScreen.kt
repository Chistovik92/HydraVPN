package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.theme.*

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Настройки", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        SettingsGroup("SSTP / L2TP (userspace PPP)", AccentCyan) {
            Text("Полностью на Kotlin, без нативных .aar: PPP-стек (LCP, MS-CHAPv2, IPCP), SSTP поверх TLS с crypto-binding, L2TP по UDP (без IPsec/ESP). Нужен тест на устройстве.",
                color = TextMuted, fontSize = 12.sp)
        }
        SettingsGroup("PPTP", Danger) {
            Text("Недоступно: данные в GRE (IP-протокол 47) требуют raw-сокетов/root, стек удалён из Android 12/13. Альтернативы: SSTP, L2TP, WireGuard.",
                color = TextMuted, fontSize = 12.sp)
        }
        SettingsGroup("Xray Core", AccentIndigo) {
            Text("Транспорт: XTLS Vision / WS / gRPC. Flow: xtls-rprx-vision. Движок: libXray.aar.",
                color = TextMuted, fontSize = 12.sp)
        }
        SettingsGroup("sing-box", AccentViolet) {
            Text("Протоколы: VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC v5. Движок: libbox.aar.",
                color = TextMuted, fontSize = 12.sp)
        }
        SettingsGroup("WireGuard / AmneziaWG", Success) {
            Text("Обычный WireGuard — через sing-box (libbox.aar). AmneziaWG 1.0/1.5/2.0 — отдельный движок amneziawg-go.aar: обфускация Jc/Jmin/Jmax/S1/S2/H1–H4 и маркеры I1–I5. Генерация .conf/uapi готова.",
                color = TextMuted, fontSize = 12.sp)
        }
        SettingsGroup("WDTT и olcRTC (BETA)", AccentViolet) {
            Text("Ознакомительные движки. WDTT — WireGuard через TURN-релей облака ВК (libclient.so + VK-авторизация). olcRTC — TCP поверх WebRTC DataChannel (olcrtc.aar + tun2socks). Отмечены плашкой BETA в интерфейсе.",
                color = TextMuted, fontSize = 12.sp)
        }
        SettingsGroup("О приложении", TextSecondary) {
            Text("Hydra 0.5.0 — мультипротокольный VPN-клиент. Лицензия GPL-3.0.",
                color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsGroup(title: String, accent: Color, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}
