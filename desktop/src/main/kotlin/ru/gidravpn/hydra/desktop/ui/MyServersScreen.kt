package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.servers.AwgGeneration
import ru.gidravpn.hydra.servers.MyServer
import ru.gidravpn.hydra.servers.MyServersManager
import ru.gidravpn.hydra.servers.ServerForm
import ru.gidravpn.hydra.servers.SshTarget
import ru.gidravpn.hydra.servers.Stage

/** Действие над уже развёрнутым сервером, для которого нужен вход по SSH (пароль в памяти, не сохраняется). */
private class NeedLogin(val title: String, val server: MyServer, val run: (SshTarget) -> Unit)

// ============================================================== Мои серверы (0.8.0)
@Composable
internal fun MyServersScreen(c: AppController, ui: UiState) {
    val m = c.myServers
    val s by m.ui.collectAsState()
    var wizard by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<NeedLogin?>(null) }
    var confirm by remember { mutableStateOf<NeedLogin?>(null) }
    var addClientFor by remember { mutableStateOf<MyServer?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Мои серверы") {
            Button(onClick = { m.reset(); wizard = true }, enabled = !s.busy) { Text("Добавить сервер") }
        }
        Text(
            "Свой сервер AmneziaWG, развёрнутый из приложения по SSH. Пароль входа не сохраняется; закрепляется только отпечаток SSH-ключа сервера.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (wizard || s.stage != Stage.IDLE) WizardCard(m, s) { wizard = false; m.reset() }
            if (ui.data.myServers.isEmpty() && !wizard && s.stage == Stage.IDLE) Empty("Серверов нет. Нужен VPS с Debian/Ubuntu (root, systemd) и доступом по SSH.")
            ui.data.myServers.forEach { srv ->
                ServerCard(srv, m, s.busy,
                    onAddClient = { addClientFor = srv },
                    onShow = { name -> m.clientConf(srv.id, name)?.let { conf -> m.showConf(name, conf) } },
                    onRemoveClient = { name -> login = NeedLogin("Удалить клиента «$name»", srv) { t -> m.removeClient(srv.id, t, name) } },
                    onUninstall = { confirm = NeedLogin("Удалить сервер «${srv.name}»", srv) { t -> m.uninstall(srv.id, t) } },
                    onForget = { m.forget(srv.id) })
            }
            if (s.log.isNotEmpty() && s.stage == Stage.IDLE) LogBox(s.log)
            s.error?.takeIf { s.stage == Stage.IDLE }?.let { Text(it, color = Danger, fontSize = 13.sp) }
        }
    }

    addClientFor?.let { srv ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addClientFor = null },
            title = { Text("Новый клиент") },
            text = { OutlinedTextField(name, { name = it.filter { ch -> ch.isLetterOrDigit() || ch in "-_" }.take(24) }, singleLine = true, label = { Text("Имя (латиницей)") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && srv.clients.none { it.name == name }, onClick = {
                    addClientFor = null
                    login = NeedLogin("Добавить клиента «$name»", srv) { t -> m.addClient(srv.id, t, name) }
                }) { Text("Далее") }
            },
            dismissButton = { TextButton(onClick = { addClientFor = null }) { Text("Отмена") } },
        )
    }
    confirm?.let { n ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(n.title + "?") },
            text = { Text("На сервере ${n.server.host} будут остановлены и удалены служба hydra-awg, конфиг и бинарники AmneziaWG. Профили клиентов перестанут работать.") },
            confirmButton = { TextButton(onClick = { confirm = null; login = n }) { Text("Далее", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
    login?.let { n ->
        LoginDialog(n.title, n.server.sshUser, n.server.host, n.server.sshPort, onCancel = { login = null }) { t -> login = null; n.run(t) }
    }
    s.shown?.let { (name, conf) ->
        AlertDialog(
            onDismissRequest = { m.hideShown() },
            title = { Text("Клиент «$name»") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).height(320.dp)) {
                    Text("Файл .conf: Hydra импортирует его сам; официальный AmneziaWG — по QR/файлу.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(conf, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { setClipboard(conf); c.toast("Конфиг скопирован") }) { Text("Копировать") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { c.import(conf); m.hideShown() }) { Text("Добавить в Серверы") }
                    TextButton(onClick = { m.hideShown() }) { Text("Закрыть") }
                }
            },
        )
    }
}

@Composable
private fun ServerCard(
    srv: MyServer, m: MyServersManager, busy: Boolean,
    onAddClient: () -> Unit, onShow: (String) -> Unit, onRemoveClient: (String) -> Unit, onUninstall: () -> Unit, onForget: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(srv.name, style = MaterialTheme.typography.titleMedium)
            Text("${srv.host}:${srv.sshPort} · AmneziaWG ${srv.awg?.generation?.label ?: "—"} · UDP ${srv.awg?.port ?: "—"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Отпечаток SSH: ${srv.fingerprint}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            srv.clients.forEach { cl ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${cl.name} · ${cl.address.substringBefore('/')}", Modifier.width(220.dp), fontSize = 13.sp)
                    OutlinedButton(onClick = { onShow(cl.name) }) { Text("Конфиг / QR-текст") }
                    TextButton(onClick = { onRemoveClient(cl.name) }, enabled = !busy && srv.clients.size > 1) { Text("Удалить", color = Danger) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAddClient, enabled = !busy) { Text("Добавить клиента") }
                OutlinedButton(onClick = onUninstall, enabled = !busy) { Text("Удалить сервер", color = Danger) }
                TextButton(onClick = onForget, enabled = !busy) { Text("Убрать из списка") }
            }
        }
    }
}

