package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.router.RouterManager
import ru.gidravpn.hydra.router.RouterUi
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.router.RouterLink
import ru.gidravpn.hydra.router.RouterNode
import ru.gidravpn.hydra.router.RouterSection

/** Действие на роутере, которое нужно подтвердить: оно меняет то, что видят все устройства сети. */
private class Pending(val title: String, val text: String, val action: () -> Unit)

// ============================================================== Роутеры
@Composable
internal fun RoutersScreen(c: AppController, ui: UiState) {
    val m = c.routers
    val r by m.ui.collectAsState()
    val links = ui.data.routers
    val current = links.firstOrNull { it.baseUrl == r.selected }
    var confirm by remember { mutableStateOf<Pending?>(null) }

    // Пока экран открыт — опрашиваем выбранный роутер.
    LaunchedEffect(r.selected) {
        while (r.selected != null) {
            m.refresh()
            delay(10_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Роутеры") {
            OutlinedButton(onClick = { m.refresh() }, enabled = current != null && !r.busy) { Text("Обновить") }
            OutlinedButton(onClick = { m.reloadConfig() }, enabled = current != null && !r.busy) { Text("Перечитать конфиг") }
            OutlinedButton(
                onClick = {
                    confirm = Pending("Перезапустить службу?", "HydraVPN for Router на «${current?.name}» перезапустится, VPN в сети роутера прервётся на несколько секунд.") { m.restart() }
                },
                enabled = current != null && !r.busy,
            ) { Text("Перезапустить службу") }
        }
        AddRouterForm(m)
        Spacer(Modifier.height(12.dp))
        if (links.isEmpty()) {
            Empty("Роутеров нет. Ссылку сопряжения показывает веб-интерфейс роутера или команда `hydravpn-router pair --host <адрес>`.")
        } else {
            RouterChips(links, r.selected, m)
            Spacer(Modifier.height(12.dp))
            if (current != null) RouterDetails(c, ui, current, r, m) { confirm = it }
        }
    }

    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(p.title) },
            text = { Text(p.text) },
            confirmButton = { TextButton(onClick = { confirm = null; p.action() }) { Text("Да", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun AddRouterForm(m: RouterManager) {
    var link by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Ссылка hydravpn-router://… или адрес[:порт]") })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(token, { token = it }, Modifier.weight(1f), singleLine = true,
                visualTransformation = PasswordVisualTransformation(), label = { Text("Токен (если не в ссылке)", maxLines = 1) })
            OutlinedTextField(name, { name = it }, Modifier.width(200.dp), singleLine = true, label = { Text("Название") })
            Button(onClick = { if (m.add(link, token, name)) { link = ""; token = ""; name = "" } }, enabled = link.isNotBlank()) { Text("Добавить") }
        }
    }
}

@Composable
private fun RouterChips(links: List<RouterLink>, selected: String?, m: RouterManager) {
    ChipRow {
        links.forEach { l ->
            FilterChip(
                selected = l.baseUrl == selected,
                onClick = { m.select(l.baseUrl) },
                label = { Text(l.name + if (l.insecure) " · без TLS" else "") },
            )
        }
        val cur = links.firstOrNull { it.baseUrl == selected }
        if (cur != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${cur.host}:${cur.port}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { m.remove(cur.baseUrl) }) { Icon(Icons.Default.Delete, "Забыть роутер") }
            }
        }
    }
}

@Composable
private fun RouterDetails(c: AppController, ui: UiState, link: RouterLink, r: RouterUi, m: RouterManager, ask: (Pending) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { StatusCard(link, r) }
        item { NodesCard(r, m) }
        item { EditableSectionsCard(r, m) { t, x, a -> ask(Pending(t, x, a)) } }
        item { SubscriptionsCard(c, ui, r, m, ask) }
        item { RouterRadarCard(r, m, r.sections.map { it.name }) }
        item { RouterChecksCard(r, m) }
        item { RouterLog(r) }
    }
}

