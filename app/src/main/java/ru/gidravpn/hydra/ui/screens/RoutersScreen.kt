package ru.gidravpn.hydra.ui.screens

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
                item { NodesCard(r, m) }
                item { SectionsCard(r) }
                item { SubscriptionsCard(vm, r, m) { confirm = it } }
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
            .padding(horizontal = 12.dp, vertical = 8.dp)
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
                scan.launch(
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
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(stringResource(R.string.rt_refresh), TextSecondary) { if (!r.busy) m.refresh() }
        Chip(stringResource(R.string.rt_reload), TextSecondary) { if (!r.busy) m.reloadConfig() }
        Chip(stringResource(R.string.rt_restart), Danger) { if (!r.busy) ask(Pending(restartQ, restartMsg) { m.restart() }) }
        Chip(stringResource(R.string.rt_forget), Danger) { m.remove(current.baseUrl) }
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
private fun SectionsCard(r: RouterUi) = Card {
    Label(stringResource(R.string.rt_sections))
    if (r.sections.isEmpty()) Text(stringResource(R.string.rt_sections_empty), color = TextMuted, fontSize = 12.sp)
    val off = stringResource(R.string.rt_section_off)
    r.sections.forEach { s ->
        Column(Modifier.padding(vertical = 3.dp)) {
            Text(s.title, color = TextPrimary, fontSize = 13.sp)
            Text("${s.action} · ${s.provider}" + if (s.enabled) "" else " · $off", color = TextMuted, fontSize = 12.sp)
        }
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
