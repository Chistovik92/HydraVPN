package ru.gidravpn.hydra.ui.screens

import ru.gidravpn.hydra.ui.launchSafe
import ru.gidravpn.hydra.ui.launchScan
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.router.RouterLink
import ru.gidravpn.hydra.router.RouterManager
import ru.gidravpn.hydra.router.RouterUi
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.RoutersViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.Label
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

/** Действие на роутере, которое надо подтвердить: оно меняет то, что видят все устройства сети. */
private class Pending(val title: String, val text: String, val action: () -> Unit)

@Composable
fun RoutersScreen(vm: MainViewModel, rvm: RoutersViewModel = viewModel()) {
    val m = rvm.manager
    val links by rvm.links.collectAsState()
    val r by m.ui.collectAsState()
    val message by rvm.message.collectAsState()
    val current = links.firstOrNull { it.baseUrl == r.selected }
    var confirm by remember { mutableStateOf<Pending?>(null) }
    val ctx = LocalContext.current

    LaunchedEffect(message) {
        message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); rvm.messageShown() }
    }
    // Пока вкладка открыта — опрашиваем выбранный роутер.
    LaunchedEffect(r.selected) {
        while (r.selected != null) { m.refresh(); delay(10_000) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.rt_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
        item { AddRouter(m) }
        if (links.isEmpty()) {
            item { Text(stringResource(R.string.rt_empty), color = TextMuted, fontSize = 13.sp) }
        } else {
            item { RouterPicker(links, r.selected, m) }
            if (current != null) {
                item { Actions(current, r, m) { confirm = it } }
                item { StatusCard(current, r) }
                // Роутер 1.3.0+: по его возможностям (capabilities); у старых роутеров этих карточек нет.
                if (r.caps.has("update")) item { UpdateCard(r, m) { confirm = it } }
                item { NodesCard(r, m) }
                item { SectionsCard(r, m) }
                if (r.caps.has("rules")) item { RulesCard(r, m) { confirm = it } }
                if (r.caps.has("lists")) item { ListsCard(r, m) { confirm = it } }
                item { SubscriptionsCard(vm, r, m) { confirm = it } }
                if (r.caps.has("servers")) item { ServersCard(r, m) { confirm = it } }
                if (r.caps.has("cores")) item { CoresCard(r, m) { confirm = it } }
                if (r.caps.has("settings")) item { SettingsCard(r, m) }
                if (r.caps.has("sms")) item { SmsCard(r, m) }
                item { LogCard(r) }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(p.title, color = TextPrimary) },
            text = { Text(p.text, color = TextSecondary) },
            containerColor = Surface,
            confirmButton = { TextButton({ confirm = null; p.action() }) { Text(stringResource(R.string.rt_yes), color = Danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.action_cancel), color = TextMuted) } },
        )
    }
}

@Composable
private fun Chip(text: String, color: Color, selected: Boolean = false, onClick: () -> Unit) {
    val c = if (selected) AccentCyan else color
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(c.copy(alpha = if (selected) 0.18f else 0.10f))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickableNoRipple(onClick)
            .defaultMinSize(minHeight = 40.dp).padding(horizontal = 12.dp, vertical = 10.dp)
    ) { Text(text, color = c, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = TextMuted, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
            focusedContainerColor = InputBg, unfocusedContainerColor = InputBg,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AddRouter(m: RouterManager) {
    var link by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val prompt = stringResource(R.string.rt_scan_prompt)
    val ctxAdd = LocalContext.current
    val scan = androidx.activity.compose.rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { res ->
        res.contents?.let { link = it }
    }
    Card {
        Field(stringResource(R.string.rt_link_hint), link) { link = it }
        Spacer(Modifier.height(8.dp))
        Field(stringResource(R.string.rt_token_hint), token, secret = true) { token = it }
        Spacer(Modifier.height(8.dp))
        Field(stringResource(R.string.rt_name_hint), name) { name = it }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.rt_scan), AccentViolet) {
                scan.launchScan(ctxAdd,
                    com.journeyapps.barcodescanner.ScanOptions()
                        .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                        .setPrompt(prompt).setBeepEnabled(false).setOrientationLocked(false)
                )
            }
            if (link.isNotBlank()) Chip(stringResource(R.string.rt_add), AccentCyan) {
                if (m.add(link, token, name)) { link = ""; token = ""; name = "" }
            }
        }
    }
}