@Composable
private fun WizardCard(m: MyServersManager, s: ru.gidravpn.hydra.servers.MyServersUi, onClose: () -> Unit) {
    var name by remember { mutableStateOf("Мой сервер") }
    var host by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var gen by remember { mutableStateOf(AwgGeneration.V2_0) }
    var awgPort by remember { mutableStateOf("51820") }
    var openFw by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Новый сервер AmneziaWG", style = MaterialTheme.typography.titleMedium)
            if (s.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp); Text(s.progress, fontSize = 13.sp)
            }
            when (s.stage) {
                Stage.IDLE, Stage.FAILED -> {
                    s.error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(name, { name = it.take(40) }, Modifier.weight(1f), singleLine = true, label = { Text("Название") })
                        OutlinedTextField(host, { host = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text("Адрес сервера (IP или имя)") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(sshPort, { sshPort = it.filter(Char::isDigit).take(5) }, Modifier.width(110.dp), singleLine = true, label = { Text("Порт SSH") })
                        OutlinedTextField(user, { user = it.trim() }, Modifier.width(140.dp), singleLine = true, label = { Text("Пользователь") })
                        OutlinedTextField(password, { password = it }, Modifier.weight(1f), singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), label = { Text("Пароль") })
                    }
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().height(90.dp), label = { Text("Или закрытый SSH-ключ (текст, вместо пароля)") })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Поколение:", fontSize = 13.sp)
                        AwgGeneration.entries.forEach { g -> FilterChip(selected = gen == g, onClick = { gen = g }, label = { Text(g.label) }) }
                        OutlinedTextField(awgPort, { awgPort = it.filter(Char::isDigit).take(5) }, Modifier.width(120.dp), singleLine = true, label = { Text("UDP-порт") })
                    }
                    Text(genHint(gen), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(openFw, { openFw = it }); Text("Открыть порт в ufw/firewalld (только если файрвол включён)", fontSize = 13.sp)
                    }
                    val port = sshPort.toIntOrNull() ?: 0
                    val aport = awgPort.toIntOrNull() ?: 0
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !s.busy && MyServersManager.validHost(host) && port in 1..65535 && aport in 1024..65535 && (password.isNotEmpty() || key.isNotBlank()),
                            onClick = {
                                m.begin(ServerForm(name.ifBlank { host }, SshTarget(host, port, user.ifBlank { "root" },
                                    password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() }), gen, aport, openFw))
                            },
                        ) { Text("Подключиться") }
                        TextButton(onClick = onClose) { Text("Закрыть") }
                    }
                }
                Stage.CONFIRM_FINGERPRINT -> {
                    Text("Отпечаток SSH-ключа сервера. Сверьте с тем, что показывает панель хостинга (или `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` на сервере); в дальнейшем соединение будет разрешено только с ним.", fontSize = 13.sp)
                    SelectionContainer { Text(s.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { m.confirmFingerprint() }, enabled = !s.busy) { Text("Доверять и войти") }
                        TextButton(onClick = onClose) { Text("Отмена") }
                    }
                }
                Stage.READY -> {
                    val pre = s.preflight
                    if (pre != null) {
                        Text("Сервер: ${pre.osName.ifBlank { "Linux" }} · ${pre.arch} · root=${yn(pre.root)} · systemd=${yn(pre.systemd)} · порт свободен=${yn(pre.portFree)} · интернет=${yn(pre.internet)} · файрвол=${pre.firewall}", fontSize = 13.sp)
                        pre.issues.forEach { Text("• $it", color = Danger, fontSize = 13.sp) }
                    }
                    Text("Что будет выполнено на сервере:", fontSize = 13.sp)
                    SelectionContainer {
                        Column {
                            s.plan.forEachIndexed { i, st ->
                                Text("${i + 1}. ${st.title}", fontSize = 12.sp)
                                (st.command ?: st.upload?.let { "загрузка → ${it.path}" })?.let { Text("   $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { m.install() }, enabled = pre?.ok == true) { Text("Установить") }
                        TextButton(onClick = onClose) { Text("Отмена") }
                    }
                }
                Stage.RUNNING -> {
                    LogBox(s.log)
                    OutlinedButton(onClick = { m.stop() }) { Text("Остановить") }
                }
                Stage.DONE -> {
                    Text(s.progress, fontSize = 13.sp)
                    LogBox(s.log)
                    Button(onClick = onClose) { Text("Готово") }
                }
            }
            if (s.stage == Stage.FAILED && s.log.isNotEmpty()) LogBox(s.log)
        }
    }
}

