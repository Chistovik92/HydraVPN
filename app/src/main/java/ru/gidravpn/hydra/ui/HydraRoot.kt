package ru.gidravpn.hydra.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.screens.*
import ru.gidravpn.hydra.ui.theme.*

// Только 3 вкладки верхнего уровня — Split и Логи переехали внутрь
// Настроек (см. SettingsScreen), как в референсе INCY.
enum class Tab(val label: String) { MAIN("● Главная"), SERVERS("Серверы"), SETTINGS("Настройки") }

@Composable
fun HydraRoot(vm: MainViewModel) {
    var tab by remember { mutableStateOf(Tab.MAIN) }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            NavBar(tab) { tab = it }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.MAIN -> MainScreen(vm, onGoServers = { tab = Tab.SERVERS })
                    Tab.SERVERS -> ServersScreen(vm, onSelected = { tab = Tab.MAIN })
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun NavBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceDim).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        Tab.entries.forEach { t ->
            val active = t == current
            Text(
                text = t.label,
                color = if (active) AccentCyan else TextMuted,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) AccentCyan.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickableNoRipple { onSelect(t) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
