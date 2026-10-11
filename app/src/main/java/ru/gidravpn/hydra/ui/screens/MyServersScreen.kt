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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.servers.AwgGeneration
import ru.gidravpn.hydra.servers.MyServer
import ru.gidravpn.hydra.servers.MyServersManager
import ru.gidravpn.hydra.servers.MyServersUi
import ru.gidravpn.hydra.servers.ServerForm
import ru.gidravpn.hydra.servers.SshTarget
import ru.gidravpn.hydra.servers.Stage
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.MyServersViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.Label
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

/** Действие над развёрнутым сервером, которому нужен вход по SSH (пароль — в памяти, не сохраняется). */
private class NeedLogin(val title: String, val server: MyServer, val run: (SshTarget) -> Unit)

@Composable
fun MyServersScreen(vm: MainViewModel, onBack: () -> Unit, mvm: MyServersViewModel = viewModel()) {
    val m = mvm.manager
    val s by m.ui.collectAsState()
    val servers by mvm.servers.collectAsState()
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var wizard by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<NeedLogin?>(null) }
    var confirm by remember { mutableStateOf<NeedLogin?>(null) }
    var addClientFor by remember { mutableStateOf<MyServer?>(null) }

    val addedFmt = stringResource(R.string.ms_added, "%s")
    DisposableEffect(Unit) {
        mvm.importConf = { name, conf ->
            vm.importAuto(listOf(conf))
            android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, addedFmt.format(name), Toast.LENGTH_SHORT).show() }
        }
        onDispose { mvm.importConf = { _, _ -> } }
    }
    androidx.activity.compose.BackHandler { onBack() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ms_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                MsChip(stringResource(R.string.ms_back), TextSecondary) { onBack() }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.ms_intro), color = TextMuted, fontSize = 12.sp)
        }
        item {
            if (!wizard && s.stage == Stage.IDLE) MsChip(stringResource(R.string.ms_add), AccentCyan) { if (!s.busy) { m.reset(); wizard = true } }
        }
        if (wizard || s.stage != Stage.IDLE) item { WizardCard(m, s) { wizard = false; m.reset() } }
        if (servers.isEmpty() && !wizard && s.stage == Stage.IDLE) item { Text(stringResource(R.string.ms_empty), color = TextMuted, fontSize = 13.sp) }
        servers.forEach { srv ->
            item(key = srv.id) {
                val removeFmt = stringResource(R.string.ms_act_remove_client, "%s")
                val uninstallFmt = stringResource(R.string.ms_uninstall_q, srv.name)
                ServerCard(srv, s.busy,
                    onAddClient = { addClientFor = srv },
                    onShow = { name -> m.clientConf(srv.id, name)?.let { m.showConf(name, it) } },
                    onRemoveClient = { name -> login = NeedLogin(removeFmt.format(name), srv) { t -> m.removeClient(srv.id, t, name) } },
                    onUninstall = { confirm = NeedLogin(uninstallFmt, srv) { t -> m.uninstall(srv.id, t) } },
                    onForget = { m.forget(srv.id) })
            }
        }
        if (s.log.isNotEmpty() && s.stage == Stage.IDLE) item { LogBox(s.log) }
        s.error?.takeIf { s.stage == Stage.IDLE }?.let { item { Text(it, color = Danger, fontSize = 13.sp) } }
        item { Spacer(Modifier.height(16.dp)) }
    }

    addClientFor?.let { srv ->
        var name by remember { mutableStateOf("") }
        val addFmt = stringResource(R.string.ms_act_add_client, "%s")
        AlertDialog(
            onDismissRequest = { addClientFor = null },
            containerColor = Surface,
            title = { Text(stringResource(R.string.ms_new_client), color = TextPrimary) },
            text = { MsField(stringResource(R.string.ms_client_name), name) { name = it.filter { ch -> ch.isLetterOrDigit() || ch in "-_" }.take(24) } },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && srv.clients.none { it.name == name }, onClick = {
                    addClientFor = null
                    login = NeedLogin(addFmt.format(name), srv) { t -> m.addClient(srv.id, t, name) }
                }) { Text(stringResource(R.string.ms_next), color = AccentCyan) }
            },
            dismissButton = { TextButton({ addClientFor = null }) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
        )
    }
    confirm?.let { n ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Surface,
            title = { Text(n.title, color = TextPrimary) },
            text = { Text(stringResource(R.string.ms_uninstall_msg, n.server.host), color = TextSecondary) },
            confirmButton = { TextButton({ confirm = null; login = n }) { Text(stringResource(R.string.ms_next), color = Danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
        )
    }
    login?.let { n -> LoginDialog(n, onCancel = { login = null }) { t -> login = null; n.run(t) } }
    s.shown?.let { (name, conf) ->
        val copied = stringResource(R.string.ms_copied)
        AlertDialog(
            onDismissRequest = { m.hideShown() },
            containerColor = Surface,
            title = { Text(stringResource(R.string.ms_client_title, name), color = TextPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 340.dp)) {
                    Text(stringResource(R.string.ms_client_hint), fontSize = 12.sp, color = TextMuted)
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer { Text(conf, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary) }
                }
            },
            confirmButton = {
                Row {
                    TextButton({ clip.setText(AnnotatedString(conf)); Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show() }) { Text(stringResource(R.string.ms_copy), color = AccentCyan) }
                    TextButton({ vm.importAuto(listOf(conf)); m.hideShown() }) { Text(stringResource(R.string.ms_add_to_servers), color = AccentCyan) }
                }
            },
            dismissButton = { TextButton({ m.hideShown() }) { Text(stringResource(R.string.ms_close), color = TextMuted) } },
        )
    }
}

