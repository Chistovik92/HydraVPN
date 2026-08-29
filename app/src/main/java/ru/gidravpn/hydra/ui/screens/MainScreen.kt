package ru.gidravpn.hydra.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.BetaBadge
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.Label
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*
import ru.gidravpn.hydra.vpn.core.ConnectionState

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
        Spacer(Modifier.height(12.dp))

        // Выбор протокола (отражает протокол выбранного сервера)
        Card(Modifier.fillMaxWidth()) {
            Label("Протокол")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    server?.protocol?.displayName ?: "—",
                    color = TextPrimary, fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(10.dp)).background(InputBg).padding(12.dp)
                )
                if (server?.protocol?.beta == true) {
                    Spacer(Modifier.width(8.dp))
                    BetaBadge()
                }
            }
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
    val error = state == ConnectionState.ERROR
    val active = connected || connecting

    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        1f, 1.3f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "p"
    )
    val glowAlpha by transition.animateFloat(
        0.15f, 0.4f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "g"
    )

    val ringColor = when {
        error -> Danger
        connected -> Success
        connecting -> AccentCyan
        else -> Border
    }
    val iconColor = if (active || error) ringColor else TextSecondary
    val label = when {
        error -> "ОШИБКА — ПОВТОРИТЬ"
        connected -> "ОТКЛЮЧИТЬ"
        connecting -> "ПОДКЛЮЧЕНИЕ…"
        else -> "ПОДКЛЮЧИТЬ"
    }

    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        RadarRings(active = active, color = ringColor)

        // Кибер-контур: тонкие радиальные дорожки вокруг кнопки.
        Canvas(Modifier.size(220.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val rInner = size.minDimension / 2 * 0.76f
            val rOuter = size.minDimension / 2 * 0.92f
            val traceColor = ringColor.copy(alpha = 0.5f)
            repeat(16) { i ->
                val angle = (i * 360f / 16) * (Math.PI / 180f)
                val from = Offset(center.x + rInner * cos(angle).toFloat(), center.y + rInner * sin(angle).toFloat())
                val to = Offset(center.x + rOuter * cos(angle).toFloat(), center.y + rOuter * sin(angle).toFloat())
                drawLine(traceColor, from, to, strokeWidth = 2f)
            }
        }

        if (active || error) {
            Box(
                Modifier.size(200.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(ringColor.copy(alpha = glowAlpha), ringColor.copy(alpha = 0f))))
            )
        }
        if (connected) {
            Box(Modifier.size(190.dp).scale(pulse).clip(CircleShape)
                .background(Success.copy(alpha = 0.06f)))
        }

        Column(
            Modifier.size(160.dp).clip(CircleShape)
                .background(Surface)
                .border(3.dp, ringColor, CircleShape)
                .clickableNoRipple(onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.PowerSettingsNew, contentDescription = label, tint = iconColor, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = iconColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

/** Кибер-радар вокруг кнопки: статичные концентрические кольца + импульс при активном соединении. */
@Composable
private fun RadarRings(active: Boolean, color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "radar")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "t")

    Canvas(Modifier.size(240.dp)) {
        val maxR = size.minDimension / 2
        // Статичные концентрические кольца.
        listOf(0.55f, 0.72f, 0.9f).forEach { fraction ->
            drawCircle(color.copy(alpha = 0.08f), radius = maxR * fraction, style = Stroke(width = 1.5f))
        }
        if (active) {
            val r = maxR * (0.45f + 0.5f * t)
            drawCircle(color.copy(alpha = (1f - t) * 0.35f), radius = r, style = Stroke(width = 2f))
        }
    }
}

@Composable
private fun ConnectionInfo(
    state: ConnectionState, serverName: String?,
    stats: ru.gidravpn.hydra.vpn.core.TrafficStats, since: Long
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
                when (state) {
                    ConnectionState.CONNECTED -> "Подключено"
                    ConnectionState.ERROR -> "Ошибка"
                    else -> "Отключено"
                },
                when (state) {
                    ConnectionState.CONNECTED -> Success
                    ConnectionState.ERROR -> Danger
                    else -> TextMuted
                })
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
