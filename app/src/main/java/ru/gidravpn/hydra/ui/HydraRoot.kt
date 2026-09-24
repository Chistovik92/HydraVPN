package ru.gidravpn.hydra.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.screens.*
import ru.gidravpn.hydra.ui.theme.*

// 4 вкладки нижней навигации: Главная/Серверы/Профиль/Настройки.
enum class Tab(@androidx.annotation.StringRes val label: Int, val icon: ImageVector) {
    MAIN(R.string.tab_main, Icons.Filled.Home),
    SERVERS(R.string.tab_servers, Icons.Filled.Dns),
    PROFILE(R.string.tab_profile, Icons.Filled.Person),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun HydraRoot(
    vm: MainViewModel,
    openServers: Boolean = false,
    onOpenServersHandled: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(Tab.MAIN) }
    val themeMode by vm.themeMode.collectAsState()

    // Переход по кнопке «Серверы» из уведомления.
    LaunchedEffect(openServers) {
        if (openServers) { tab = Tab.SERVERS; onOpenServersHandled() }
    }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        // Фаза 8: планшеты и Android TV. От 600 dp навигация — колонкой слева (на TV пульт
        // сразу попадает в неё), а контент не растягивается шире 720 dp — карточки,
        // рассчитанные на телефон, на широком экране иначе превращались в полосы.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val content: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                        when (tab) {
                            Tab.MAIN -> MainScreen(vm, onGoServers = { tab = Tab.SERVERS })
                            Tab.SERVERS -> ServersScreen(vm, onSelected = { tab = Tab.MAIN })
                            Tab.PROFILE -> ProfileScreen(vm)
                            Tab.SETTINGS -> SettingsScreen(vm)
                        }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    NavRail(tab) { tab = it }
                    Column(Modifier.weight(1f)) {
                        TopBrandBar(themeMode)
                        Box(Modifier.weight(1f).navigationBarsPadding()) { content() }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopBrandBar(themeMode)
                    Box(Modifier.weight(1f)) { content() }
                    NavBar(tab) { tab = it }
                }
            }
        }
    }
}

/** Боковая навигация для широких экранов (планшет, TV). */
@Composable
private fun NavRail(current: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier.fillMaxHeight().background(SurfaceDim).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Tab.entries.forEach { t ->
            val active = t == current
            val color = if (active) AccentCyan else TextMuted
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickableNoRipple { onSelect(t) }.padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(t.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text(stringResource(t.label), color = color, fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun TopBrandBar(themeMode: ThemeMode) {
    // Герб меняется вместе с темой: Cyber Emerald / Crimson Core.
    val mark = if (themeMode == ThemeMode.STEALTH) R.drawable.ic_hydra_stealth
    else R.drawable.ic_hydra_ambient
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(mark),
            contentDescription = null,
            modifier = Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text("HYDRA", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(" VPN", color = AccentCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NavBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceDim).navigationBarsPadding().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        Tab.entries.forEach { t ->
            val active = t == current
            val color = if (active) AccentCyan else TextMuted
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickableNoRipple { onSelect(t) }.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                // Подпись ниже уже читается TalkBack — у иконки описание не дублируем.
                Icon(t.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(t.label),
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
