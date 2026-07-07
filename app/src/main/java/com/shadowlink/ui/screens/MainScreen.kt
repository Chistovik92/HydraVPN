package com.shadowlink.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shadowlink.ui.MainViewModel
import com.shadowlink.ui.components.Card
import com.shadowlink.ui.components.Label
import com.shadowlink.ui.components.clickableNoRipple
import com.shadowlink.ui.theme.*
import com.shadowlink.vpn.core.ConnectionState

@Composable
fun MainScreen(vm: MainViewModel, onGoServers: () -> Unit) {
    val state by vm.state.collectAsState()
    val server by vm.selectedServer.collectAsState()
    val stats by vm.stats.collectAsState()
    val since by vm.connectedSince.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Заголовок
        Text("ShadowLink", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
        Text("Мультипротокольный VPN-клиент", color = TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))

        // Выбор протокола (отражает протокол выбранного сервера)
        Card(Modifier.fillMaxWidth()) {
            Label("Протокол")
            Text(
                server?.protocol?.displayName ?: "—",
                color = TextPrimary, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp)).background(InputBg).padding(12.dp)
            )
        }
        Spacer(Modifier.height(32.dp))

        ConnectButton(state) { vm.toggle() }
        Spacer(Modifier.height(32.dp))

        ConnectionInfo(state, server?.name, stats, since)
        Spacer(Modifier.height(20.dp))

        // Превью конфигурации
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Текущая конфигурация", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("Изменить →", color = AccentCyan, fontSize = 11.sp,
                    modifier = Modifier.clickableNoRipple(onGoServers))
            }
            Spacer(Modifier.height(8.dp))
            val cfg = server?.let {
                "Сервер: ${it.address}\nПорт: ${it.port}\nТип: ${it.protocol?.displayName ?: it.protocolId}"
            } ?: "Сервер не выбран"
            Text(cfg, color = TextSecondary, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)).background(InputBg).padding(10.dp))
        }
    }
}

@Composable
private fun ConnectButton(state: ConnectionState, onClick: () -> Unit) {
    val connected = state == ConnectionState.CONNECTED
    val connecting = state == ConnectionState.CONNECTING

    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        1f, 1.3f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "p"
    )
    val ringColor = when { connected -> Success; connecting -> AccentCyan; else -> Border }

    Box(contentAlignment = Alignment.Center) {
        if (connected) {
            Box(Modifier.size(200.dp).scale(pulse).clip(CircleShape)
                .background(AccentCyan.copy(alpha = 0.08f)))
        }
        Column(
            Modifier.size(160.dp).clip(CircleShape)
                .background(Surface)
                .border(3.dp, ringColor, CircleShape)
                .clickableNoRipple(onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                when { connected -> "🔓"; connecting -> "⏳"; else -> "🔒" },
                fontSize = 32.sp
            )
            Text(
                when {
                    connected -> "ОТКЛЮЧИТЬ"
                    connecting -> "ПОДКЛЮЧЕНИЕ…"
                    else -> "ПОДКЛЮЧИТЬ"
                },
                color = if (connected) Success else TextSecondary,
                fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ConnectionInfo(
    state: ConnectionState, serverName: String?,
    stats: com.shadowlink.vpn.core.TrafficStats, since: Long
) {
    val connected = state == ConnectionState.CONNECTED
    var elapsed by remember { mutableStateOf("00:00") }
    LaunchedEffect(connected, since) {
        while (connected && since > 0) {
            val s = (System.currentTimeMillis() - since) / 1000
            elapsed = "%02d:%02d".format(s / 60, s % 60)
            kotlinx.coroutines.delay(1000)
        }
        if (!connected) elapsed = "00:00"
    }

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            InfoCell("Статус",
                if (connected) "Подключено" else "Отключено",
                if (connected) Success else Danger)
            InfoCell("Сервер", serverName ?: "—", TextPrimary)
            InfoCell("Время", elapsed, TextPrimary)
        }
        if (connected) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                InfoCell("↓ Загружено", mb(stats.downBytes), AccentCyan)
                InfoCell("↑ Отправлено", mb(stats.upBytes), AccentIndigo)
            }
        }
    }
}

@Composable
private fun InfoCell(title: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = TextMuted, fontSize = 11.sp)
        Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
    }
}

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_000_000.0)
