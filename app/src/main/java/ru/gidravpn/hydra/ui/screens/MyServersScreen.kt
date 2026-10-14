package ru.gidravpn.hydra.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.asImageBitmap
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
import ru.gidravpn.hydra.servers.FriendlyText
import ru.gidravpn.hydra.servers.InstallStage
import ru.gidravpn.hydra.servers.InstallUi
import ru.gidravpn.hydra.servers.MyServer
import ru.gidravpn.hydra.servers.MyServersManager
import ru.gidravpn.hydra.servers.MyServersUi
import ru.gidravpn.hydra.servers.OfxNodeSpec
import ru.gidravpn.hydra.servers.ServerForm
import ru.gidravpn.hydra.servers.ServerKind
import ru.gidravpn.hydra.servers.ServerStatus
import ru.gidravpn.hydra.servers.ServiceForm
import ru.gidravpn.hydra.servers.SshTarget
import ru.gidravpn.hydra.servers.Stage
import ru.gidravpn.hydra.servers.XuiPanel
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.MyServersViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.components.humanBytes
import ru.gidravpn.hydra.ui.theme.*

/** Действие, которое надо подтвердить (удаление, очистка, перезагрузка). */
private class Confirm(val title: String, val text: String, val button: String, val run: () -> Unit)

/**
 * «Мои серверы» (0.8.0; сервер-сначала с 0.8.0-rc4): добавить сервер (вход можно запомнить, зашифрованно) → на карточке
 * поставить AmneziaWG или узел OpenFlux → устройства, проверка, перезапуск, перезагрузка, удаление службы, полная очистка.
 * Логика общая с ПК — [MyServersManager].
 */
@Composable
fun MyServersScreen(vm: MainViewModel, onBack: () -> Unit, mvm: MyServersViewModel = viewModel()) {
    val m = mvm.manager
    val s by m.ui.collectAsState()
    val servers by mvm.servers.collectAsState()
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var wizard by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var addClientFor by remember { mutableStateOf<MyServer?>(null) }
    var loginFor by remember { mutableStateOf<MyServer?>(null) }
    var connectPanel by remember { mutableStateOf(false) }
    var addUserFor by remember { mutableStateOf<MyServer?>(null) }

    val addedFmt = stringResource(R.string.ms_added, "%s")
    DisposableEffect(Unit) {
        mvm.importConf = { name, conf ->
            vm.importAuto(listOf(conf), name)
            android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, addedFmt.format(name), Toast.LENGTH_SHORT).show() }
        }
        onDispose { mvm.importConf = { _, _ -> } }
    }
    androidx.activity.compose.BackHandler { onBack() }

    // при открытии — проверить серверы с сохранённым входом (по очереди: операции идут одна за другой)
    LaunchedEffect(Unit) {
        servers.filter { it.id in s.saved && it.id !in s.status }.forEach { srv ->
            while (m.ui.value.busy) kotlinx.coroutines.delay(300)
            m.refresh(srv.id)
            kotlinx.coroutines.delay(300)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ms_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                MsChip(stringResource(R.string.ms_back), TextSecondary) { onBack() }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.ms_intro), color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (!wizard && s.stage == Stage.IDLE) MsRow {
                MsChip(stringResource(R.string.ms_add), AccentCyan) { if (!s.busy) { m.reset(); wizard = true } }
                MsChip(stringResource(R.string.ms_connect_panel), AccentViolet) { if (!s.busy) { m.reset(); connectPanel = true } }
            }
            // ход и ошибка подключения готовой панели (своей карточки у неё ещё нет)
            if (!wizard && s.stage == Stage.IDLE && s.opServer == null) {
                if (s.busy) { Spacer(Modifier.height(8.dp)); Busy(s.progress) }
                s.error?.let { Spacer(Modifier.height(8.dp)); ErrorBox(it) }
                if (!s.busy && s.progress == "Панель подключена") Text(stringResource(R.string.ms_panel_connected), color = Success, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (wizard || s.stage != Stage.IDLE) item { AddServerCard(m, s) { wizard = false; m.reset() } }
        if (servers.isEmpty() && !wizard && s.stage == Stage.IDLE) item { Text(stringResource(R.string.ms_empty), color = TextMuted, fontSize = 13.sp) }
        servers.forEach { srv ->
            item(key = srv.id) {
                if (srv.broken) BrokenCard(srv) { m.forget(srv.id) }
                else if (srv.panelOnly) PanelOnlyCard(srv, m, s, onAddUser = { addUserFor = srv }, onConfirm = { confirm = it })
                else ServerCard(srv, m, s, onAddClient = { addClientFor = srv }, onAddUser = { addUserFor = srv }, onLogin = { loginFor = srv }, onConfirm = { confirm = it })
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    addClientFor?.let { srv ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addClientFor = null }, containerColor = Surface,
            title = { Text(stringResource(R.string.ms_new_device), color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ms_new_device_hint), color = TextSecondary, fontSize = 13.sp)
                    MsField(stringResource(R.string.ms_latin_name), name) { name = it.filter { ch -> ch.isLetterOrDigit() || ch in "-_" }.take(24) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && srv.clients.none { it.name == name }, onClick = { addClientFor = null; m.addClient(srv.id, name) }) {
                    Text(stringResource(R.string.ms_add_short), color = AccentCyan)
                }
            },
            dismissButton = { TextButton({ addClientFor = null }) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
        )
    }
    if (connectPanel) ConnectPanelDialog(onClose = { connectPanel = false }) { name, address, token ->
        connectPanel = false; m.connectPanel(name, address, token)
    }
    addUserFor?.let { srv ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addUserFor = null }, containerColor = Surface,
            title = { Text(stringResource(R.string.ms_new_user), color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ms_new_user_hint), color = TextSecondary, fontSize = 13.sp)
                    MsField(stringResource(R.string.ms_latin_name), name) { name = it.filter { ch -> ch.isLetterOrDigit() || ch in "-_" }.take(24) }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { addUserFor = null; m.addPanelClient(srv.id, name) }) { Text(stringResource(R.string.ms_create), color = AccentCyan) } },
            dismissButton = { TextButton({ addUserFor = null }) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
        )
    }
    confirm?.let { k ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = Surface,
            title = { Text(k.title, color = TextPrimary) },
            text = { Text(k.text, color = TextSecondary, fontSize = 13.sp) },
            confirmButton = { TextButton({ confirm = null; k.run() }) { Text(k.button, color = Danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
        )
    }
    loginFor?.let { srv ->
        LoginDialog(stringResource(R.string.ms_login_title, srv.name), srv, null, rememberDefault = srv.id in s.saved, editUser = true,
            onCancel = { loginFor = null }) { t, remember -> loginFor = null; m.changeLogin(srv.id, t, remember) }
    }
    s.login?.let { req ->
        servers.firstOrNull { it.id == req.serverId }?.let { srv ->
            LoginDialog(req.title.removeSuffix("…"), srv, req.error, rememberDefault = true, editUser = false, onCancel = { m.cancelLogin() }) { t, remember -> m.provideLogin(t, remember) }
        }
    }
    s.shown?.let { (name, text) ->
        val copied = stringResource(R.string.ms_copied)
        val isConf = "[Interface]" in text
        val qr = remember(text) { ru.gidravpn.hydra.data.subscription.QrEncoder.encode(text, 640) }
        AlertDialog(
            onDismissRequest = { m.hideShown() }, containerColor = Surface,
            title = { Text(stringResource(R.string.ms_access_title, name), color = TextPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (isConf) R.string.ms_access_conf else R.string.ms_access_link), fontSize = 13.sp, color = TextSecondary)
                    Text(stringResource(R.string.ms_access_secret), fontSize = 12.sp, color = Danger)
                    qr?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.White)) }
                    SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary) }
                }
            },
            confirmButton = {
                Row {
                    TextButton({ clip.setText(AnnotatedString(text)); Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show() }) { Text(stringResource(R.string.ms_copy), color = AccentCyan) }
                    TextButton({ vm.importAuto(listOf(text), name); m.hideShown() }) { Text(stringResource(R.string.ms_add_to_servers), color = AccentCyan) }
                }
            },
            dismissButton = { TextButton({ m.hideShown() }) { Text(stringResource(R.string.ms_close), color = TextMuted) } },
        )
    }
}