@Composable
private fun RouterPicker(links: List<RouterLink>, selected: String?, m: RouterManager) {
    val noTls = stringResource(R.string.rt_no_tls)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEach { l ->
            Chip(l.name + if (l.insecure) " · $noTls" else "", TextSecondary, selected = l.baseUrl == selected) { m.select(l.baseUrl) }
        }
    }
}

@Composable
private fun Actions(current: RouterLink, r: RouterUi, m: RouterManager, ask: (Pending) -> Unit) {
    val restartQ = stringResource(R.string.rt_restart_q)
    val restartMsg = stringResource(R.string.rt_restart_msg, current.name)
    val forgetQ = stringResource(R.string.rt_forget_q)
    val forgetMsg = stringResource(R.string.rt_forget_msg, current.name)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.rt_refresh), TextSecondary) { if (!r.busy) m.refresh() }
        Chip(stringResource(R.string.rt_reload), TextSecondary) { if (!r.busy) m.reloadConfig() }
        Chip(stringResource(R.string.rt_restart), Danger) { if (!r.busy) ask(Pending(restartQ, restartMsg) { m.restart() }) }
        Chip(stringResource(R.string.rt_forget), Danger) { ask(Pending(forgetQ, forgetMsg) { m.remove(current.baseUrl) }) }
    }
}

@Composable
private fun StatusCard(link: RouterLink, r: RouterUi) = Card {
    Label(stringResource(R.string.rt_state))
    val (label, color) = when {
        r.busy && !r.online && r.error == null -> stringResource(R.string.rt_connecting) to AccentViolet
        r.online -> stringResource(R.string.rt_online) to Success
        else -> stringResource(R.string.rt_offline) to Danger
    }
    Text(label, color = color, fontWeight = FontWeight.Bold)
    val bits = buildList {
        if (r.version.isNotEmpty()) add(stringResource(R.string.rt_version, r.version))
        if (r.state.isNotEmpty()) add(stringResource(R.string.rt_service, r.state))
        if (r.uptime.isNotEmpty()) add(stringResource(R.string.rt_uptime, r.uptime))
    }
    if (bits.isNotEmpty()) Text(bits.joinToString(" · "), color = TextMuted, fontSize = 12.sp)
    Text("${link.host}:${link.port}", color = TextMuted, fontSize = 12.sp)
    if (link.insecure) Text(stringResource(R.string.rt_insecure), color = AccentViolet, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    r.error?.let { Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
    if (r.lastError.isNotEmpty()) {
        Text(stringResource(R.string.rt_last_error, r.lastError), color = AccentViolet, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun NodesCard(r: RouterUi, m: RouterManager) = Card {
    Label(stringResource(R.string.rt_nodes))
    r.nodesError?.let { Text(stringResource(R.string.rt_nodes_error, it), color = AccentViolet, fontSize = 12.sp) }
    val byName = r.nodes.associateBy { it.name }
    val groups = r.nodes.filter { it.isGroup && it.type.equals("Selector", true) }
    if (groups.isEmpty() && r.nodesError == null) Text(stringResource(R.string.rt_nodes_empty), color = TextMuted, fontSize = 12.sp)
    groups.forEach { g ->
        Text(g.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        g.members.forEach { name ->
            val ms = r.delays[name] ?: byName[name]?.delayMs ?: 0
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Chip(name, TextSecondary, selected = g.now == name) { m.selectNode(g.name, name) } }
                Spacer(Modifier.width(8.dp))
                when {
                    ms > 0 -> Text(stringResource(R.string.rt_ms, ms), color = PingFast, fontSize = 12.sp)
                    ms < 0 -> Text(stringResource(R.string.rt_no_answer), color = Danger, fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Chip(stringResource(R.string.rt_test), AccentIndigo) { if (!r.busy) m.testNode(name) }
            }
        }
    }
}

@Composable
private fun SectionsCard(r: RouterUi, m: RouterManager) = Card {
    Label(stringResource(R.string.rt_sections))
    if (r.sections.isEmpty()) Text(stringResource(R.string.rt_sections_empty), color = TextMuted, fontSize = 12.sp)
    val off = stringResource(R.string.rt_section_off)
    r.sections.forEach { s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, color = TextPrimary, fontSize = 13.sp)
                Text("${s.action} · ${s.provider}" + if (s.enabled) "" else " · $off", color = TextMuted, fontSize = 12.sp)
            }
            // Включить/выключить раздел (роутер 1.3.0 сам сохранит скрытые ключи раздела).
            if (s.editable) Chip(stringResource(if (s.enabled) R.string.rt_disable else R.string.rt_enable), TextSecondary) {
                if (!r.busy) m.setSectionEnabled(s, !s.enabled)
            }
        }
    }
}

private fun csv(s: String) = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun UpdateCard(r: RouterUi, m: RouterManager, ask: (Pending) -> Unit) = Card {
    Label(stringResource(R.string.rt_update))
    val u = r.update ?: return@Card
    Text(stringResource(R.string.rt_update_info, u.optString("current"), u.optString("latest").ifBlank { "?" }), color = TextPrimary, fontSize = 13.sp)
    u.optString("error").takeIf { it.isNotBlank() }?.let { Text(it, color = Danger, fontSize = 12.sp) }
    val q = stringResource(R.string.rt_update_q, u.optString("latest"))
    val msg = stringResource(R.string.rt_update_msg)
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.rt_update_check), TextSecondary) { if (!r.busy) m.checkUpdate() }
        if (u.optBoolean("update_available")) Chip(stringResource(R.string.rt_update_install), AccentCyan) {
            if (!r.busy) ask(Pending(q, msg) { m.installUpdate(null) })
        }
    }
}

