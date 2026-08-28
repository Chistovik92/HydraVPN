package ru.gidravpn.hydra.ui.screens

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

/** Установленное приложение для списка split tunneling. */
data class AppEntry(val packageName: String, val label: String, val isSystem: Boolean)

@Composable
fun SplitTunnelScreen(vm: MainViewModel) {
    val split by vm.splitTunnel.collectAsState()
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { installedApps(context) }
    }
    val filtered = remember(search, showSystem, apps) {
        apps.filter {
            (showSystem || !it.isSystem) &&
                    (search.isBlank() || it.label.contains(search, true) ||
                            it.packageName.contains(search, true))
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Раздельное туннелирование", fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(12.dp))
        Text(split.summary, color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        // Режим
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip("Весь трафик", split.mode == SplitTunnelMode.OFF) { vm.setSplitMode(SplitTunnelMode.OFF) }
            ModeChip("Только выбранные", split.mode == SplitTunnelMode.INCLUDE) { vm.setSplitMode(SplitTunnelMode.INCLUDE) }
            ModeChip("Кроме выбранных", split.mode == SplitTunnelMode.EXCLUDE) { vm.setSplitMode(SplitTunnelMode.EXCLUDE) }
        }
        Spacer(Modifier.height(16.dp))

        if (split.mode != SplitTunnelMode.OFF) {
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                label = { Text("Поиск приложения", color = TextMuted, fontSize = 12.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
                    focusedContainerColor = InputBg, unfocusedContainerColor = InputBg
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Системные приложения", color = TextMuted, fontSize = 12.sp)
                Switch(
                    checked = showSystem, onCheckedChange = { showSystem = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = AccentCyan)
                )
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = app.packageName in split.packages,
                        enabled = split.mode != SplitTunnelMode.OFF,
                        onClick = { vm.toggleSplitApp(app.packageName) },
                    )
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Text("Весь трафик устройства идёт через VPN. Выберите режим, чтобы настроить исключения или белый список приложений.",
                    color = TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text("Применяется при следующем подключении.", color = TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ModeChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) AccentCyan.copy(alpha = 0.15f) else CardBg)
            .border(1.dp, if (active) AccentCyan else Border, RoundedCornerShape(10.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (active) AccentCyan else TextSecondary,
            fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) AccentCyan.copy(alpha = 0.08f) else CardBg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(app.label, color = TextPrimary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName + if (app.isSystem) " • системное" else "",
                color = TextMuted, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Checkbox(
            checked = checked, onCheckedChange = { onClick() },
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = AccentCyan, checkmarkColor = Bg)
        )
    }
}

/** Запускабельные приложения (launcher intent) + флаг системных. */
private fun installedApps(context: android.content.Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(
                    packageName = pkg,
                    label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg),
                    isSystem = runCatching {
                        (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    }.getOrDefault(false),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
