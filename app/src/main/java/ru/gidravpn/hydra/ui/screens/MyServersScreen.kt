package ru.gidravpn.hydra.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
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
import ru.gidravpn.hydra.servers.FriendlyText
import ru.gidravpn.hydra.servers.MyServer
import ru.gidravpn.hydra.servers.MyServersManager
import ru.gidravpn.hydra.servers.MyServersUi
import ru.gidravpn.hydra.servers.OfxNodeSpec
import ru.gidravpn.hydra.servers.ServerForm
import ru.gidravpn.hydra.servers.ServerKind
import ru.gidravpn.hydra.servers.SshTarget
import ru.gidravpn.hydra.servers.Stage
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.MyServersViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

private const val GUIDE_URL = "https://github.com/Chistovik92/HydraVPN/blob/master/docs/SERVERS.md"

/** Действие над развёрнутым сервером, которому нужен пароль (запрашивается и нигде не сохраняется). */
private class NeedLogin(val title: String, val why: String, val server: MyServer, val run: (SshTarget) -> Unit)

private fun kindOf(s: MyServer) = when { s.panel != null -> ServerKind.PANEL_3XUI; s.ofx != null -> ServerKind.OPENFLUX; else -> ServerKind.AMNEZIAWG }

@Composable
fun MyServersScreen(vm: MainViewModel, onBack: () -> Unit, mvm: MyServersViewModel = viewModel()) {
    val m = mvm.manager
    val s by m.ui.collectAsState()
    val servers by mvm.servers.collectAsState()
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var wizard by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<NeedLogin?>(null) }
    var confirm by remember { mutableStateOf<NeedLogin?>(null) }
    var forget by remember { mutableStateOf<MyServer?>(null) }
    var addDeviceFor by remember { mutableStateOf<MyServer?>(null) }
    var addUserFor by remember { mutableStateOf<MyServer?>(null) }
    var connectPanel by remember { mutableStateOf(false) }

    val addedFmt = stringResource(R.string.ms_added, "%s")
    DisposableEffect(Unit) {
        mvm.importConf = { name, conf ->
            vm.importAuto(listOf(conf), name)
            android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, addedFmt.format(name), Toast.LENGTH_SHORT).show() }
        }
        onDispose { mvm.importConf = { _, _ -> } }
    }
    androidx.activity.compose.BackHandler { onBack() }
    val openUrl = { url: String -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; Unit }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ms_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                MsChip(stringResource(R.string.ms_back), TextSecondary) { onBack() }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                MsChip(stringResource(R.string.ms_help_btn), TextSecondary) { help = true }
                if (!wizard && s.stage == Stage.IDLE) MsChip(stringResource(R.string.ms_add), AccentCyan) { if (!s.busy) { m.reset(); wizard = true } }
                if (!wizard && s.stage == Stage.IDLE) MsChip(stringResource(R.string.ms_connect_panel), AccentViolet) { if (!s.busy) { m.reset(); connectPanel = true } }
            }
        }
        if (servers.isEmpty() && !wizard && s.stage == Stage.IDLE) item {
            Card {
                Text(stringResource(R.string.ms_intro_title), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.ms_intro_text), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ms_intro_need), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(stringResource(R.string.ms_intro_need_text), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    MsChip(stringResource(R.string.ms_start), AccentCyan) { m.reset(); wizard = true }
                    MsChip(stringResource(R.string.ms_guide), AccentViolet) { openUrl(GUIDE_URL) }
                }
            }
        }
        if (wizard || s.stage != Stage.IDLE) item { WizardCard(m, s, servers, openUrl) { wizard = false; m.reset() } }
        servers.forEach { srv ->
            item(key = srv.id) {
                ServerCard(srv, m, s, openUrl,
                    onAddDevice = { addDeviceFor = srv },
                    onAddUser = { addUserFor = srv },
                    onRemoveDevice = { name -> login = NeedLogin(ctx.getString(R.string.ms_act_remove_device, name), ctx.getString(R.string.ms_act_remove_device_why, name), srv) { t -> m.removeClient(srv.id, t, name) } },
                    onUninstall = { confirm = NeedLogin(ctx.getString(R.string.ms_act_uninstall, srv.name), "", srv) { t -> m.uninstall(srv.id, t) } },
                    onForget = { forget = srv })
            }
        }
        if (s.stage == Stage.IDLE && !wizard) s.error?.let { item { ErrorBox(it) } }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (connectPanel) ConnectPanelDialog(onClose = { connectPanel = false }) { name, address, token ->
        connectPanel = false; m.connectPanel(name, address, token)
    }

    if (help) AlertDialog(
        onDismissRequest = { help = false }, containerColor = Surface,
        title = { Text(stringResource(R.string.ms_help_title), color = TextPrimary) },
        text = { Text(stringResource(R.string.ms_help_text), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ help = false }) { Text(stringResource(R.string.ms_ok), color = AccentCyan) } },
        dismissButton = { TextButton({ openUrl(GUIDE_URL) }) { Text(stringResource(R.string.ms_guide), color = TextMuted) } },
    )
    addDeviceFor?.let { srv ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDeviceFor = null }, containerColor = Surface,
            title = { Text(stringResource(R.string.ms_new_device), color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ms_new_device_hint), color = TextSecondary, fontSize = 13.sp)
                    MsField(stringResource(R.string.ms_latin_name), name) { name = it.filter { ch -> ch.isLetterOrDigit() || ch in "-_" }.take(24) }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && srv.clients.none { it.name == name }, onClick = {
                    addDeviceFor = null
                    login = NeedLogin(ctx.getString(R.string.ms_act_add_device, name), ctx.getString(R.string.ms_act_add_device_why), srv) { t -> m.addClient(srv.id, t, name) }
                }) { Text(stringResource(R.string.ms_next), color = AccentCyan) }
            },
            dismissButton = { TextButton({ addDeviceFor = null }) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
        )
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
    forget?.let { srv ->
        AlertDialog(
            onDismissRequest = { forget = null }, containerColor = Surface,
            title = { Text(stringResource(R.string.ms_forget_title, srv.name), color = TextPrimary) },
            text = { Text(stringResource(R.string.ms_forget_msg), color = TextSecondary, fontSize = 13.sp) },
            confirmButton = { TextButton({ forget = null; m.forget(srv.id) }) { Text(stringResource(R.string.ms_remove), color = Danger) } },
            dismissButton = { TextButton({ forget = null }) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
        )
    }
    confirm?.let { n ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = Surface,
            title = { Text(n.title + "?", color = TextPrimary) },
            text = { Text(stringResource(R.string.ms_uninstall_msg, n.server.host), color = TextSecondary, fontSize = 13.sp) },
            confirmButton = { TextButton({ confirm = null; login = n }) { Text(stringResource(R.string.ms_continue), color = Danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.ms_cancel), color = TextMuted) } },
        )
    }
    login?.let { n -> LoginDialog(n, onCancel = { login = null }) { t -> login = null; n.run(t) } }
    s.shown?.let { (name, text) ->
        val copied = stringResource(R.string.ms_copied)
        val isConf = "[Interface]" in text
        AlertDialog(
            onDismissRequest = { m.hideShown() }, containerColor = Surface,
            title = { Text(stringResource(R.string.ms_access_title, name), color = TextPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (isConf) R.string.ms_access_conf else R.string.ms_access_link, name), fontSize = 13.sp, color = TextSecondary)
                    Text(stringResource(R.string.ms_access_secret), fontSize = 12.sp, color = Danger)
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

@Composable
private fun ErrorBox(raw: String) {
    val f = FriendlyText.explain(raw, ruLocale().not())
    val clip = LocalClipboardManager.current
    var details by remember { mutableStateOf(false) }
    Card(borderColor = Danger.copy(alpha = 0.6f)) {
        Text(f.title, color = Danger, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(f.advice, color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            MsChip(stringResource(if (details) R.string.ms_err_hide else R.string.ms_err_details), TextSecondary) { details = !details }
            MsChip(stringResource(R.string.ms_err_copy), TextSecondary) { clip.setText(AnnotatedString(f.raw)) }
        }
        if (details) { Spacer(Modifier.height(6.dp)); SelectionContainer { Text(f.raw, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted) } }
    }
}

// ------------------------------------------------------------------------------------------------ Мастер

@Composable
private fun WizardCard(m: MyServersManager, s: MyServersUi, servers: List<MyServer>, openUrl: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var local by remember { mutableStateOf(1) }
    var kind by remember { mutableStateOf(ServerKind.AMNEZIAWG) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var sshPort by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }
    var key by remember { mutableStateOf("") }
    var gen by remember { mutableStateOf(AwgGeneration.V2_0) }
    var awgPort by remember { mutableStateOf("51820") }
    var openFw by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf("hydra") }
    var yandex by remember { mutableStateOf("") }
    var mailru by remember { mutableStateOf("") }
    var panelPort by remember { mutableStateOf("443") }
    var showCommands by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    val step = when (s.stage) {
        Stage.IDLE, Stage.FAILED -> local
        Stage.CONFIRM_FINGERPRINT -> 3
        Stage.READY -> 4
        Stage.RUNNING -> 5
        Stage.DONE -> 6
    }
    val stepTitles = listOf(R.string.ms_step_1, R.string.ms_step_2, R.string.ms_step_3, R.string.ms_step_4, R.string.ms_step_5, R.string.ms_step_6)
    val defaultName = stringResource(when (kind) { ServerKind.AMNEZIAWG -> R.string.ms_name_awg; ServerKind.OPENFLUX -> R.string.ms_name_ofx; ServerKind.PANEL_3XUI -> R.string.ms_name_xui })

    Card {
        Text(stringResource(R.string.ms_step_fmt, step, stringResource(stepTitles[step - 1])), color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { step / 6f }, Modifier.fillMaxWidth(), color = AccentCyan)
        Spacer(Modifier.height(10.dp))
        if (s.stage == Stage.FAILED) {
            s.error?.let { ErrorBox(it); Spacer(Modifier.height(8.dp)) }
            if (s.log.isNotEmpty()) {
                MsChip(stringResource(if (showLog) R.string.ms_log_hide else R.string.ms_log_failed), TextSecondary) { showLog = !showLog }
                if (showLog) { Spacer(Modifier.height(6.dp)); LogBox(s.log) }
                Spacer(Modifier.height(8.dp))
            }
        }
        when (step) {
            1 -> {
                Text(stringResource(R.string.ms_choose_hint), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                ServerKind.entries.forEach { k ->
                    val (t, d, r) = when (k) {
                        ServerKind.AMNEZIAWG -> Triple(R.string.ms_kind_awg, R.string.ms_kind_awg_text, R.string.ms_kind_awg_rec)
                        ServerKind.OPENFLUX -> Triple(R.string.ms_kind_ofx, R.string.ms_kind_ofx_text, R.string.ms_kind_ofx_rec)
                        ServerKind.PANEL_3XUI -> Triple(R.string.ms_kind_xui, R.string.ms_kind_xui_text, R.string.ms_kind_xui_rec)
                    }
                    val sel = kind == k
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp))
                            .border(if (sel) 2.dp else 1.dp, if (sel) AccentCyan else Border, RoundedCornerShape(14.dp))
                            .clickableNoRipple { kind = k }.padding(12.dp),
                    ) {
                        Text((if (sel) "● " else "○ ") + stringResource(t), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(d), color = TextSecondary, fontSize = 13.sp)
                        Text(stringResource(r), color = TextMuted, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MsChip(stringResource(R.string.ms_next), AccentCyan) { local = 2 }
                    MsChip(stringResource(R.string.ms_close), TextSecondary) { onClose() }
                }
            }
            2 -> {
                Text(stringResource(R.string.ms_data_hint), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                MsField(stringResource(R.string.ms_host), host, hint = stringResource(R.string.ms_host_hint)) { host = it.trim() }
                Spacer(Modifier.height(8.dp))
                MsField(stringResource(R.string.ms_password), password, secret = true, hint = stringResource(R.string.ms_password_hint)) { password = it }
                Spacer(Modifier.height(8.dp))
                MsField(stringResource(R.string.ms_name_label), name, hint = defaultName) { name = it.take(40) }
                Spacer(Modifier.height(6.dp))
                MsChip(stringResource(if (advanced) R.string.ms_advanced_hide else R.string.ms_advanced_show), TextSecondary) { advanced = !advanced }
                if (advanced) {
                    Spacer(Modifier.height(8.dp))
                    MsField(stringResource(R.string.ms_ssh_port), sshPort) { sshPort = it.filter(Char::isDigit).take(5) }
                    Spacer(Modifier.height(8.dp))
                    MsField(stringResource(R.string.ms_user), user) { user = it.trim() }
                    Spacer(Modifier.height(8.dp))
                    MsField(stringResource(R.string.ms_key), key, singleLine = false) { key = it }
                    Spacer(Modifier.height(10.dp))
                    when (kind) {
                        ServerKind.AMNEZIAWG -> {
                            Text(stringResource(R.string.ms_awg_version), color = TextSecondary, fontSize = 13.sp)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AwgGeneration.entries.forEach { g ->
                                    MsChip(if (g == AwgGeneration.V2_0) stringResource(R.string.ms_awg_recommended, g.label) else g.label, TextSecondary, selected = gen == g) { gen = g }
                                }
                            }
                            Text(stringResource(when (gen) {
                                AwgGeneration.V1_0 -> R.string.ms_gen_1_0
                                AwgGeneration.V1_5 -> R.string.ms_gen_1_5
                                AwgGeneration.V2_0 -> R.string.ms_gen_2_0
                                AwgGeneration.V3 -> R.string.ms_gen_3
                            }), color = TextMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            MsField(stringResource(R.string.ms_awg_port), awgPort) { awgPort = it.filter(Char::isDigit).take(5) }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.clickableNoRipple { openFw = !openFw }, verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(openFw, { openFw = it })
                                Column {
                                    Text(stringResource(R.string.ms_open_fw), color = TextPrimary, fontSize = 13.sp)
                                    Text(stringResource(R.string.ms_open_fw_hint), color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                        ServerKind.OPENFLUX -> {
                            MsField(stringResource(R.string.ms_ofx_channel), channel) { channel = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(31) }
                            Spacer(Modifier.height(8.dp))
                            MsField(stringResource(R.string.ms_ofx_yandex), yandex) { yandex = it.trim() }
                            Spacer(Modifier.height(8.dp))
                            MsField(stringResource(R.string.ms_ofx_mailru), mailru) { mailru = it.trim() }
                        }
                        ServerKind.PANEL_3XUI -> MsField(stringResource(R.string.ms_xui_port), panelPort) { panelPort = it.filter(Char::isDigit).take(5) }
                    }
                }
                val port = sshPort.toIntOrNull() ?: 0
                val extraOk = when (kind) {
                    ServerKind.AMNEZIAWG -> (awgPort.toIntOrNull() ?: 0) in 1024..65535
                    ServerKind.OPENFLUX -> OfxNodeSpec.validChannel(channel)
                    ServerKind.PANEL_3XUI -> (panelPort.toIntOrNull() ?: 0) in 1..65535
                }
                val hostOk = MyServersManager.validHost(host)
                if (host.isNotBlank() && !hostOk) Text(stringResource(R.string.ms_host_bad), color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                val ready = !s.busy && hostOk && port in 1..65535 && extraOk && (password.isNotEmpty() || key.isNotBlank())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    MsChip(stringResource(R.string.ms_back_arrow), TextSecondary) { local = 1 }
                    if (ready) MsChip(stringResource(R.string.ms_check_server), AccentCyan) {
                        m.begin(ServerForm(name.ifBlank { defaultName }, SshTarget(host, port, user.ifBlank { "root" },
                            password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() }), gen, awgPort.toIntOrNull() ?: 51820, openFw,
                            kind, channel, yandex, mailru, accessPort = panelPort.toIntOrNull() ?: 443))
                    }
                    MsChip(stringResource(R.string.ms_close), TextSecondary) { onClose() }
                }
                if (s.busy) { Spacer(Modifier.height(8.dp)); Busy(s.progress.ifBlank { stringResource(R.string.ms_connecting) }) }
            }
            3 -> {
                Text(stringResource(R.string.ms_fp_text1), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.ms_fp_text2), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                SelectionContainer { Text(s.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextPrimary) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!s.busy) MsChip(stringResource(R.string.ms_fp_yes), AccentCyan) { m.confirmFingerprint() }
                    MsChip(stringResource(R.string.ms_fp_no), TextSecondary) { onClose() }
                }
                if (s.busy) { Spacer(Modifier.height(8.dp)); Busy(s.progress.ifBlank { stringResource(R.string.ms_signing_in) }) }
            }
            4 -> {
                s.preflight?.let { pre ->
                    Text(stringResource(R.string.ms_checked), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    FriendlyText.checklist(pre, ruLocale().not()).forEach { item ->
                        Text((if (item.ok) "✓ " else "✗ ") + item.title, color = if (item.ok) Success else Danger, fontSize = 13.sp)
                        if (!item.ok) Text(item.help, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.ms_plan_title), color = TextPrimary, fontWeight = FontWeight.SemiBold)
                FriendlyText.planSentences(kind, openFw, ruLocale().not()).forEachIndexed { i, t -> Text("${i + 1}. $t", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) }
                Text(stringResource(R.string.ms_plan_time), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(6.dp))
                MsChip(stringResource(if (showCommands) R.string.ms_details_hide else R.string.ms_details_show), TextSecondary) { showCommands = !showCommands }
                if (showCommands) SelectionContainer {
                    Column(Modifier.padding(top = 6.dp)) {
                        s.plan.forEachIndexed { i, st ->
                            Text("${i + 1}. ${st.title}", fontSize = 12.sp, color = TextPrimary)
                            val detail = st.command ?: st.upload?.let { u -> stringResource(R.string.ms_upload_to, u.path) }
                            detail?.let { Text("   $it", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextMuted) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.preflight?.ok == true) MsChip(stringResource(R.string.ms_install), AccentCyan) { m.install() }
                    MsChip(stringResource(R.string.ms_cancel), TextSecondary) { onClose() }
                }
                if (s.preflight?.ok == false) Text(stringResource(R.string.ms_checks_bad), color = Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            5 -> {
                Busy(s.progress.ifBlank { stringResource(R.string.ms_working) })
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.ms_running_text), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                MsChip(stringResource(if (showLog) R.string.ms_log_hide else R.string.ms_log_show), TextSecondary) { showLog = !showLog }
                if (showLog) { Spacer(Modifier.height(6.dp)); LogBox(s.log) }
                Spacer(Modifier.height(8.dp))
                MsChip(stringResource(R.string.ms_stop), Danger) { m.stop() }
            }
            else -> {
                Text(stringResource(R.string.ms_done_title), color = Success, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val done = servers.lastOrNull()
                when (kind) {
                    ServerKind.AMNEZIAWG -> Text(stringResource(R.string.ms_done_awg, done?.name ?: ""), color = TextSecondary, fontSize = 13.sp)
                    ServerKind.OPENFLUX -> Text(stringResource(R.string.ms_done_ofx), color = TextSecondary, fontSize = 13.sp)
                    ServerKind.PANEL_3XUI -> {
                        Text(stringResource(R.string.ms_done_xui), color = TextSecondary, fontSize = 13.sp)
                        done?.panel?.let { p ->
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.ms_done_creds), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            val creds = stringResource(R.string.ms_creds_fmt, p.baseUrl, p.username, p.password)
                            SelectionContainer { Text(creds, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary) }
                            Spacer(Modifier.height(6.dp))
                            val copiedMsg = stringResource(R.string.ms_creds_copied)
                            val pwMsg = stringResource(R.string.ms_password_copied)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                MsChip(stringResource(R.string.ms_copy_creds), TextSecondary) { clip.setText(AnnotatedString(creds)); Toast.makeText(ctx, copiedMsg, Toast.LENGTH_SHORT).show() }
                                MsChip(stringResource(R.string.ms_open_panel), AccentCyan) { clip.setText(AnnotatedString(p.password)); Toast.makeText(ctx, pwMsg, Toast.LENGTH_LONG).show(); openUrl(p.baseUrl) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                MsChip(stringResource(R.string.ms_close), AccentCyan) { onClose() }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Карточка сервера

@Composable
private fun ServerCard(
    srv: MyServer, m: MyServersManager, st: MyServersUi, openUrl: (String) -> Unit,
    onAddDevice: () -> Unit, onAddUser: () -> Unit, onRemoveDevice: (String) -> Unit, onUninstall: () -> Unit, onForget: () -> Unit,
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val busy = st.busy
    var more by remember { mutableStateOf(false) }
    val kind = kindOf(srv)
    val kindName = stringResource(when (kind) { ServerKind.AMNEZIAWG -> R.string.ms_kind_awg; ServerKind.OPENFLUX -> R.string.ms_kind_ofx; ServerKind.PANEL_3XUI -> R.string.ms_kind_xui })
    Card {
        Text(srv.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(stringResource(R.string.ms_card_kind_fmt, kindName, srv.host), color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        when (kind) {
            ServerKind.AMNEZIAWG -> {
                Text(stringResource(R.string.ms_devices), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                srv.clients.forEach { cl ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("• ${cl.name}", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        MsChip(stringResource(R.string.ms_show_access), AccentViolet) { m.clientConf(srv.id, cl.name)?.let { conf -> m.showConf(cl.name, conf) } }
                        if (srv.clients.size > 1) MsChip(stringResource(R.string.ms_delete), Danger) { if (!busy) onRemoveDevice(cl.name) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                MsChip(stringResource(R.string.ms_add_device), AccentCyan) { if (!busy) onAddDevice() }
            }
            ServerKind.OPENFLUX -> {
                Text(stringResource(R.string.ms_ofx_card_text), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                MsChip(stringResource(R.string.ms_show_link), AccentViolet) { m.clientConf(srv.id, "")?.let { link -> m.showConf("openflux://", link) } }
            }
            ServerKind.PANEL_3XUI -> {
                val p = srv.panel!!
                val pwMsg = stringResource(R.string.ms_password_copied)
                Text(stringResource(R.string.ms_panel_card_text, p.version.ifBlank { "—" }), color = TextSecondary, fontSize = 13.sp)
                SelectionContainer { Text("${p.baseUrl}\n${p.username}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextMuted) }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    if (p.password.isBlank()) MsChip(stringResource(R.string.ms_open_panel), AccentCyan) { openUrl(p.baseUrl) }
                    else {
                        MsChip(stringResource(R.string.ms_open_panel), AccentCyan) { copySecret(clip, p.password); Toast.makeText(ctx, pwMsg, Toast.LENGTH_LONG).show(); openUrl(p.baseUrl) }
                        MsChip(stringResource(R.string.ms_copy_password), TextSecondary) { copySecret(clip, p.password); Toast.makeText(ctx, pwMsg, Toast.LENGTH_SHORT).show() }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    MsChip(stringResource(R.string.ms_panel_users), AccentViolet) { if (!busy) m.loadPanelClients(srv.id) }
                    MsChip(stringResource(R.string.ms_panel_add_user), AccentCyan) { if (!busy) onAddUser() }
                }
                st.panelClients[srv.id]?.let { emails ->
                    if (emails.isEmpty()) Text(stringResource(R.string.ms_panel_no_users), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    emails.forEach { email ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("• $email", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            MsChip(stringResource(R.string.ms_panel_link), AccentViolet) { if (!busy) m.showPanelLinks(srv.id, email) }
                            MsChip(stringResource(R.string.ms_delete), Danger) { if (!busy) m.removePanelClient(srv.id, email) }
                        }
                    }
                }
                if (st.error != null && st.stage == Stage.IDLE) { Spacer(Modifier.height(8.dp)); ErrorBox(st.error!!) }
            }
        }
        if (busy) { Spacer(Modifier.height(8.dp)); Busy(stringResource(R.string.ms_working)) }
        Spacer(Modifier.height(6.dp))
        MsChip(stringResource(if (more) R.string.ms_hide else R.string.ms_more), TextSecondary) { more = !more }
        if (more) {
            Spacer(Modifier.height(6.dp))
            val overSsh = srv.fingerprint.isNotBlank()   // подключённая вручную панель SSH не использует — удалять с сервера нечего
            if (overSsh) Text(stringResource(R.string.ms_fingerprint, srv.fingerprint), color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                if (overSsh) MsChip(stringResource(R.string.ms_uninstall), Danger) { if (!busy) onUninstall() }
                MsChip(stringResource(R.string.ms_forget), TextSecondary) { if (!busy) onForget() }
            }
            Text(stringResource(if (overSsh) R.string.ms_more_note else R.string.ms_more_note_connected), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Busy(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CircularProgressIndicator(Modifier.size(18.dp), color = AccentCyan, strokeWidth = 2.dp)
    Text(text, color = TextSecondary, fontSize = 13.sp)
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
        onDismissRequest = onCancel, containerColor = Surface,
        title = { Text(n.title, color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (n.why.isNotBlank()) Text(n.why, fontSize = 13.sp, color = TextSecondary)
                Text(stringResource(R.string.ms_login_why, srv.host, srv.sshUser), fontSize = 12.sp, color = TextMuted)
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

/** «У меня уже есть панель» (0.8.1-a): адрес и токен доступа 3x-ui — без установки и без входа на сервер. */
@Composable
private fun ConnectPanelDialog(onClose: () -> Unit, onOk: (name: String, address: String, token: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val plain = address.isNotBlank() && !ru.gidravpn.hydra.servers.XuiPanel.isHttps(ru.gidravpn.hydra.servers.XuiPanel.normalizeUrl(address).orEmpty())
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

/** Пароль панели в буфер на 30 секунд (0.8.1-a): потом очищается, если там всё ещё он. */
private fun copySecret(clip: androidx.compose.ui.platform.ClipboardManager, text: String) {
    clip.setText(AnnotatedString(text))
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        if (clip.getText()?.text == text) clip.setText(AnnotatedString(""))
    }, 30_000)
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

/** Тексты проверки и плана — русские у русского интерфейса, у остальных языков английские. */
@Composable
private fun ruLocale(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "ru"