// ------------------------------------------------------------------------------------------------ Добавление сервера

@Composable
private fun AddServerCard(m: MyServersManager, s: MyServersUi, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var advanced by remember { mutableStateOf(false) }

    Card {
        Text(stringResource(R.string.ms_wizard_title), color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (s.busy && s.opServer == null) { Busy(s.progress); Spacer(Modifier.height(6.dp)) }
        when (s.stage) {
            Stage.IDLE, Stage.FAILED -> {
                s.error?.takeIf { s.opServer == null }?.let { ErrorBox(it); Spacer(Modifier.height(6.dp)) }
                Text(stringResource(R.string.ms_step1_hint), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                val hostBad = host.isNotBlank() && !MyServersManager.validHost(host)
                MsField(stringResource(R.string.ms_host), host, hint = if (hostBad) stringResource(R.string.ms_host_bad) else stringResource(R.string.ms_host_hint)) { host = it.trim() }
                Spacer(Modifier.height(8.dp))
                MsField(stringResource(R.string.ms_password), password, secret = true) { password = it }
                Spacer(Modifier.height(8.dp))
                MsField(stringResource(R.string.ms_name), name) { name = it.take(40) }
                Spacer(Modifier.height(6.dp))
                MsChip(stringResource(if (advanced) R.string.ms_advanced_hide else R.string.ms_advanced_show), TextSecondary) { advanced = !advanced }
                if (advanced) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { MsField(stringResource(R.string.ms_ssh_port), sshPort) { sshPort = it.filter(Char::isDigit).take(5) } }
                        Box(Modifier.weight(1.4f)) { MsField(stringResource(R.string.ms_user), user) { user = it.trim() } }
                    }
                    Spacer(Modifier.height(8.dp))
                    MsField(stringResource(R.string.ms_key), key, singleLine = false) { key = it }
                    if (key.isNotBlank()) { Spacer(Modifier.height(8.dp)); MsField(stringResource(R.string.ms_key_pass), passphrase, secret = true) { passphrase = it } }
                }
                Row(Modifier.clickableNoRipple { remember = !remember }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(remember, { remember = it })
                    Text(stringResource(R.string.ms_remember), color = TextSecondary, fontSize = 13.sp)
                }
                val port = sshPort.toIntOrNull() ?: 0
                val ready = !s.busy && MyServersManager.validHost(host) && port in 1..65535 && (password.isNotEmpty() || key.isNotBlank())
                Spacer(Modifier.height(8.dp))
                MsRow {
                    if (ready) MsChip(stringResource(R.string.ms_connect), AccentCyan) {
                        m.begin(ServerForm(name.ifBlank { host }, SshTarget(host, port, user.ifBlank { "root" },
                            password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() },
                            passphrase.takeIf { key.isNotBlank() && it.isNotEmpty() }), remember))
                    }
                    MsChip(stringResource(R.string.ms_close), TextSecondary) { onClose() }
                }
            }
            Stage.CONFIRM_FINGERPRINT -> {
                Text(stringResource(R.string.ms_fp_text), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                SelectionContainer { Text(s.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextPrimary) }
                Spacer(Modifier.height(10.dp))
                MsRow {
                    if (!s.busy) MsChip(stringResource(R.string.ms_trust), AccentCyan) { m.confirmFingerprint() }
                    MsChip(stringResource(R.string.ms_cancel), TextSecondary) { onClose() }
                }
            }
            Stage.DONE -> {
                Text(s.progress, color = Success, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.ms_added_next), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                MsChip(stringResource(R.string.ms_done), AccentCyan) { onClose() }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Карточка сервера

@Composable
private fun ServerCard(srv: MyServer, m: MyServersManager, s: MyServersUi, onAddClient: () -> Unit, onAddUser: () -> Unit, onLogin: () -> Unit, onConfirm: (Confirm) -> Unit) {
    val ctx = LocalContext.current
    val st = s.status[srv.id]
    val mine = s.opServer == srv.id
    val busy = s.busy
    var installKind by remember { mutableStateOf<ServerKind?>(null) }
    var more by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(srv.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            StatusChip(st)
        }
        Text("${srv.sshUser}@${srv.host}:${srv.sshPort}" + listOf(st?.osName ?: srv.osName, st?.arch ?: srv.arch).filter { it.isNotBlank() }.joinToString(" · ", " · "),
            color = TextMuted, fontSize = 12.sp)
        st?.takeIf { it.error == null && it.uptimeSec > 0 }?.let {
            Text(stringResource(R.string.ms_status_line, uptime(it.uptimeSec), it.load, it.mem, it.disk), color = TextMuted, fontSize = 11.sp)
        }
        Text(stringResource(if (srv.id in s.saved) R.string.ms_login_saved else R.string.ms_login_not_saved), color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        MsRow {
            MsChip(stringResource(R.string.ms_check), AccentCyan) { if (!busy) m.refresh(srv.id) }
            MsChip(stringResource(R.string.ms_login_btn), TextSecondary) { if (!busy) onLogin() }
            MsChip(stringResource(R.string.ms_reboot), TextSecondary) {
                if (!busy) onConfirm(Confirm(ctx.getString(R.string.ms_reboot_q, srv.name), ctx.getString(R.string.ms_reboot_msg), ctx.getString(R.string.ms_reboot)) { m.reboot(srv.id) })
            }
        }

        Divider()
        AwgSection(srv, st, m, busy, onAddClient, onConfirm) { installKind = ServerKind.AMNEZIAWG }
        Divider()
        OfxSection(srv, st, m, busy, onConfirm) { installKind = ServerKind.OPENFLUX }
        Divider()
        PanelSection(srv, st, m, s, onAddUser, onConfirm) { k -> installKind = k }

        val inst = s.install?.takeIf { it.serverId == srv.id }
        if (installKind != null && inst == null) { Spacer(Modifier.height(8.dp)); InstallForm(installKind!!, busy, onCancel = { installKind = null }) { f -> m.prepareInstall(srv.id, f) } }
        if (inst != null) { Spacer(Modifier.height(8.dp)); InstallPanel(inst, s, m) { installKind = null; m.closeInstall() } }

        if (mine && busy && inst == null) { Spacer(Modifier.height(8.dp)); Busy(s.progress) }
        if (mine && s.log.isNotEmpty() && inst == null) { Spacer(Modifier.height(8.dp)); LogBox(s.log) }
        if (mine && s.error != null && inst == null) { Spacer(Modifier.height(8.dp)); ErrorBox(s.error!!) }

        Divider()
        MsChip(stringResource(if (more) R.string.ms_hide else R.string.ms_more), TextSecondary) { more = !more }
        if (more) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.ms_fingerprint, srv.fingerprint), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            MsRow {
                MsChip(stringResource(R.string.ms_rename), TextSecondary) { if (!busy) rename = true }
                MsChip(stringResource(R.string.ms_wipe), Danger) {
                    if (!busy) onConfirm(Confirm(ctx.getString(R.string.ms_wipe_q, srv.name), ctx.getString(R.string.ms_wipe_msg, srv.host), ctx.getString(R.string.ms_wipe_btn)) { m.wipe(srv.id) })
                }
                MsChip(stringResource(R.string.ms_forget), TextSecondary) {
                    if (!busy) onConfirm(Confirm(ctx.getString(R.string.ms_forget_q, srv.name), ctx.getString(R.string.ms_forget_msg), ctx.getString(R.string.ms_remove)) { m.forget(srv.id) })
                }
            }
        }
    }
    if (rename) {
        var n by remember { mutableStateOf(srv.name) }
        AlertDialog(
            onDismissRequest = { rename = false }, containerColor = Surface,
            title = { Text(stringResource(R.string.ms_rename), color = TextPrimary) },
            text = { MsField(stringResource(R.string.ms_name), n) { n = it.take(40) } },
            confirmButton = { TextButton(enabled = n.isNotBlank(), onClick = { rename = false; m.rename(srv.id, n) }) { Text(stringResource(R.string.ms_save), color = AccentCyan) } },
            dismissButton = { TextButton({ rename = false }) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
        )
    }
}

@Composable
private fun AwgSection(srv: MyServer, st: ServerStatus?, m: MyServersManager, busy: Boolean, onAddClient: () -> Unit, onConfirm: (Confirm) -> Unit, onInstall: () -> Unit) {
    val ctx = LocalContext.current
    val awg = srv.awg
    Text("AmneziaWG", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    if (awg == null) {
        Text(stringResource(R.string.ms_awg_none), color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        MsChip(stringResource(R.string.ms_awg_install), AccentCyan) { if (!busy) onInstall() }
        return
    }
    val state = st?.takeIf { it.error == null }?.let { if (it.awgActive) stringResource(R.string.ms_state_running) else stringResource(R.string.ms_state_service, it.awgState.ifBlank { "—" }) }
        ?: stringResource(R.string.ms_state_unknown)
    Text(stringResource(R.string.ms_awg_line, awg.generation.label, awg.port, state), color = if (st?.awgActive == true) Success else TextMuted, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    srv.clients.forEach { cl ->
        val peer = st?.awgPeers?.firstOrNull { it.publicKey == cl.publicKey }
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text("${cl.name} · ${cl.address.substringBefore('/')}", color = TextSecondary, fontSize = 13.sp)
            peer?.let { Text(peerLine(it.latestHandshake, it.rx, it.tx), color = TextMuted, fontSize = 11.sp) }
            if (cl.privateKey.isEmpty()) Text(stringResource(R.string.ms_client_foreign), color = TextMuted, fontSize = 11.sp)
            MsRow {
                if (cl.privateKey.isNotEmpty()) MsChip(stringResource(R.string.ms_show_conf), AccentViolet) { m.clientConf(srv.id, cl.name)?.let { conf -> m.showConf(cl.name, conf) } }
                if (srv.clients.size > 1) MsChip(stringResource(R.string.ms_delete), Danger) {
                    if (!busy) onConfirm(Confirm(ctx.getString(R.string.ms_client_del_q, cl.name), ctx.getString(R.string.ms_client_del_msg), ctx.getString(R.string.ms_delete)) { m.removeClient(srv.id, cl.name) })
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    MsRow {
        MsChip(stringResource(R.string.ms_add_device), AccentCyan) { if (!busy) onAddClient() }
        MsChip(stringResource(R.string.ms_restart), TextSecondary) { if (!busy) m.restartService(srv.id, ServerKind.AMNEZIAWG) }
        MsChip(stringResource(R.string.ms_awg_remove), Danger) {
            if (!busy) onConfirm(Confirm(ctx.getString(R.string.ms_awg_remove_q, srv.name), ctx.getString(R.string.ms_awg_remove_msg, srv.host, awg.port), ctx.getString(R.string.ms_delete)) {
                m.uninstallService(srv.id, ServerKind.AMNEZIAWG)
            })
        }
    }
}

@Composable
private fun OfxSection(srv: MyServer, st: ServerStatus?, m: MyServersManager, busy: Boolean, onConfirm: (Confirm) -> Unit, onInstall: () -> Unit) {
    val ctx = LocalContext.current
    val ofx = srv.ofx
    Text(stringResource(R.string.ms_kind_ofx), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    if (ofx == null) {
        Text(stringResource(R.string.ms_ofx_none), color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        MsChip(stringResource(R.string.ms_ofx_install), AccentViolet) { if (!busy) onInstall() }
        return
    }
    val state = st?.takeIf { it.error == null }?.ofxChannels?.get(ofx.channel)
    Text(stringResource(R.string.ms_ofx_line, ofx.channel, ofx.port, state ?: "—"), color = if (state == "active") Success else TextMuted, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    MsRow {
        MsChip(stringResource(R.string.ms_ofx_link), AccentViolet) { m.ofxLink(srv.id)?.let { m.showConf(srv.name, it) } }
        MsChip(stringResource(R.string.ms_restart), TextSecondary) { if (!busy) m.restartService(srv.id, ServerKind.OPENFLUX) }
        MsChip(stringResource(R.string.ms_ofx_remove), Danger) {
            if (!busy) onConfirm(Confirm(ctx.getString(R.string.ms_ofx_remove_q, srv.name), ctx.getString(R.string.ms_ofx_remove_msg, ofx.channel), ctx.getString(R.string.ms_delete)) {
                m.uninstallService(srv.id, ServerKind.OPENFLUX)
            })
        }
    }
}

/** Панель 3x-ui на своём сервере (0.8.1): сайт, пользователи, перезапуск, удаление. */
@Composable
private fun PanelSection(srv: MyServer, st: ServerStatus?, m: MyServersManager, s: MyServersUi, onAddUser: () -> Unit, onConfirm: (Confirm) -> Unit, onInstall: (ServerKind) -> Unit) {
    val ctx = LocalContext.current
    val p = srv.panel
    val busy = s.busy
    Text(p?.let { stringResource(R.string.ms_panel_kind, it.kind.title) } ?: stringResource(R.string.ms_panel_title), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    if (p == null) {
        Text(stringResource(R.string.ms_panel_none), color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        val foreign = (st?.xuiInstalled == true && !st.xuiByHydra && !st.xuiAlirezaByHydra) || (st?.suiInstalled == true && !st.suiByHydra)
        if (foreign) Text(stringResource(R.string.ms_panel_foreign), color = TextMuted, fontSize = 12.sp)
        MsRow {
            if (st?.xuiInstalled != true) MsChip(stringResource(R.string.ms_panel_install), AccentViolet) { if (!busy) onInstall(ServerKind.PANEL_3XUI) }
            if (st?.suiInstalled != true) MsChip(stringResource(R.string.ms_panel_install_other, "s-ui"), TextSecondary) { if (!busy) onInstall(ServerKind.PANEL_SUI) }
            if (st?.xuiInstalled != true) MsChip(stringResource(R.string.ms_panel_install_other, "x-ui"), TextSecondary) { if (!busy) onInstall(ServerKind.PANEL_XUI) }
        }
        return
    }
    val kind = ServerKind.ofPanel(p.kind)
    val state = st?.takeIf { it.error == null }?.xuiState?.takeIf { it.isNotBlank() }
    Text(stringResource(R.string.ms_panel_line, p.version.ifBlank { "—" }, state ?: "—"), color = if (state == "active") Success else TextMuted, fontSize = 12.sp)
    PanelBody(srv, m, s, onAddUser)
    Spacer(Modifier.height(6.dp))
    MsRow {
        MsChip(stringResource(R.string.ms_restart), TextSecondary) { if (!busy) m.restartService(srv.id, kind) }
        MsChip(stringResource(R.string.ms_panel_remove), Danger) {
            if (!busy) onConfirm(Confirm(ctx.getString(R.string.ms_panel_remove_q, srv.name), ctx.getString(R.string.ms_panel_remove_msg), ctx.getString(R.string.ms_delete)) {
                m.uninstallService(srv.id, kind)
            })
        }
    }
}

/** Общее для панели на своём сервере и подключённой готовой: адрес, вход на сайт, пользователи. */
@Composable
private fun PanelBody(srv: MyServer, m: MyServersManager, s: MyServersUi, onAddUser: () -> Unit) {
    val p = srv.panel ?: return
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val busy = s.busy
    val pwMsg = stringResource(R.string.ms_password_copied)
    val open = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(p.baseUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }; Unit }
    SelectionContainer {
        Text(stringResource(R.string.ms_panel_address, p.baseUrl) + if (p.username.isNotBlank()) "\n" + stringResource(R.string.ms_panel_login, p.username) else "",
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary)
    }
    if (!XuiPanel.isHttps(p.baseUrl)) Text(stringResource(R.string.ms_panel_http), color = TextMuted, fontSize = 11.sp)
    Spacer(Modifier.height(6.dp))
    MsRow {
        if (p.password.isNotBlank()) {
            MsChip(stringResource(R.string.ms_open_panel), AccentCyan) { copySecret(clip, p.password); Toast.makeText(ctx, pwMsg, Toast.LENGTH_LONG).show(); open() }
            MsChip(stringResource(R.string.ms_copy_password), TextSecondary) { copySecret(clip, p.password); Toast.makeText(ctx, pwMsg, Toast.LENGTH_SHORT).show() }
        } else MsChip(stringResource(R.string.ms_open_panel), AccentCyan) { open() }
        // пользователи через API — только у 3x-ui (токен); у s-ui и x-ui — на сайте панели
        if (p.token.isNotBlank()) {
            MsChip(stringResource(R.string.ms_panel_users), AccentViolet) { if (!busy) m.loadPanelClients(srv.id) }
            MsChip(stringResource(R.string.ms_panel_add_user), AccentCyan) { if (!busy) onAddUser() }
        }
    }
    if (p.token.isBlank()) Text(stringResource(R.string.ms_panel_site_only), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    s.panelClients[srv.id]?.let { emails ->
        if (emails.isEmpty()) Text(stringResource(R.string.ms_panel_no_users), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        emails.forEach { email ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text("• $email", color = TextSecondary, fontSize = 13.sp)
                MsRow {
                    MsChip(stringResource(R.string.ms_panel_link), AccentViolet) { if (!busy) m.showPanelLinks(srv.id, email) }
                    MsChip(stringResource(R.string.ms_delete), Danger) { if (!busy) m.removePanelClient(srv.id, email) }
                }
            }
        }
    }
}

/** Запись без отпечатка и без панели (панель 0.8.1-a, пересохранённая 0.8.0-rc4): подключить нечего — только убрать. */
@Composable
private fun BrokenCard(srv: MyServer, onForget: () -> Unit) = Card {
    Text(srv.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text(stringResource(R.string.ms_broken), color = TextMuted, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    MsChip(stringResource(R.string.ms_forget), TextSecondary) { onForget() }
}

/** Готовая панель, подключённая по адресу и токену (без SSH): только API панели. */
@Composable
private fun PanelOnlyCard(srv: MyServer, m: MyServersManager, s: MyServersUi, onAddUser: () -> Unit, onConfirm: (Confirm) -> Unit) {
    val ctx = LocalContext.current
    val st = s.status[srv.id]
    val mine = s.opServer == srv.id
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(srv.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            StatusChip(st)
        }
        Text(stringResource(R.string.ms_panel_only), color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        PanelBody(srv, m, s, onAddUser)
        if (mine && s.busy) { Spacer(Modifier.height(8.dp)); Busy(s.progress.ifBlank { stringResource(R.string.ms_checking) }) }
        if (mine && s.error != null) { Spacer(Modifier.height(8.dp)); ErrorBox(s.error!!) }
        Divider()
        MsRow {
            MsChip(stringResource(R.string.ms_check), AccentCyan) { if (!s.busy) m.refresh(srv.id) }
            MsChip(stringResource(R.string.ms_forget), TextSecondary) {
                if (!s.busy) onConfirm(Confirm(ctx.getString(R.string.ms_forget_q, srv.name), ctx.getString(R.string.ms_panel_forget_msg), ctx.getString(R.string.ms_remove)) { m.forget(srv.id) })
            }
        }
    }
}

/** «У меня уже есть панель» (0.8.1-a): адрес и токен доступа 3x-ui — без установки и без входа на сервер. */
@Composable
private fun ConnectPanelDialog(onClose: () -> Unit, onOk: (name: String, address: String, token: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val plain = address.isNotBlank() && !XuiPanel.isHttps(XuiPanel.normalizeUrl(address).orEmpty())
    AlertDialog(
        onDismissRequest = onClose, containerColor = Surface,
        title = { Text(stringResource(R.string.ms_cp_title), color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ms_cp_text), color = TextSecondary, fontSize = 13.sp)
                MsField(stringResource(R.string.ms_cp_name), name) { name = it }
                MsField(stringResource(R.string.ms_cp_address), address, hint = "http://203.0.113.5:2053/path") { address = it }
                MsField(stringResource(R.string.ms_cp_token), token, secret = true, hint = stringResource(R.string.ms_cp_token_hint)) { token = it }
                if (plain) Text(stringResource(R.string.ms_cp_plain_warn), color = Danger, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(enabled = address.isNotBlank() && token.isNotBlank(), onClick = { onOk(name, address, token) }) { Text(stringResource(R.string.ms_cp_connect), color = AccentCyan) } },
        dismissButton = { TextButton(onClose) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
    )
}

/** Пароль панели в буфер на 30 секунд: потом очищается, если там всё ещё он. */
private fun copySecret(clip: androidx.compose.ui.platform.ClipboardManager, text: String) {
    clip.setText(AnnotatedString(text))
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        if (clip.getText()?.text == text) clip.setText(AnnotatedString(""))
    }, 30_000)
}

@Composable
private fun InstallForm(kind: ServerKind, busy: Boolean, onCancel: () -> Unit, onNext: (ServiceForm) -> Unit) {
    var gen by remember { mutableStateOf(AwgGeneration.V2_0) }
    var awgPort by remember { mutableStateOf("51820") }
    var openFw by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf("hydra") }
    var yandex by remember { mutableStateOf("") }
    var mailru by remember { mutableStateOf("") }
    var accessPort by remember { mutableStateOf("443") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Border, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(when (kind) {
                ServerKind.AMNEZIAWG -> stringResource(R.string.ms_install_awg_title)
                ServerKind.OPENFLUX -> stringResource(R.string.ms_install_ofx_title)
                else -> stringResource(R.string.ms_install_panel_title, kind.panel!!.title)
            }, color = TextPrimary, fontWeight = FontWeight.SemiBold)
        if (kind == ServerKind.PANEL_SUI || kind == ServerKind.PANEL_XUI)
            Text(stringResource(R.string.ms_panel_other_hint, kind.panel!!.title), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        Spacer(Modifier.height(6.dp))
        if (kind == ServerKind.PANEL_3XUI) {
            Text(stringResource(R.string.ms_xui_hint), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            MsField(stringResource(R.string.ms_xui_port), accessPort) { accessPort = it.filter(Char::isDigit).take(5) }
        } else if (kind == ServerKind.AMNEZIAWG) {
            Text(stringResource(R.string.ms_generation), color = TextSecondary, fontSize = 13.sp)
            MsRow {
                AwgGeneration.entries.forEach { g ->
                    MsChip(if (g == AwgGeneration.V2_0) stringResource(R.string.ms_awg_recommended, g.label) else g.label, TextSecondary, selected = gen == g) { gen = g }
                }
            }
            Text(stringResource(when (gen) {
                AwgGeneration.V1_0 -> R.string.ms_gen_1_0
                AwgGeneration.V1_5 -> R.string.ms_gen_1_5
                AwgGeneration.V2_0 -> R.string.ms_gen_2_0
                AwgGeneration.V3 -> R.string.ms_gen_3
            }), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            MsField(stringResource(R.string.ms_awg_port), awgPort) { awgPort = it.filter(Char::isDigit).take(5) }
        } else if (kind == ServerKind.OPENFLUX) {
            Text(stringResource(R.string.ms_ofx_hint), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            MsField(stringResource(R.string.ms_ofx_channel), channel) { channel = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(31) }
            Spacer(Modifier.height(8.dp))
            MsField(stringResource(R.string.ms_ofx_yandex), yandex) { yandex = it.trim() }
            Spacer(Modifier.height(8.dp))
            MsField(stringResource(R.string.ms_ofx_mailru), mailru) { mailru = it.trim() }
        }
        Row(Modifier.clickableNoRipple { openFw = !openFw }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(openFw, { openFw = it })
            Text(stringResource(R.string.ms_open_fw), color = TextSecondary, fontSize = 13.sp)
        }
        val aport = awgPort.toIntOrNull() ?: 0
        val ok = when (kind) {
            ServerKind.AMNEZIAWG -> aport in 1024..65535
            ServerKind.OPENFLUX -> OfxNodeSpec.validChannel(channel)
            ServerKind.PANEL_3XUI -> (accessPort.toIntOrNull() ?: 0) in 1..65535
            ServerKind.PANEL_SUI, ServerKind.PANEL_XUI -> true
        }
        Spacer(Modifier.height(8.dp))
        MsRow {
            if (ok && !busy) MsChip(stringResource(R.string.ms_check_plan), AccentCyan) { onNext(ServiceForm(kind, gen, aport, openFw, channel, yandex, mailru, accessPort = accessPort.toIntOrNull() ?: 443)) }
            MsChip(stringResource(R.string.ms_cancel), TextSecondary) { onCancel() }
        }
    }
}

@Composable
private fun InstallPanel(i: InstallUi, s: MyServersUi, m: MyServersManager, onClose: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    val en = !ruLocale()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Border, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(when (i.form.kind) {
                ServerKind.AMNEZIAWG -> stringResource(R.string.ms_installing_awg, i.form.generation.label)
                ServerKind.OPENFLUX -> stringResource(R.string.ms_installing_ofx, i.form.channel)
                else -> stringResource(R.string.ms_install_panel_title, i.form.kind.panel!!.title)
            },
            color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        when (i.stage) {
            InstallStage.CHECKING -> Busy(s.progress.ifBlank { stringResource(R.string.ms_checking) })
            InstallStage.READY -> {
                i.preflight?.let { pre ->
                    FriendlyText.checklist(pre, en).forEach { item ->
                        Text((if (item.ok) "✓ " else "✗ ") + item.title, color = if (item.ok) Success else Danger, fontSize = 13.sp)
                        if (!item.ok) Text(item.help, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ms_plan_title), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FriendlyText.planSentences(i.form.kind, i.form.openFirewall, en).forEachIndexed { n, t -> Text("${n + 1}. $t", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) }
                Spacer(Modifier.height(6.dp))
                MsChip(stringResource(if (details) R.string.ms_details_hide else R.string.ms_details_show), TextSecondary) { details = !details }
                if (details) SelectionContainer {
                    Column(Modifier.padding(top = 6.dp)) {
                        i.plan.forEachIndexed { n, st ->
                            Text("${n + 1}. ${st.title}", fontSize = 12.sp, color = TextPrimary)
                            (st.command ?: st.upload?.let { u -> stringResource(R.string.ms_upload_to, u.path) })?.let { Text("   $it", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextMuted) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                MsRow {
                    if (i.preflight?.ok == true && !s.busy) MsChip(stringResource(R.string.ms_install), AccentCyan) { m.runInstall() }
                    MsChip(stringResource(R.string.ms_cancel), TextSecondary) { onClose() }
                }
            }
            InstallStage.RUNNING -> {
                Busy(s.progress)
                Spacer(Modifier.height(6.dp))
                LogBox(s.log)
                Spacer(Modifier.height(6.dp))
                MsChip(stringResource(R.string.ms_stop), Danger) { m.stop() }
            }
            InstallStage.DONE -> {
                Text(stringResource(if (i.form.kind == ServerKind.PANEL_SUI || i.form.kind == ServerKind.PANEL_XUI) R.string.ms_install_done_panel else R.string.ms_install_done), color = Success, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp)); LogBox(s.log); Spacer(Modifier.height(6.dp))
                MsChip(stringResource(R.string.ms_close), AccentCyan) { onClose() }
            }
            InstallStage.FAILED -> {
                s.error?.let { ErrorBox(it) }
                Spacer(Modifier.height(6.dp)); LogBox(s.log); Spacer(Modifier.height(6.dp))
                MsChip(stringResource(R.string.ms_close), TextSecondary) { onClose() }
            }
        }
        if (i.stage == InstallStage.CHECKING || i.stage == InstallStage.READY) s.error?.let { Spacer(Modifier.height(6.dp)); ErrorBox(it) }
    }
}

// ------------------------------------------------------------------------------------------------ Мелочи

@Composable
private fun Divider() = Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(Border))

@Composable
private fun StatusChip(st: ServerStatus?) {
    val (text, color) = when {
        st == null -> stringResource(R.string.ms_st_unknown) to TextMuted
        st.error != null -> stringResource(R.string.ms_st_down) to Danger
        else -> stringResource(R.string.ms_st_up) to Success
    }
    Text(text, color = color, fontSize = 12.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MsRow(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable
private fun Busy(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CircularProgressIndicator(Modifier.size(18.dp), color = AccentCyan, strokeWidth = 2.dp)
    Text(text, color = TextSecondary, fontSize = 13.sp)
}

@Composable
private fun ErrorBox(raw: String) {
    val f = FriendlyText.explain(raw, !ruLocale())
    val clip = LocalClipboardManager.current
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Danger.copy(alpha = 0.08f)).border(1.dp, Danger.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(f.title, color = Danger, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(f.advice, color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        MsRow {
            MsChip(stringResource(if (details) R.string.ms_err_hide else R.string.ms_err_details), TextSecondary) { details = !details }
            MsChip(stringResource(R.string.ms_err_copy), TextSecondary) { clip.setText(AnnotatedString(f.raw)) }
        }
        if (details) SelectionContainer { Text(f.raw, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted, modifier = Modifier.padding(top = 6.dp)) }
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
private fun LoginDialog(title: String, srv: MyServer, error: String?, rememberDefault: Boolean, editUser: Boolean, onCancel: () -> Unit, onOk: (SshTarget, Boolean) -> Unit) {
    var user by remember { mutableStateOf(srv.sshUser) }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(rememberDefault) }
    AlertDialog(
        onDismissRequest = onCancel, containerColor = Surface,
        title = { Text(title, color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                Text(stringResource(R.string.ms_login_for, srv.host, srv.sshPort), fontSize = 12.sp, color = TextMuted)
                if (editUser) MsField(stringResource(R.string.ms_user), user) { user = it.trim() }
                MsField(stringResource(R.string.ms_password), password, secret = true) { password = it }
                MsField(stringResource(R.string.ms_or_key), key, singleLine = false) { key = it }
                if (key.isNotBlank()) MsField(stringResource(R.string.ms_key_pass), passphrase, secret = true) { passphrase = it }
                Row(Modifier.clickableNoRipple { remember = !remember }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(remember, { remember = it })
                    Text(stringResource(R.string.ms_remember), color = TextSecondary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() || key.isNotBlank(), onClick = {
                onOk(SshTarget(srv.host, srv.sshPort, user.ifBlank { srv.sshUser }, password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() },
                    passphrase.takeIf { key.isNotBlank() && it.isNotEmpty() }), remember)
            }) { Text(stringResource(R.string.ms_sign_in), color = AccentCyan) }
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
private fun MsField(label: String, value: String, secret: Boolean = false, singleLine: Boolean = true, hint: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = singleLine, maxLines = if (singleLine) 1 else 4,
        label = { Text(label, fontSize = 12.sp) },
        supportingText = hint?.let { { Text(it, fontSize = 11.sp) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun ruLocale(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ru"

@Composable
private fun uptime(sec: Long): String = when {
    sec >= 86_400 -> stringResource(R.string.ms_uptime_days, sec / 86_400, sec % 86_400 / 3600)
    sec >= 3600 -> stringResource(R.string.ms_uptime_hours, sec / 3600, sec % 3600 / 60)
    else -> stringResource(R.string.ms_uptime_min, sec / 60)
}

@Composable
private fun peerLine(handshake: Long, rx: Long, tx: Long): String {
    if (handshake <= 0) return stringResource(R.string.ms_peer_never)
    val ago = (System.currentTimeMillis() / 1000 - handshake).coerceAtLeast(0)
    val t = when {
        ago < 180 -> stringResource(R.string.ms_peer_online)
        ago < 3600 -> stringResource(R.string.ms_peer_min, ago / 60)
        ago < 86_400 -> stringResource(R.string.ms_peer_hours, ago / 3600)
        else -> stringResource(R.string.ms_peer_days, ago / 86_400)
    }
    return stringResource(R.string.ms_peer_line, t, humanBytes(rx), humanBytes(tx))
}
