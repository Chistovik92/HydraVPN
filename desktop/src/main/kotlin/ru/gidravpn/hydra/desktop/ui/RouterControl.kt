package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.router.RouterList
import ru.gidravpn.hydra.router.RouterManager
import ru.gidravpn.hydra.router.RouterRule
import ru.gidravpn.hydra.router.RouterUi

// Полное управление роутером 1.3.0+ (возможности приходят из /api/v1/capabilities): обновление роутера,
// ядра и их версии, правила маршрутизации, списки, серверы, настройки (в том числе Telegram-бот) и SMS.
// У старых роутеров этих карточек нет — RoutersScreen показывает их по r.caps.

private typealias Ask = (String, String, () -> Unit) -> Unit

@Composable
internal fun RouterUpdateCard(r: RouterUi, m: RouterManager, ask: Ask) = MCard("Обновление роутера") {
    val u = r.update
    if (u == null) {
        Hint("Роутер не сообщает о самообновлении (контейнер или версия до 1.2.4).")
        return@MCard
    }
    Text("Установлена ${u.optString("current")}" + (u.optString("latest").takeIf { it.isNotBlank() }?.let { ", последняя $it" } ?: ""), fontSize = 13.sp)
    u.optString("state").takeIf { it.isNotBlank() && it != "idle" }?.let { Hint("Состояние: $it") }
    u.optString("error").takeIf { it.isNotBlank() }?.let { Hint("Ошибка: $it") }
    var version by remember { mutableStateOf("") }
    ChipRow {
        OutlinedButton(onClick = { m.checkUpdate() }, enabled = !r.busy) { Text("Проверить") }
        if (u.optBoolean("update_available")) {
            Button(onClick = { ask("Обновить роутер до ${u.optString("latest")}?", "Служба перезапустится сама; трафик за роутером прервётся на несколько секунд.") { m.installUpdate(null) } },
                enabled = !r.busy) { Text("Обновить") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(version, { version = it.trim() }, Modifier.width(200.dp), singleLine = true, label = { Text("Версия (откат)") })
        OutlinedButton(onClick = { ask("Установить версию $version?", "Подходит и для отката на прежнюю версию.") { m.installUpdate(version) } },
            enabled = version.matches(Regex("^v?[0-9]+[.][0-9]+[.][0-9]+(-[0-9A-Za-z.]+)?$")) && !r.busy) { Text("Установить") }
    }
}

@Composable
internal fun RouterCoresCard(r: RouterUi, m: RouterManager, ask: Ask) = MCard("Ядра роутера") {
    Hint("sing-box и Xray — любая версия, включая беты; OpenFlux, olcRTC и AmneziaWG — сборки из релиза роутера.")
    r.cores.forEach { core ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(core.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                Text(core.installed.ifBlank { if (core.binary.isBlank()) "не установлено" else "установлено" }, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (core.selectable) OutlinedButton(onClick = { m.loadCoreVersions(core.name) }, enabled = !r.busy) { Text("Версии…") }
            }
            if (core.installState != "idle") {
                val progress = if (core.size > 0) " ${core.downloaded * 100 / core.size}%" else ""
                Hint("Установка ${core.installVersion}: ${core.installState}$progress ${core.installError}".trim())
            }
            if (core.kept.isNotEmpty()) Hint("На роутере: ${core.kept.joinToString(", ")} — переключение без загрузки")
            r.coreVersions[core.name]?.let { rels ->
                ChipRow {
                    listOf("latest" to "Последняя", "beta" to "Самая новая (бета)").forEach { (v, t) ->
                        OutlinedButton(onClick = { ask("Установить ${core.name}: $t?", "Роутер скачает сборку под свой процессор и перезапустит ядро.") { m.installCore(core.name, v) } },
                            enabled = !r.busy) { Text(t) }
                    }
                }
                ChipRow {
                    rels.take(12).forEach { rel ->
                        FilterChip(selected = rel.version == core.installed, enabled = !r.busy,
                            onClick = { ask("Установить ${core.name} ${rel.version}?", if (rel.prerelease) "Это бета-версия." else "") { m.installCore(core.name, rel.version) } },
                            label = { Text(rel.version + if (rel.prerelease) " β" else "") })
                    }
                }
            }
        }
    }
}

private fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

/** Правило: раздел, домены (суффиксы), адреса, устройства, порт. Остальные поля правила сохраняются как были. */
@Composable
private fun RuleDialog(existing: RouterRule?, sections: List<String>, m: RouterManager, onClose: () -> Unit) {
    val base = existing?.raw ?: JSONObject()
    var section by remember { mutableStateOf(existing?.section ?: sections.firstOrNull().orEmpty()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var domains by remember { mutableStateOf(base.strings("domain_suffix").joinToString("\n")) }
    var ips by remember { mutableStateOf(base.strings("destination").joinToString("\n")) }
    var sources by remember { mutableStateOf(base.strings("source").joinToString("\n")) }
    var port by remember { mutableStateOf(base.optString("port")) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "Новое правило" else "Правило ${existing.index + 1}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow {
                    Hint("Куда:")
                    sections.forEach { s -> FilterChip(selected = section == s, onClick = { section = s }, label = { Text(s) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Switch(enabled, { enabled = it }); Text("Включено") }
                OutlinedTextField(domains, { domains = it }, Modifier.fillMaxWidth().height(90.dp), label = { Text("Домены с поддоменами (по одному в строке)") })
                OutlinedTextField(ips, { ips = it }, Modifier.fillMaxWidth().height(70.dp), label = { Text("Адреса назначения, CIDR") })
                OutlinedTextField(sources, { sources = it }, Modifier.fillMaxWidth().height(70.dp), label = { Text("Устройства-источники (IP/CIDR)") })
                OutlinedTextField(port, { port = it.filter { c -> c.isDigit() || c == ',' || c == '-' } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Порт (443 или 80,443)") })
            }
        },
        confirmButton = {
            TextButton(enabled = section.isNotBlank(), onClick = {
                val rule = JSONObject(base.toString()).apply {
                    put("section", section); put("enabled", enabled)
                    put("domain_suffix", JSONArray(lines(domains))); put("destination", JSONArray(lines(ips)))
                    put("source", JSONArray(lines(sources))); put("port", port.trim())
                }
                m.saveRule(existing?.index, rule); onClose()
            }) { Text("Сохранить", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

@Composable
internal fun RouterRulesCard(r: RouterUi, m: RouterManager, ask: Ask) = MCard("Правила маршрутизации") {
    var editing by remember { mutableStateOf<RouterRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    Hint("Что и через какой раздел идёт: домены, адреса, устройства, порты. Применяется ко всем устройствам за роутером.")
    if (r.rules.isEmpty()) Hint("Правил нет — трафик распределяют разделы и их списки.")
    r.rules.forEach { rule ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("→ ${rule.section}" + if (rule.enabled) "" else " (выключено)", fontSize = 13.sp)
                Hint(rule.summary)
            }
            IconButton(onClick = { editing = rule }, enabled = !r.busy) { Icon(Icons.Default.Edit, "Изменить") }
            IconButton(onClick = { ask("Удалить правило?", rule.summary) { m.deleteRule(rule.index) } }, enabled = !r.busy) { Icon(Icons.Default.Delete, "Удалить") }
        }
    }
    OutlinedButton(onClick = { adding = true }, enabled = !r.busy && r.sections.isNotEmpty()) { Text("Добавить правило") }
    val names = r.sections.map { it.name }
    editing?.let { RuleDialog(it, names, m) { editing = null } }
    if (adding) RuleDialog(null, names, m) { adding = false }
}

@Composable
private fun ListDialog(existing: RouterList?, m: RouterManager, onClose: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var entries by remember { mutableStateOf(existing?.entries.orEmpty().joinToString("\n")) }
    val nameOk = Regex("^[A-Za-z0-9_.-]{1,64}$").matches(name)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "Новый список" else "Список «${existing.name}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Имя (латиница)") })
                OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Ссылка на список (.lst/.srs) — или записи ниже") })
                OutlinedTextField(entries, { entries = it }, Modifier.fillMaxWidth().height(140.dp), label = { Text("Домены и адреса (по одному в строке)") })
                Hint("Список подключается к разделу в его настройках («Списки сообществ»).")
            }
        },
        confirmButton = {
            TextButton(enabled = nameOk, onClick = {
                val list = JSONObject((existing?.raw ?: JSONObject()).toString()).apply {
                    put("name", name.trim()); put("url", url); put("entries", JSONArray(lines(entries)))
                    if (optString("type").isBlank()) put("type", "domain")
                }
                m.saveList(existing?.name, list); onClose()
            }) { Text("Сохранить", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

@Composable
internal fun RouterListsCard(r: RouterUi, m: RouterManager, ask: Ask) = MCard("Списки доменов и адресов") {
    var editing by remember { mutableStateOf<RouterList?>(null) }
    var adding by remember { mutableStateOf(false) }
    r.lists.forEach { l ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(l.name, fontSize = 13.sp)
                Hint(l.url.ifBlank { "${l.entries.size} записей" })
            }
            IconButton(onClick = { editing = l }, enabled = !r.busy) { Icon(Icons.Default.Edit, "Изменить") }
            IconButton(onClick = { ask("Удалить список «${l.name}»?", "Список, который использует раздел, роутер удалить не даст.") { m.deleteList(l.name) } }, enabled = !r.busy) {
                Icon(Icons.Default.Delete, "Удалить")
            }
        }
    }
    OutlinedButton(onClick = { adding = true }, enabled = !r.busy) { Text("Добавить список") }
    editing?.let { ListDialog(it, m) { editing = null } }
    if (adding) ListDialog(null, m) { adding = false }
}

private val SERVER_TEMPLATE = JSONObject().put("name", "vless-in").put("enabled", true).put("protocol", "vless")
    .put("listen", "0.0.0.0").put("listen_port", 8443).put("routing_mode", "rules")

/** Сервер правится как JSON (поля servers: роутера, docs/API.md); скрытые ключи роутер оставит прежними. */
@Composable
private fun ServerDialog(existing: JSONObject?, m: RouterManager, onClose: () -> Unit) {
    var text by remember { mutableStateOf((existing ?: SERVER_TEMPLATE).toString(2)) }
    val parsed = runCatching { JSONObject(text) }.getOrNull()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "Новый сервер" else "Сервер «${existing.optString("name")}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(260.dp), isError = parsed == null,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), label = { Text("JSON") })
                Hint("Поля «********» — скрытые ключи: роутер оставит их как были.")
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && parsed.optString("name").isNotBlank(), onClick = {
                parsed?.let { m.saveServer(existing?.optString("name"), it) }; onClose()
            }) { Text("Сохранить", color = Accent) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

@Composable
internal fun RouterServersCard(r: RouterUi, m: RouterManager, ask: Ask) = MCard("Серверы на роутере") {
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var adding by remember { mutableStateOf(false) }
    Hint("Входящие подключения к роутеру (VLESS, Hysteria2, Tailscale…): заходить домой извне.")
    r.servers.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(s.optString("name") + if (s.optBoolean("enabled")) "" else " (выключен)", fontSize = 13.sp)
                Hint("${s.optString("protocol")} · порт ${s.optInt("listen_port")}")
            }
            IconButton(onClick = { editing = s }, enabled = !r.busy) { Icon(Icons.Default.Edit, "Изменить") }
            IconButton(onClick = { ask("Удалить сервер «${s.optString("name")}»?", "Подключения к нему перестанут работать.") { m.deleteServer(s.optString("name")) } }, enabled = !r.busy) {
                Icon(Icons.Default.Delete, "Удалить")
            }
        }
    }
    OutlinedButton(onClick = { adding = true }, enabled = !r.busy) { Text("Добавить сервер") }
    editing?.let { ServerDialog(it, m) { editing = null } }
    if (adding) ServerDialog(null, m) { adding = false }
}

/** Переключатели настроек роутера, которые чаще всего меняют. */
private val TOGGLES = listOf(
    "disable_quic" to "Запретить QUIC (сайты перейдут на TCP и маршрутизируются по SNI)",
    "exclude_ntp" to "NTP всегда напрямую",
    "enable_ipv6" to "IPv6",
    "fakeip_enabled" to "FakeIP",
    "download_lists_via_proxy" to "Загружать списки через прокси",
    "download_components_via_proxy" to "Загружать обновления и ядра через прокси",
    "list_update_enabled" to "Обновлять списки автоматически",
    "component_update_check_enabled" to "Проверять обновления",
)

@Composable
private fun SettingSwitch(r: RouterUi, m: RouterManager, s: JSONObject, key: String, title: String) {
    if (!s.has(key)) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(s.optBoolean(key), { m.setSetting(key, it) }, enabled = !r.busy)
        Text(title, fontSize = 13.sp)
    }
}

@Composable
internal fun RouterSettingsCard(r: RouterUi, m: RouterManager) = MCard("Настройки роутера") {
    val s = r.settings
    if (s == null) {
        Hint("Роутер не отдаёт настройки.")
        return@MCard
    }
    TOGGLES.forEach { (key, title) -> SettingSwitch(r, m, s, key, title) }
    var dns by remember(s) { mutableStateOf(s.strings("dns_server").joinToString(", ")) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(dns, { dns = it }, Modifier.weight(1f), singleLine = true, label = { Text("DNS-серверы (через запятую)") })
        OutlinedButton(onClick = { m.setSetting("dns_server", dns.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }, enabled = !r.busy) { Text("Сохранить") }
    }
    if (!s.has("telegram_bot_token")) return@MCard
    Text("Telegram-бот и SMS", fontWeight = FontWeight.Medium, fontSize = 13.sp)
    Hint("Управление роутером из Telegram и SMS с модема роутера. Токен — у @BotFather; ID чата бот пришлёт сам на первое сообщение.")
    var token by remember { mutableStateOf("") }
    var chats by remember(s) { mutableStateOf(s.optJSONArray("telegram_chat_ids")?.let { a -> (0 until a.length()).joinToString(", ") { a.optLong(it).toString() } }.orEmpty()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(token, { token = it.trim() }, Modifier.weight(1f), singleLine = true, visualTransformation = PasswordVisualTransformation(),
            label = { Text(if (s.optString("telegram_bot_token").isNotBlank()) "Токен задан — новый токен" else "Токен бота") })
        OutlinedButton(onClick = { m.setSetting("telegram_bot_token", token); token = "" }, enabled = token.contains(':') && !r.busy) { Text("Сохранить") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(chats, { chats = it }, Modifier.weight(1f), singleLine = true, label = { Text("ID чатов (через запятую)") })
        OutlinedButton(onClick = { m.setSetting("telegram_chat_ids", chats.split(',').mapNotNull { it.trim().toLongOrNull() }) }, enabled = !r.busy) { Text("Сохранить") }
    }
    SettingSwitch(r, m, s, "telegram_via_proxy", "Бот через прокси (если Telegram заблокирован)")
    SettingSwitch(r, m, s, "sms_forward", "Пересылать SMS в Telegram")
    SettingSwitch(r, m, s, "sms_delete_forwarded", "Удалять доставленные SMS с модема")
}

@Composable
internal fun RouterSmsCard(r: RouterUi, m: RouterManager) = MCard("SMS с модема роутера") {
    if (r.smsModems.isEmpty()) {
        Hint("Модем с SIM-картой не найден (KeeneticOS, ModemManager или smstools3).")
        return@MCard
    }
    Hint("Источник: ${r.smsModems.joinToString(", ")}")
    if (r.sms.isEmpty()) Hint("SMS ещё не приходили.")
    r.sms.takeLast(10).reversed().forEach { s ->
        Text("${s.from} · ${s.time}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(s.text, fontSize = 13.sp)
    }
    var to by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(to, { to = it.filter { c -> c.isDigit() || c == '+' } }, Modifier.width(170.dp), singleLine = true, label = { Text("Номер") })
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, label = { Text("Текст") })
        OutlinedButton(onClick = { m.sendSms(to, text); text = "" }, enabled = to.length >= 3 && text.isNotBlank() && !r.busy) { Text("Отправить") }
    }
    TextButton(onClick = { m.refreshSms() }, enabled = !r.busy) { Text("Обновить") }
}