@Composable
private fun CoresCard(r: RouterUi, m: RouterManager, ask: (Pending) -> Unit) = Card {
    Label(stringResource(R.string.rt_cores))
    Text(stringResource(R.string.rt_cores_hint), color = TextMuted, fontSize = 12.sp)
    val none = stringResource(R.string.rt_core_not_installed)
    val msg = stringResource(R.string.rt_core_install_msg)
    val qFmt = stringResource(R.string.rt_core_install_q)
    r.cores.forEach { core ->
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(core.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(core.installed.ifBlank { none } + if (core.installState != "idle") " · ${core.installState} ${core.installError}" else "",
                    color = TextMuted, fontSize = 12.sp)
            }
            if (core.selectable) Chip(stringResource(R.string.rt_core_versions), TextSecondary) { if (!r.busy) m.loadCoreVersions(core.name) }
        }
        r.coreVersions[core.name]?.let { rels ->
            val choices = listOf("latest" to stringResource(R.string.rt_core_latest), "beta" to stringResource(R.string.rt_core_beta)) +
                rels.take(10).map { it.version to it.version + if (it.prerelease) " β" else "" }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { (v, t) ->
                    Chip(t, AccentViolet, selected = v == core.installed) {
                        if (!r.busy) ask(Pending(qFmt.format(core.name, t), msg) { m.installCore(core.name, v) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesCard(r: RouterUi, m: RouterManager, ask: (Pending) -> Unit) = Card {
    Label(stringResource(R.string.rt_rules))
    if (r.rules.isEmpty()) Text(stringResource(R.string.rt_rules_empty), color = TextMuted, fontSize = 12.sp)
    val delMsg = stringResource(R.string.rt_delete_msg)
    val delFmt = stringResource(R.string.rt_delete_q)
    r.rules.forEach { rule ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("→ ${rule.section}" + if (rule.enabled) "" else " · ${stringResource(R.string.rt_section_off)}", color = TextPrimary, fontSize = 13.sp)
                Text(rule.summary, color = TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Chip(stringResource(R.string.rt_delete), Danger) { if (!r.busy) ask(Pending(delFmt.format(rule.summary), delMsg) { m.deleteRule(rule.index) }) }
        }
    }
    if (r.sections.isEmpty()) return@Card
    var section by remember(r.sections) { mutableStateOf(r.sections.first().name) }
    var domains by remember { mutableStateOf("") }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        r.sections.forEach { s -> Chip(s.title, TextSecondary, selected = section == s.name) { section = s.name } }
    }
    Field(stringResource(R.string.rt_rule_domains), domains) { domains = it }
    if (domains.isNotBlank()) Box(Modifier.padding(top = 8.dp)) {
        Chip(stringResource(R.string.rt_rule_add), AccentCyan) {
            if (!r.busy) {
                m.saveRule(null, org.json.JSONObject().put("section", section).put("enabled", true).put("domain_suffix", org.json.JSONArray(csv(domains))))
                domains = ""
            }
        }
    }
}

@Composable
private fun ListsCard(r: RouterUi, m: RouterManager, ask: (Pending) -> Unit) = Card {
    Label(stringResource(R.string.rt_lists))
    val delMsg = stringResource(R.string.rt_delete_msg)
    val delFmt = stringResource(R.string.rt_delete_q)
    r.lists.forEach { l ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.name, color = TextPrimary, fontSize = 13.sp)
                Text(l.url.ifBlank { l.entries.joinToString(", ") }, color = TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Chip(stringResource(R.string.rt_delete), Danger) { if (!r.busy) ask(Pending(delFmt.format(l.name), delMsg) { m.deleteList(l.name) }) }
        }
    }
    var name by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf("") }
    Spacer(Modifier.height(6.dp))
    Field(stringResource(R.string.rt_list_name), name) { name = it.filter { c -> c.isLetterOrDigit() || c in "_.-" } }
    Spacer(Modifier.height(6.dp))
    Field(stringResource(R.string.rt_list_entries), entries) { entries = it }
    if (name.isNotBlank() && entries.isNotBlank()) Box(Modifier.padding(top = 8.dp)) {
        Chip(stringResource(R.string.rt_list_add), AccentCyan) {
            if (!r.busy) {
                m.saveList(null, org.json.JSONObject().put("name", name).put("type", "domain").put("entries", org.json.JSONArray(csv(entries))))
                name = ""; entries = ""
            }
        }
    }
}

@Composable
private fun ServersCard(r: RouterUi, m: RouterManager, ask: (Pending) -> Unit) = Card {
    Label(stringResource(R.string.rt_servers))
    val delMsg = stringResource(R.string.rt_delete_msg)
    val delFmt = stringResource(R.string.rt_delete_q)
    val off = stringResource(R.string.rt_section_off)
    r.servers.forEach { s ->
        val name = s.optString("name")
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name + if (s.optBoolean("enabled")) "" else " · $off", color = TextPrimary, fontSize = 13.sp)
                Text(stringResource(R.string.rt_server_info, s.optString("protocol"), s.optInt("listen_port")), color = TextMuted, fontSize = 12.sp)
            }
            Chip(stringResource(if (s.optBoolean("enabled")) R.string.rt_disable else R.string.rt_enable), TextSecondary) {
                // Секреты сервера приходят замаскированными — роутер 1.3.0 оставит их прежними.
                if (!r.busy) m.saveServer(name, org.json.JSONObject(s.toString()).put("enabled", !s.optBoolean("enabled")))
            }
            Spacer(Modifier.width(6.dp))
            Chip(stringResource(R.string.rt_delete), Danger) { if (!r.busy) ask(Pending(delFmt.format(name), delMsg) { m.deleteServer(name) }) }
        }
    }
}