@Composable
private fun RCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun Muted(text: String) = Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun StatusCard(link: RouterLink, r: RouterUi) = RCard("Состояние") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val (label, color) = when {
            r.busy && !r.online && r.error == null -> "Подключение…" to Warn
            r.online -> "Онлайн" to Accent
            else -> "Недоступен" to Danger
        }
        Text(label, color = color, fontWeight = FontWeight.Bold)
        if (r.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        if (r.version.isNotEmpty()) Muted("версия ${r.version}")
        if (r.state.isNotEmpty()) Muted("служба: ${r.state}")
        if (r.uptime.isNotEmpty()) Muted("работает ${r.uptime}")
    }
    if (link.insecure) Text("Соединение без TLS: токен виден в сети. Включите api_tls_cert/api_tls_key на роутере и добавьте его заново по новой ссылке.", color = Warn, fontSize = 12.sp)
    else if (link.tls && link.fingerprint == null) Muted("TLS без закрепления отпечатка: сертификат проверяется обычной цепочкой доверия.")
    r.error?.let { Text(it, color = Danger, fontSize = 13.sp) }
    if (r.lastError.isNotEmpty()) Text("Последняя ошибка службы: ${r.lastError}", color = Warn, fontSize = 12.sp)
}

@Composable
private fun NodesCard(r: RouterUi, m: RouterManager) = RCard("Узлы") {
    r.nodesError?.let { Text("Список узлов недоступен: $it", color = Warn, fontSize = 12.sp) }
    val byName = r.nodes.associateBy { it.name }
    val groups = r.nodes.filter { it.isGroup && it.type.equals("Selector", true) }
    if (groups.isEmpty() && r.nodesError == null) Muted("Групп выбора нет — узлы появятся после загрузки подписки.")
    groups.forEach { g ->
        Text(g.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        g.members.forEach { name ->
            val node: RouterNode? = byName[name]
            val ms = r.delays[name] ?: node?.delayMs ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = g.now == name, onClick = { m.selectNode(g.name, name) }, label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                node?.country?.takeIf { it.isNotEmpty() }?.let { Muted(it) }
                when {
                    ms > 0 -> Text("$ms мс", fontSize = 12.sp, color = Accent)
                    ms < 0 -> Text("нет ответа", fontSize = 12.sp, color = Danger)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { m.testNode(name) }, enabled = !r.busy) { Text("Проверить") }
            }
        }
    }
}

@Composable
private fun SubscriptionsCard(c: AppController, ui: UiState, r: RouterUi, m: RouterManager, ask: (Pending) -> Unit) = RCard("Подписки на роутере") {
    r.subscriptions.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.url, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("секция ${s.section}" + if (s.autoUpdate) " · автообновление раз в ${"%.0f".format(s.updateIntervalHours)} ч" else " · без автообновления")
            }
            IconButton(onClick = { m.refreshSubscription(s.index) }, enabled = !r.busy) { Icon(Icons.Default.Refresh, "Обновить сейчас") }
            IconButton(
                onClick = { ask(Pending("Удалить подписку с роутера?", "Узлы этой подписки исчезнут у всех устройств за роутером.") { m.deleteSubscription(s.index) }) },
                enabled = !r.busy,
            ) { Icon(Icons.Default.Delete, "Удалить") }
        }
    }
    if (r.subscriptions.isEmpty()) Muted("Подписок нет.")

    var section by remember(r.sections) { mutableStateOf(r.sections.firstOrNull()?.name.orEmpty()) }
    var url by remember { mutableStateOf("") }
    Spacer(Modifier.height(4.dp))
    if (r.sections.isEmpty()) {
        Muted("Чтобы добавить подписку, на роутере должна быть хотя бы одна секция.")
        return@RCard
    }
    ChipRow {
        Muted("Секция:")
        r.sections.forEach { s -> FilterChip(selected = section == s.name, onClick = { section = s.name }, label = { Text(s.title) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true, label = { Text("Адрес подписки (https://…)") })
        Button(onClick = { m.addSubscription(section, url.trim()); url = "" }, enabled = url.isNotBlank() && section.isNotEmpty() && !r.busy) { Text("Добавить") }
    }
    val own = ui.data.subscriptions.filter { it.url.startsWith("http", true) }
    if (own.isNotEmpty()) {
        Muted("Отправить на роутер подписку из Hydra:")
        ChipRow {
            own.forEach { s ->
                OutlinedButton(
                    onClick = { m.addSubscription(section, s.url) },
                    enabled = section.isNotEmpty() && !r.busy,
                ) { Text(s.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun RouterLog(r: RouterUi) = RCard("Журнал роутера") {
    if (r.logs.isEmpty()) {
        Muted("Записей пока нет.")
        return@RCard
    }
    SelectionContainer {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp)).padding(8.dp)) {
            r.logs.takeLast(40).forEach { e ->
                val color = when (e.level.lowercase()) {
                    "error" -> Danger
                    "warn", "warning" -> Warn
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text("${e.time.substringAfter('T').substringBefore('.').take(8)} ${e.level.uppercase()} ${e.message}",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color)
            }
        }
    }
}
