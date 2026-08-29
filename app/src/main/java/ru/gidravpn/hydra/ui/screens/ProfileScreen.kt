package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.BuildConfig
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.humanBytes
import ru.gidravpn.hydra.ui.components.Label
import ru.gidravpn.hydra.ui.components.Sparkline
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*
import ru.gidravpn.hydra.vpn.core.ConnectionState

private const val GITHUB_REPO = "Chistovik92/HydraVPN"
private const val MAX_SAMPLES = 30

@Composable
fun ProfileScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val server by vm.activeServer.collectAsState()
    val stats by vm.stats.collectAsState()
    val since by vm.connectedSince.collectAsState()
    val connected = state == ConnectionState.CONNECTED

    var elapsed by remember { mutableStateOf("00:00:00") }
    val downHistory = remember { mutableStateListOf<Float>() }
    val upHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(connected) {
        var prevDown = stats.downBytes
        var prevUp = stats.upBytes
        while (true) {
            if (connected && since > 0) {
                val s = (System.currentTimeMillis() - since) / 1000
                elapsed = "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
            } else {
                elapsed = "00:00:00"
            }
            val down = stats.downBytes
            val up = stats.upBytes
            downHistory.add((down - prevDown).coerceAtLeast(0).toFloat())
            upHistory.add((up - prevUp).coerceAtLeast(0).toFloat())
            if (downHistory.size > MAX_SAMPLES) downHistory.removeAt(0)
            if (upHistory.size > MAX_SAMPLES) upHistory.removeAt(0)
            prevDown = down
            prevUp = up
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Профиль", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

        Card(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(SurfaceDim),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Person, contentDescription = null, tint = AccentCyan) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("HYDRA AGENT", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Hydra ${BuildConfig.VERSION_NAME}", color = TextMuted, fontSize = 12.sp)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Label("Статистика")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                StatCell("Статус", if (connected) "Подключено" else "Отключено", if (connected) Success else TextMuted)
                StatCell("Сервер", server?.name ?: "—", TextPrimary)
                StatCell("Сессия", elapsed, TextPrimary)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("↓ ${humanBytes(stats.downBytes)}", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("↑ ${humanBytes(stats.upBytes)}", color = AccentIndigo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Sparkline(downHistory, AccentCyan, Modifier.fillMaxWidth().height(48.dp))
            Spacer(Modifier.height(6.dp))
            Sparkline(upHistory, AccentIndigo, Modifier.fillMaxWidth().height(48.dp))
        }

        Card(Modifier.fillMaxWidth()) {
            Label("Виртуальный IP")
            Text("[скрыт]", color = TextSecondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }

        GithubCard()
    }
}

@Composable
private fun StatCell(title: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = TextMuted, fontSize = 11.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GithubCard() {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().clickableNoRipple { uriHandler.openUri("https://github.com/$GITHUB_REPO") }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Репозиторий", color = TextMuted, fontSize = 11.sp)
                Text(GITHUB_REPO, color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Открыть на GitHub", tint = AccentCyan)
        }
    }
}