@Composable
private fun ServerCard(
    srv: MyServer, busy: Boolean,
    onAddClient: () -> Unit, onShow: (String) -> Unit, onRemoveClient: (String) -> Unit, onUninstall: () -> Unit, onForget: () -> Unit,
) = Card {
    Text(srv.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.ms_server_line, srv.host, srv.sshPort, srv.awg?.generation?.label ?: "—", srv.awg?.port ?: 0), color = TextMuted, fontSize = 12.sp)
    Text(stringResource(R.string.ms_fingerprint, srv.fingerprint), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    Spacer(Modifier.height(8.dp))
    srv.clients.forEach { cl ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 2.dp)) {
            Text("${cl.name} · ${cl.address.substringBefore('/')}", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            MsChip(stringResource(R.string.ms_show_conf), AccentViolet) { onShow(cl.name) }
            if (srv.clients.size > 1) MsChip(stringResource(R.string.ms_delete), Danger) { if (!busy) onRemoveClient(cl.name) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MsChip(stringResource(R.string.ms_add_client), AccentCyan) { if (!busy) onAddClient() }
        MsChip(stringResource(R.string.ms_uninstall), Danger) { if (!busy) onUninstall() }
        MsChip(stringResource(R.string.ms_forget), TextSecondary) { if (!busy) onForget() }
    }
}

@Composable
private fun WizardCard(m: MyServersManager, s: MyServersUi, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var gen by remember { mutableStateOf(AwgGeneration.V2_0) }
    var awgPort by remember { mutableStateOf("51820") }
    var openFw by remember { mutableStateOf(false) }
    val defaultName = stringResource(R.string.ms_name_default)
    val yes = stringResource(R.string.ms_yes)
    val no = stringResource(R.string.ms_no)
    fun yn(b: Boolean) = if (b) yes else no

    Card {
        Label(stringResource(R.string.ms_wizard_title))
        if (s.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), color = AccentCyan, strokeWidth = 2.dp)
            Text(s.progress, color = TextSecondary, fontSize = 13.sp)
        }
        when (s.stage) {
            Stage.IDLE, Stage.FAILED -> {
                s.error?.let { Text(it, color = Danger, fontSize = 13.sp); Spacer(Modifier.height(6.dp)) }
                MsField(stringResource(R.string.ms_name), name) { name = it.take(40) }
                Spacer(Modifier.height(8.dp))
                MsField(stringResource(R.string.ms_host), host) { host = it.trim() }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { MsField(stringResource(R.string.ms_ssh_port), sshPort) { sshPort = it.filter(Char::isDigit).take(5) } }
                    Box(Modifier.weight(1.4f)) { MsField(stringResource(R.string.ms_user), user) { user = it.trim() } }
                }
                Spacer(Modifier.height(8.dp))
                MsField(stringResource(R.string.ms_password), password, secret = true) { password = it }
                Spacer(Modifier.height(8.dp))
                MsField(stringResource(R.string.ms_key), key, singleLine = false) { key = it }
                Spacer(Modifier.height(10.dp))
                Label(stringResource(R.string.ms_generation))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AwgGeneration.entries.forEach { g -> MsChip(g.label, TextSecondary, selected = gen == g) { gen = g } }
                }
                Text(
                    stringResource(when (gen) {
                        AwgGeneration.V1_0 -> R.string.ms_gen_1_0
                        AwgGeneration.V1_5 -> R.string.ms_gen_1_5
                        AwgGeneration.V2_0 -> R.string.ms_gen_2_0
                        AwgGeneration.V3 -> R.string.ms_gen_3
                    }), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp),
                )
                MsField(stringResource(R.string.ms_awg_port), awgPort) { awgPort = it.filter(Char::isDigit).take(5) }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.clickableNoRipple { openFw = !openFw }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(openFw, { openFw = it })
                    Text(stringResource(R.string.ms_open_fw), color = TextSecondary, fontSize = 13.sp)
                }
                val port = sshPort.toIntOrNull() ?: 0
                val aport = awgPort.toIntOrNull() ?: 0
                val ready = !s.busy && MyServersManager.validHost(host) && port in 1..65535 && aport in 1024..65535 && (password.isNotEmpty() || key.isNotBlank())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ready) MsChip(stringResource(R.string.ms_connect), AccentCyan) {
                        m.begin(ServerForm(name.ifBlank { defaultName }, SshTarget(host, port, user.ifBlank { "root" },
                            password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() }), gen, aport, openFw))
                    }
                    MsChip(stringResource(R.string.ms_close), TextSecondary) { onClose() }
                }
            }
            Stage.CONFIRM_FINGERPRINT -> {
                Text(stringResource(R.string.ms_fp_text), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                SelectionContainer { Text(s.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextPrimary) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!s.busy) MsChip(stringResource(R.string.ms_trust), AccentCyan) { m.confirmFingerprint() }
                    MsChip(stringResource(R.string.ms_cancel), TextSecondary) { onClose() }
                }
            }
            Stage.READY -> {
                val pre = s.preflight
                if (pre != null) {
                    Text(stringResource(R.string.ms_server_info, pre.osName.ifBlank { "Linux" }, pre.arch, yn(pre.root), yn(pre.systemd), yn(pre.portFree), yn(pre.internet), pre.firewall),
                        color = TextSecondary, fontSize = 13.sp)
                    pre.issues.forEach { Text("• $it", color = Danger, fontSize = 13.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ms_plan), color = TextSecondary, fontSize = 13.sp)
                SelectionContainer {
                    Column {
                        s.plan.forEachIndexed { i, st ->
                            Text("${i + 1}. ${st.title}", fontSize = 12.sp, color = TextPrimary)
                            val detail = st.command ?: st.upload?.let { u -> stringResource(R.string.ms_upload_to, u.path) }
                            detail?.let { Text("   $it", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextMuted) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pre?.ok == true) MsChip(stringResource(R.string.ms_install), AccentCyan) { m.install() }
                    MsChip(stringResource(R.string.ms_cancel), TextSecondary) { onClose() }
                }
            }
            Stage.RUNNING -> {
                LogBox(s.log)
                Spacer(Modifier.height(8.dp))
                MsChip(stringResource(R.string.ms_stop), Danger) { m.stop() }
            }
            Stage.DONE -> {
                Text(s.progress, color = TextSecondary, fontSize = 13.sp)
                LogBox(s.log)
                Spacer(Modifier.height(8.dp))
                MsChip(stringResource(R.string.ms_done), AccentCyan) { onClose() }
            }
        }
        if (s.stage == Stage.FAILED && s.log.isNotEmpty()) { Spacer(Modifier.height(8.dp)); LogBox(s.log) }
    }
}