@Composable
private fun SettingsCard(r: RouterUi, m: RouterManager) = Card {
    Label(stringResource(R.string.rt_settings))
    val s = r.settings ?: return@Card
    @Composable
    fun toggle(key: String, title: String) {
        if (s.has(key)) Box(Modifier.padding(top = 6.dp)) {
            Chip((if (s.optBoolean(key)) "✓ " else "") + title, TextSecondary, selected = s.optBoolean(key)) { if (!r.busy) m.setSetting(key, !s.optBoolean(key)) }
        }
    }
    toggle("disable_quic", stringResource(R.string.rt_set_quic))
    toggle("exclude_ntp", stringResource(R.string.rt_set_ntp))
    toggle("enable_ipv6", stringResource(R.string.rt_set_ipv6))
    if (!s.has("telegram_bot_token")) return@Card
    Text(stringResource(R.string.rt_telegram), color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    var token by remember { mutableStateOf("") }
    var chats by remember(s) {
        mutableStateOf(s.optJSONArray("telegram_chat_ids")?.let { a -> (0 until a.length()).joinToString(", ") { a.optLong(it).toString() } }.orEmpty())
    }
    Spacer(Modifier.height(6.dp))
    Field(stringResource(R.string.rt_tg_token), token, secret = true) { token = it.trim() }
    if (token.contains(':')) Box(Modifier.padding(top = 6.dp)) {
        Chip(stringResource(R.string.rt_save), AccentCyan) { if (!r.busy) { m.setSetting("telegram_bot_token", token); token = "" } }
    }
    Spacer(Modifier.height(6.dp))
    Field(stringResource(R.string.rt_tg_chats), chats) { chats = it }
    Box(Modifier.padding(top = 6.dp)) {
        Chip(stringResource(R.string.rt_save), AccentCyan) { if (!r.busy) m.setSetting("telegram_chat_ids", csv(chats).mapNotNull { it.toLongOrNull() }) }
    }
    toggle("telegram_via_proxy", stringResource(R.string.rt_tg_proxy))
    toggle("sms_forward", stringResource(R.string.rt_sms_forward))
}

@Composable
private fun SmsCard(r: RouterUi, m: RouterManager) = Card {
    Label(stringResource(R.string.rt_sms))
    if (r.smsModems.isEmpty()) {
        Text(stringResource(R.string.rt_sms_none), color = TextMuted, fontSize = 12.sp)
        return@Card
    }
    r.sms.takeLast(10).reversed().forEach { s ->
        Text("${s.from} · ${s.time}", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Text(s.text, color = TextPrimary, fontSize = 13.sp)
    }
    var to by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    Spacer(Modifier.height(8.dp))
    Field(stringResource(R.string.rt_sms_to), to) { to = it.filter { c -> c.isDigit() || c == '+' } }
    Spacer(Modifier.height(6.dp))
    Field(stringResource(R.string.rt_sms_text), text) { text = it }
    if (to.length >= 3 && text.isNotBlank()) Box(Modifier.padding(top = 8.dp)) {
        Chip(stringResource(R.string.rt_sms_send), AccentCyan) { if (!r.busy) { m.sendSms(to, text); text = "" } }
    }
}

@Composable
private fun SubscriptionsCard(vm: MainViewModel, r: RouterUi, m: RouterManager, ask: (Pending) -> Unit) = Card {
    Label(stringResource(R.string.rt_subs))
    if (r.subscriptions.isEmpty()) Text(stringResource(R.string.rt_subs_empty), color = TextMuted, fontSize = 12.sp)
    val delQ = stringResource(R.string.rt_delete_sub_q)
    val delMsg = stringResource(R.string.rt_delete_sub_msg)
    r.subscriptions.forEach { s ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(s.url, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (s.autoUpdate) stringResource(R.string.rt_sub_info, s.section, "%.0f".format(s.updateIntervalHours))
                else stringResource(R.string.rt_sub_info_manual, s.section),
                color = TextMuted, fontSize = 12.sp,
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.rt_sub_refresh), AccentCyan) { if (!r.busy) m.refreshSubscription(s.index) }
                Chip(stringResource(R.string.rt_sub_delete), Danger) { if (!r.busy) ask(Pending(delQ, delMsg) { m.deleteSubscription(s.index) }) }
            }
        }
    }
    if (r.sections.isEmpty()) {
        Text(stringResource(R.string.rt_need_section), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        return@Card
    }
    var section by remember(r.sections) { mutableStateOf(r.sections.first().name) }
    var url by remember { mutableStateOf("") }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.rt_section_label), color = TextMuted, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        r.sections.forEach { s -> Chip(s.title, TextSecondary, selected = section == s.name) { section = s.name } }
    }
    Field(stringResource(R.string.rt_sub_url), url) { url = it }
    if (url.isNotBlank()) Box(Modifier.padding(top = 8.dp)) {
        Chip(stringResource(R.string.rt_add), AccentCyan) { if (!r.busy) { m.addSubscription(section, url.trim()); url = "" } }
    }
    val own by vm.subscriptions.collectAsState()
    val urls = own.filter { it.url.startsWith("http", true) }
    if (urls.isNotEmpty()) {
        Text(stringResource(R.string.rt_send_from_hydra), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            urls.forEach { s -> Chip(s.displayName, AccentViolet) { if (!r.busy) m.addSubscription(section, s.url) } }
        }
    }
}

@Composable
private fun LogCard(r: RouterUi) = Card {
    Label(stringResource(R.string.rt_log))
    if (r.logs.isEmpty()) {
        Text(stringResource(R.string.rt_log_empty), color = TextMuted, fontSize = 12.sp)
        return@Card
    }
    SelectionContainer {
        Column {
            r.logs.takeLast(40).forEach { e ->
                val color = when (e.level.lowercase()) {
                    "error" -> Danger
                    "warn", "warning" -> AccentViolet
                    else -> TextSecondary
                }
                Text(
                    "${e.time.substringAfter('T').substringBefore('.').take(8)} ${e.level.uppercase()} ${e.message}",
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = color,
                )
            }
        }
    }
}
