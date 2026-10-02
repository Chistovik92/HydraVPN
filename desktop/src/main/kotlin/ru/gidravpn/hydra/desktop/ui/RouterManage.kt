package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.router.RouterManager
import ru.gidravpn.hydra.router.RouterSection
import ru.gidravpn.hydra.router.RouterUi

// Управление роутером из Hydra на ПК (0.7.1): разделы (включить/выключить, править, добавить, удалить),
// аккаунт бота «Радар» на самом роутере, диагностика. Это то, что даёт управляющий API роутера
// (`/api/v1/sections`, `/radar`, `/check`), — раньше экран умел только смотреть.

@Composable
private fun MCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

private val ACTIONS = listOf("connection" to "Через VPN", "bypass" to "Мимо VPN", "block" to "Блокировать")
private val PROVIDERS = listOf("singbox", "zapret", "zapret2", "byedpi", "auto")

private fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }

/** Диалог «раздел»: имя задаётся только при добавлении. */
@Composable
private fun SectionDialog(existing: RouterSection?, m: RouterManager, busy: Boolean, onClose: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var action by remember { mutableStateOf(existing?.action?.ifBlank { "connection" } ?: "connection") }
    var provider by remember { mutableStateOf(existing?.provider ?: "singbox") }
    var community by remember { mutableStateOf(existing?.list("community_lists").orEmpty().joinToString("\n")) }
    var ruleSet by remember { mutableStateOf(existing?.list("rule_set").orEmpty().joinToString("\n")) }
    var fully by remember { mutableStateOf(existing?.list("fully_routed_ips").orEmpty().joinToString("\n")) }
    val nameOk = existing != null || Regex("^[A-Za-z0-9_.-]{1,64}$").matches(name)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "Новый раздел" else "Раздел «${existing.title}»") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, isError = name.isNotEmpty() && !nameOk,
                        label = { Text("Имя (латиница, цифры, - _ .)") })
                }
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(enabled, { enabled = it }); Text("Включён")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Hint("Действие:")
                    ACTIONS.forEach { (v, t) -> FilterChip(selected = action == v, onClick = { action = v }, label = { Text(t) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Hint("Движок:")
                    PROVIDERS.forEach { p -> FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(p) }) }
                }
                OutlinedTextField(community, { community = it }, Modifier.fillMaxWidth().height(90.dp), label = { Text("Списки сообществ (по одному в строке)") })
                OutlinedTextField(ruleSet, { ruleSet = it }, Modifier.fillMaxWidth().height(90.dp), label = { Text("rule_set (ссылки, по одной в строке)") })
                OutlinedTextField(fully, { fully = it }, Modifier.fillMaxWidth().height(90.dp), label = { Text("Весь трафик этих IP (по одному в строке)") })
                Hint("Остальные поля раздела на роутере не меняются. Изменение видят все устройства за роутером.")
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameOk && !busy,
                onClick = {
                    val e = RouterManager.SectionEdit(label.trim(), enabled, action, provider, lines(community), lines(ruleSet), lines(fully))
                    if (existing == null) m.addSection(name.trim(), e) else m.saveSection(existing, e)
                    onClose()
                },
            ) { Text("Сохранить", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

@Composable
internal fun EditableSectionsCard(r: RouterUi, m: RouterManager, ask: (String, String, () -> Unit) -> Unit) = MCard("Разделы") {
    var editing by remember { mutableStateOf<RouterSection?>(null) }
    var adding by remember { mutableStateOf(false) }
    if (r.sections.isEmpty()) Hint("Разделов нет.")
    r.sections.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(s.enabled, { m.setSectionEnabled(s, it) }, enabled = s.editable && !r.busy)
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = 13.sp)
                Hint("${s.name} · ${s.action} · ${s.provider}" + if (s.editable) "" else " · есть личные ссылки — правка только на роутере")
            }
            IconButton(onClick = { editing = s }, enabled = s.editable && !r.busy) { Icon(Icons.Default.Edit, "Изменить") }
            IconButton(
                onClick = { ask("Удалить раздел «${s.title}»?", "Раздел исчезнет у всех устройств за роутером. Раздел с подпиской удалить нельзя.") { m.deleteSection(s.name) } },
                enabled = !r.busy,
            ) { Icon(Icons.Default.Delete, "Удалить") }
        }
    }
    OutlinedButton(onClick = { adding = true }, enabled = !r.busy) { Text("Добавить раздел") }
    editing?.let { s -> SectionDialog(s, m, r.busy) { editing = null } }
    if (adding) SectionDialog(null, m, r.busy) { adding = false }
}

@Composable
internal fun RouterRadarCard(r: RouterUi, m: RouterManager, sectionNames: List<String>) = MCard("Аккаунт Hydra VPN (бот «Радар») на роутере") {
    val radar = r.radar
    if (radar != null && radar.linked) {
        Text(if (radar.username.isNotBlank()) "Подключён как @${radar.username}" else "Подключён к боту", fontWeight = FontWeight.Medium, fontSize = 13.sp)
        if (radar.server.isNotBlank()) Hint(radar.server)
        Hint("Роутер сам раз в 12 часов забирает выданные в боте подписки; удалённые в боте с роутера не убираются.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { m.radarSync(null) }, enabled = !r.busy) { Text("Получить подписки сейчас") }
            TextButton(onClick = { m.radarUnlink() }, enabled = !r.busy) { Text("Отключить роутер от бота") }
        }
    } else {
        var server by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var section by remember(sectionNames) { mutableStateOf(sectionNames.firstOrNull().orEmpty()) }
        Hint("Роутер войдёт в ваш аккаунт бота и сам заведёт выданные подписки. В боте: «VPN» → «Подключить приложение» — код действует 5 минут.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(server, { server = it }, Modifier.weight(1f), singleLine = true, label = { Text("Адрес сервера бота") })
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, Modifier.width(170.dp), singleLine = true, label = { Text("Код (8 цифр)") })
        }
        if (sectionNames.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Hint("Раздел для подписок:")
                sectionNames.forEach { n -> FilterChip(selected = section == n, onClick = { section = n }, label = { Text(n) }) }
            }
        }
        Button(
            onClick = { m.radarLink(server.trim(), code, section.ifBlank { null }); code = "" },
            enabled = server.isNotBlank() && code.length == 8 && !r.busy,
        ) { Text("Подключить роутер к боту") }
    }
}

@Composable
internal fun RouterChecksCard(r: RouterUi, m: RouterManager) = MCard("Диагностика роутера") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("global" to "Общая", "dns" to "DNS", "singbox" to "sing-box", "nft" to "Файрвол", "proxy" to "Прокси").forEach { (id, t) ->
            OutlinedButton(onClick = { m.runCheck(id) }, enabled = !r.busy) { Text(t) }
        }
    }
    r.checks.forEach { (name, text) ->
        Hint("Проверка «$name»:")
        SelectionContainer {
            Text(text.ifBlank { "(пустой ответ)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp)).padding(8.dp))
        }
    }
    if (r.checks.isEmpty()) Hint("Выберите проверку — роутер выполнит её и вернёт отчёт (до минуты).")
    if (r.statusDetails.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Hint("Состояние службы:")
        r.statusDetails.forEach { (k, v) -> Hint("$k: $v") }
    }
}
