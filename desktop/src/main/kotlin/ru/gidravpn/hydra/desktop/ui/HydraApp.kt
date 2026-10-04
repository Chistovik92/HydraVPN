package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.TlsFragmentMode
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.ConnectionMode
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import ru.gidravpn.hydra.desktop.Status
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.desktop.core.DesktopConfig
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date

internal val Accent = Color(0xFF2EC4B6)
internal val Danger = Color(0xFFE5484D)
internal val Warn = Color(0xFFF5A524)

private val HydraColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00201D),
    secondary = Color(0xFF7DD3C8),
    secondaryContainer = Color(0xFF1C4A45),
    onSecondaryContainer = Color(0xFFCFF5EF),
    background = Color(0xFF0F1417),
    surface = Color(0xFF151B1F),
    surfaceVariant = Color(0xFF1E262B),
    onSurface = Color(0xFFE3E8EA),
    onSurfaceVariant = Color(0xFF9FB0B6),
    error = Danger,
)

private enum class Tab(val title: String) { HOME("Главная"), SERVERS("Серверы"), SUBS("Подписки"), ROUTING("Маршруты"), ROUTERS("Роутеры"), SETTINGS("Настройки"), LOG("Журнал") }

@Composable
fun HydraApp(c: AppController, ui: UiState, onRelaunchAdmin: () -> Unit, startTab: Int = 0) {
    var tab by remember { mutableStateOf(Tab.entries[startTab]) }
    MaterialTheme(colorScheme = HydraColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                // Семь пунктов не помещаются в окно минимальной высоты (560) — рельсу можно прокрутить.
                Box(Modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
                NavigationRail(Modifier.verticalScroll(rememberScrollState()), containerColor = Color.Transparent) {
                    Spacer(Modifier.height(12.dp))
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(when (t) {
                                    Tab.HOME -> Icons.Default.Home
                                    Tab.SERVERS -> Icons.AutoMirrored.Filled.List
                                    Tab.SUBS -> Icons.Default.Share
                                    Tab.ROUTING -> Icons.Default.Place
                                    Tab.ROUTERS -> Icons.Default.Build
                                    Tab.SETTINGS -> Icons.Default.Settings
                                    Tab.LOG -> Icons.Default.Info
                                }, contentDescription = t.title)
                            },
                            label = { Text(t.title, fontSize = 12.sp) },
                        )
                    }
                }
                }
                Box(Modifier.fillMaxSize().padding(24.dp)) {
                    when (tab) {
                        Tab.HOME -> HomeScreen(c, ui, onRelaunchAdmin, openServers = { tab = Tab.SERVERS })
                        Tab.SERVERS -> ServersScreen(c, ui)
                        Tab.SUBS -> SubscriptionsScreen(c, ui)
                        Tab.ROUTING -> RoutingScreen(c, ui)
                        Tab.ROUTERS -> RoutersScreen(c, ui)
                        Tab.SETTINGS -> SettingsScreen(c, ui)
                        Tab.LOG -> LogScreen(c, ui)
                    }
                }
            }
            ui.message?.let { msg ->
                AlertDialog(
                    onDismissRequest = { c.toast(null) },
                    confirmButton = { TextButton(onClick = { c.toast(null) }) { Text("OK") } },
                    text = { Text(msg) },
                )
            }
        }
    }
}