private fun yn(b: Boolean) = if (b) "да" else "нет"

private fun genHint(g: AwgGeneration) = when (g) {
    AwgGeneration.V1_0 -> "1.0: Jc, Jmin, Jmax, S1, S2, H1–H4. Работает с любыми клиентами AmneziaWG."
    AwgGeneration.V1_5 -> "1.5: параметры как в 1.0 (сигнатурные пакеты I1–I5 не заданы); совместим с клиентами 1.0 и 1.5."
    AwgGeneration.V2_0 -> "2.0: + S3, S4 и диапазоны H1–H4. Нужен клиент 2.0 и новее (Hydra подходит)."
    AwgGeneration.V3 -> "3.x: + HeaderProtectionKey, дополнение содержимого, случайные хвосты. Нужен клиент 3.x (Hydra подходит)."
}

@Composable
private fun LogBox(lines: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(8.dp).height(180.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
                lines.takeLast(200).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun LoginDialog(title: String, user: String, host: String, port: Int, onCancel: () -> Unit, onOk: (SshTarget) -> Unit) {
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Вход на $user@$host:$port (данные не сохраняются)", fontSize = 12.sp)
                OutlinedTextField(password, { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Пароль") })
                OutlinedTextField(key, { key = it }, Modifier.height(80.dp), label = { Text("или SSH-ключ") })
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() || key.isNotBlank(), onClick = {
                onOk(SshTarget(host, port, user, password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() }))
            }) { Text("Выполнить") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
