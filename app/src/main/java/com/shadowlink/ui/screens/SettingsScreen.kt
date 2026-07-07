package com.shadowlink.ui.screens

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
import com.shadowlink.ui.components.Card
import com.shadowlink.ui.theme.*

@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Настройки", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        SettingsGroup("IKEv2/IPsec (замена L2TP)", AccentCyan) {
            Text("PSK и параметры хранятся в профиле сервера. На Android 13+ нативный L2TP удалён — используется системный IKEv2/IPsec.",
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
        SettingsGroup("О приложении", TextSecondary) {
            Text("ShadowLink 0.1.0 — мультипротокольный VPN-клиент. Лицензия GPL-3.0.",
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