// ============================================================== Главная
@Composable
private fun HomeScreen(c: AppController, ui: UiState, onRelaunchAdmin: () -> Unit, openServers: () -> Unit) {
    val settings = ui.data.settings
    // Прокрутка: в окне минимальной высоты кольцо, карточки и счётчики раньше обрезались.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        val ringColor = when (ui.status) {
            Status.CONNECTED -> if (ui.delayMs != null) Accent else Warn
            Status.CONNECTING, Status.STOPPING -> Warn
            Status.ERROR -> Danger
            Status.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Box(
            Modifier.size(180.dp).clip(CircleShape)
                .border(6.dp, ringColor, CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = ui.status != Status.STOPPING) {
                    // Сервера нет — ведём туда, где его берут, а не показываем «Выберите сервер».
                    if (ui.selected == null && !ui.active) openServers() else c.toggle()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (ui.status == Status.CONNECTING || ui.status == Status.STOPPING) {
                CircularProgressIndicator(color = Warn)
            } else {
                Text(if (ui.active) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = ringColor)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(ui.statusText, style = MaterialTheme.typography.titleMedium, color = if (ui.status == Status.ERROR) Danger else MaterialTheme.colorScheme.onSurface)
        ui.delayMs?.let { Text("Проверка через туннель: $it мс", color = Accent, fontSize = 13.sp) }
        if (ui.blocked) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { c.disconnect() }) { Text("Снять блокировку (kill switch)") }
        }
        ui.update?.let { u ->
            Spacer(Modifier.height(8.dp))
            UpdateBanner(c, ui, u)
        }
        Spacer(Modifier.height(20.dp))

        Card(Modifier.widthIn(max = 520.dp).fillMaxWidth().clickable(enabled = !ui.active) { openServers() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("Сервер", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val s = ui.selected
                if (s == null) Text("Не выбран — добавьте ссылку или подписку", fontWeight = FontWeight.Medium)
                else {
                    Text("${s.flag} ${s.name}", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val engine = DesktopConfig.engineFor(s, settings, c.xrayAvailable)
                    Text(s.summary + " · " + engineLabel(engine), fontSize = 12.sp,
                        color = if (engine == null) Danger else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ui.data.subscriptions.any { it.botPanel.isNotEmpty() }) {
            Spacer(Modifier.height(12.dp))
            AccountStatus(ui)
        }
        Spacer(Modifier.height(12.dp))
        ChipRow(center = true) {
            ConnectionMode.entries.forEach { m ->
                FilterChip(
                    selected = settings.mode == m,
                    enabled = !ui.active,
                    onClick = { c.updateSettings { it.copy(mode = m) } },
                    label = { Text(if (m == ConnectionMode.PROXY) "Системный прокси" else "TUN (весь трафик)") },
                )
            }
        }
        if (ui.active) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Stat("↓ ${speed(ui.downSpeed)}", "всего ${bytes(ui.downTotal)}")
                Stat("↑ ${speed(ui.upSpeed)}", "всего ${bytes(ui.upTotal)}")
            }
        }
        if (ui.needsElevation) {
            Spacer(Modifier.height(16.dp))
            Card(Modifier.widthIn(max = 520.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Для режима TUN Hydra нужно запустить от имени администратора (так Windows разрешает создать сетевой адаптер).")
                    Spacer(Modifier.height(8.dp))
                    ChipRow {
                        Button(onClick = onRelaunchAdmin) { Text("Перезапустить от администратора") }
                        OutlinedButton(onClick = { c.updateSettings { it.copy(mode = ConnectionMode.PROXY) }; c.connect() }) { Text("Подключить как прокси") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Stat(big: String, small: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(big, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    Text(small, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ============================================================== Серверы
@Composable
private fun ServersScreen(c: AppController, ui: UiState) {
    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var sortByPing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ServerProfile?>(null) }
    confirmDelete?.let { s ->
        ConfirmDelete("Удалить сервер?", "«${s.name}» будет удалён из списка.", { c.deleteServer(s.id) }) { confirmDelete = null }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Серверы") {
            FilterChip(selected = sortByPing, onClick = { sortByPing = !sortByPing }, label = { Text("По пингу") })
            OutlinedButton(onClick = { c.import(clipboard()) }) { Text("Вставить из буфера") }
            OutlinedButton(onClick = { c.pingAll() }, enabled = !ui.pinging && ui.data.servers.isNotEmpty()) {
                Text(if (ui.pinging) "Пинг…" else "Пинг всех")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true,
                // Enter добавляет — раньше приходилось тянуться к кнопке «+».
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) { c.import(input); input = "" } }),
                placeholder = { Text("vless://…, trojan://…, ss://… или адрес подписки", maxLines = 1, overflow = TextOverflow.Ellipsis) })
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { c.import(input); input = "" }, enabled = input.isNotBlank()) { Icon(Icons.Default.Add, "Добавить") }
        }
        Spacer(Modifier.height(12.dp))
        if (ui.data.servers.isEmpty()) {
            Empty("Серверов пока нет. Скопируйте ссылку-конфиг или адрес подписки и нажмите «Вставить из буфера».")
            return
        }
        if (ui.data.servers.size > 5) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Сбросить поиск") } },
                placeholder = { Text("Поиск по названию, адресу, протоколу") })
            Spacer(Modifier.height(8.dp))
        }
        val q = query.trim()
        val shown = ui.data.servers.filter {
            q.isEmpty() || it.name.contains(q, true) || it.address.contains(q, true) ||
                (it.protocol?.displayName ?: it.protocolId).contains(q, true)
        }
        if (shown.isEmpty()) { Empty("Ничего не найдено по «$q»."); return }
        val groups = shown.groupBy { it.subscriptionId }
        val subNames = ui.data.subscriptions.associate { it.id to it.displayName }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (subId, list) ->
                item(key = "h$subId") {
                    Text(subId?.let { subNames[it] ?: "Подписка" } ?: "Добавлены вручную",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                val ordered = if (sortByPing) list.sortedBy { if (it.pingMs >= 0) it.pingMs else Int.MAX_VALUE } else list
                items(ordered, key = { it.id }) { s -> ServerRow(c, ui, s) { confirmDelete = s } }
            }
        }
    }
}

@Composable
private fun ServerRow(c: AppController, ui: UiState, s: ServerProfile, onDelete: () -> Unit) {
    val supported = DesktopConfig.isSupported(s)
    val engine = DesktopConfig.engineFor(s, ui.data.settings, c.xrayAvailable)
    val selected = ui.data.settings.selectedServerId == s.id
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .clickable(enabled = supported && !ui.active) { c.select(s.id) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = { c.select(s.id) }, enabled = supported && !ui.active)
        Column(Modifier.weight(1f)) {
            Text("${s.flag} ${s.name}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                (s.protocol?.displayName ?: s.protocolId) + " · " + s.address + ":" + s.port + " · " +
                    when {
                        !supported -> "на ПК недоступно"
                        else -> engineLabel(engine)
                    },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            when {
                s.pingMs == -2 -> "нет ответа"
                s.pingMs >= 0 -> "${s.pingMs} мс"
                else -> ""
            },
            fontSize = 12.sp,
            color = when {
                s.pingMs == -2 -> Danger
                s.pingMs in 0..150 -> Accent
                s.pingMs > 150 -> Warn
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        IconButton(onClick = {
            val link = c.shareLink(s.id)
            if (link == null) c.toast("Для этого протокола нет формата ссылки")
            else { setClipboard(link); c.toast("Ссылка на «${s.name}» скопирована в буфер обмена.\nВ ней пароль сервера — передавайте только тем, кому доверяете.") }
        }) {
            Icon(Icons.Default.Share, "Поделиться", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete, enabled = !(ui.active && ui.connectedId == s.id)) {
            Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConfirmDelete(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Удалить", color = Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ============================================================== Подписки
@Composable
private fun SubscriptionsScreen(c: AppController, ui: UiState) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<ru.gidravpn.hydra.data.model.Subscription?>(null) }
    confirmDelete?.let { sub ->
        val count = ui.data.servers.count { it.subscriptionId == sub.id }
        ConfirmDelete("Удалить подписку?", "«${sub.displayName}» и её серверы ($count) будут удалены.", { c.deleteSubscription(sub.id) }) { confirmDelete = null }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Подписки") {
            OutlinedButton(onClick = { c.refreshAll() }, enabled = ui.data.subscriptions.isNotEmpty()) { Text("Обновить все") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.weight(2f), singleLine = true, label = { Text("Адрес подписки (https://…)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (url.isNotBlank()) { c.addSubscription(url, name); url = ""; name = "" } }))
            OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, label = { Text("Название") })
            Button(onClick = { c.addSubscription(url, name); url = ""; name = "" }, enabled = url.isNotBlank()) { Text("Добавить") }
        }
        Spacer(Modifier.height(12.dp))
        if (ui.data.subscriptions.isEmpty()) {
            Empty("Подписок нет. Адрес подписки выдаёт ваша панель (Remnawave, Marzban, 3x-ui и др.).")
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.data.subscriptions, key = { it.id }) { s ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.displayName, fontWeight = FontWeight.Medium)
                            val count = ui.data.servers.count { it.subscriptionId == s.id }
                            val parts = buildList {
                                add("серверов: $count")
                                if (s.totalBytes > 0) add("трафик ${bytes(s.usedBytes)} из ${bytes(s.totalBytes)}")
                                else if (s.usedBytes > 0) add("трафик ${bytes(s.usedBytes)}")
                                if (s.expireAt > 0) add("до ${date(s.expireAt * 1000)}")
                                if (s.lastUpdated > 0) add("обновлена ${date(s.lastUpdated, time = true)}")
                            }
                            Text(parts.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (s.lastError.isNotEmpty()) Text("Ошибка: ${s.lastError}", fontSize = 12.sp, color = Danger)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(s.autoUpdateHours > 0, { on -> c.setSubscriptionAutoUpdate(s.id, if (on) 12 else 0) })
                                Spacer(Modifier.width(8.dp))
                                Text(if (s.autoUpdateHours > 0) "Обновлять автоматически (раз в ${s.autoUpdateHours} ч)" else "Автообновление выключено", fontSize = 12.sp)
                            }
                        }
                        if (s.id in ui.updatingSubs) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { c.refreshSubscription(s.id) }) { Icon(Icons.Default.Refresh, "Обновить") }
                        IconButton(onClick = { confirmDelete = s }, enabled = !ui.active) { Icon(Icons.Default.Delete, "Удалить") }
                    }
                }
            }
        }
    }
}

// ============================================================== Журнал
@Composable
private fun LogScreen(c: AppController, ui: UiState) {
    val state = rememberLazyListState()
    LaunchedEffect(ui.log.size) { if (ui.log.isNotEmpty()) state.scrollToItem(ui.log.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Header("Журнал ядра") {
            OutlinedButton(onClick = {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(ui.log.joinToString("\n")), null)
            }, enabled = ui.log.isNotEmpty()) { Text("Копировать") }
            OutlinedButton(onClick = { c.clearLog() }, enabled = ui.log.isNotEmpty()) { Text("Очистить") }
        }
        if (ui.log.isEmpty()) { Empty("Журнал появится после подключения."); return }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(10.dp), state = state) {
                items(ui.log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    }
}

// ============================================================== общее
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Header(title: String, actions: @Composable () -> Unit) {
    // Не помещается в одну строку — кнопки переходят под заголовок, а не сжимают его в столбик.
    FlowRow(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.height(40.dp).padding(end = 16.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** Ряд чипов/кнопок, который переносится на новую строку, если не помещается в окно. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(center: Boolean = false, content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(),
        horizontalArrangement = if (center) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
internal fun Empty(text: String) = Box(Modifier.fillMaxWidth().fillMaxHeight(0.6f), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
}

internal fun clipboard(): String = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
}.getOrDefault("")

internal fun bytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f ГБ".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f МБ".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f КБ".format(b / 1024.0)
    else -> "$b Б"
}

private fun speed(b: Long) = bytes(b) + "/с"

private fun date(ms: Long, time: Boolean = false): String =
    SimpleDateFormat(if (time) "dd.MM.yyyy HH:mm" else "dd.MM.yyyy").format(Date(ms))

internal fun setClipboard(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

internal fun openUrl(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

internal fun engineLabel(kind: ru.gidravpn.hydra.data.model.EngineToggles.Kind?): String = when (kind) {
    ru.gidravpn.hydra.data.model.EngineToggles.Kind.XRAY -> "Xray"
    ru.gidravpn.hydra.data.model.EngineToggles.Kind.SINGBOX -> "sing-box"
    ru.gidravpn.hydra.data.model.EngineToggles.Kind.OLCRTC -> "olcRTC (BETA)"
    ru.gidravpn.hydra.data.model.EngineToggles.Kind.OPENFLUX -> "OpenFlux (BETA)"
    null -> "ядро выключено"
    else -> kind.name
}