@Composable
private fun LogBox(lines: List<String>) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) { scroll.scrollTo(scroll.maxValue) }
    Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.25f)).padding(8.dp)) {
        SelectionContainer {
            Column(Modifier.verticalScroll(scroll)) {
                lines.takeLast(200).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextSecondary) }
            }
        }
    }
}

@Composable
private fun LoginDialog(n: NeedLogin, onCancel: () -> Unit, onOk: (SshTarget) -> Unit) {
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val srv = n.server
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Surface,
        title = { Text(n.title, color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ms_login_for, srv.sshUser, srv.host, srv.sshPort), fontSize = 12.sp, color = TextMuted)
                MsField(stringResource(R.string.ms_password), password, secret = true) { password = it }
                MsField(stringResource(R.string.ms_or_key), key, singleLine = false) { key = it }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() || key.isNotBlank(), onClick = {
                onOk(SshTarget(srv.host, srv.sshPort, srv.sshUser, password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() }))
            }) { Text(stringResource(R.string.ms_run), color = AccentCyan) }
        },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
    )
}

@Composable
private fun MsChip(text: String, color: Color, selected: Boolean = false, onClick: () -> Unit) {
    val c = if (selected) AccentCyan else color
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(c.copy(alpha = if (selected) 0.18f else 0.10f))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickableNoRipple(onClick)
            .defaultMinSize(minHeight = 40.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = c, fontSize = 12.sp, maxLines = 1) }
}

@Composable
private fun MsField(label: String, value: String, secret: Boolean = false, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = singleLine, maxLines = if (singleLine) 1 else 4,
        label = { Text(label, fontSize = 12.sp) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}
