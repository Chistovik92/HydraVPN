package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.servers.AwgGeneration
import ru.gidravpn.hydra.servers.FriendlyText
import ru.gidravpn.hydra.servers.InstallStage
import ru.gidravpn.hydra.servers.MyServer
import ru.gidravpn.hydra.servers.PanelKind
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

private val Ok = Color(0xFF22C55E)

/** Действие, которое надо подтвердить (удаление, очистка, перезагрузка). */
private class Confirm(val title: String, val text: String, val button: String, val run: () -> Unit)

// ============================================================== Мои серверы (0.8.0; сервер-сначала с 0.8.0-rc4)
@Composable
internal fun MyServersScreen(c: AppController, ui: UiState) {
    val m = c.myServers
    val s by m.ui.collectAsState()
    var wizard by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var addClientFor by remember { mutableStateOf<MyServer?>(null) }
    var loginFor by remember { mutableStateOf<MyServer?>(null) }
    var connectPanel by remember { mutableStateOf(false) }
    var addUserFor by remember { mutableStateOf<MyServer?>(null) }

    // при первом показе — проверить серверы с сохранённым входом (без журнала, по одному: операции идут по очереди)
    LaunchedEffect(Unit) {
        ui.data.myServers.filter { m.hasSavedLogin(it.id) && it.id !in s.status }.forEach { srv ->
            while (m.ui.value.busy) kotlinx.coroutines.delay(300)
            m.refresh(srv.id)
            kotlinx.coroutines.delay(300)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Мои серверы") {
            Button(onClick = { m.reset(); wizard = true }, enabled = !s.busy) { Text("Добавить сервер") }
            OutlinedButton(onClick = { m.reset(); connectPanel = true }, enabled = !s.busy) { Text("У меня уже есть панель") }
        }
        Text(
            "Ваш собственный сервер (VPS). Hydra входит на него по SSH, ставит VPN и обслуживает его: устройства, перезапуск, удаление, " +
                "полная очистка. Данные входа можно запомнить — они хранятся зашифрованными только на этом компьютере. " +
                "Отпечаток SSH-ключа сервера закрепляется: подменённый сервер Hydra не примет.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (wizard || s.stage != Stage.IDLE) AddServerCard(m, s) { wizard = false; m.reset() }
            // ошибка/ход подключения готовой панели (без сервера в списке)
            if (!wizard && s.stage == Stage.IDLE && s.opServer == null) {
                if (s.busy) Busy(s.progress)
                s.error?.let { ErrorBox(it) }
                if (!s.busy && s.progress == "Панель подключена") Text("Панель подключена — она в списке ниже.", color = Ok, fontSize = 13.sp)
            }
            if (ui.data.myServers.isEmpty() && !wizard && s.stage == Stage.IDLE)
                Empty("Серверов пока нет. Нужен VPS с Debian или Ubuntu, вход root по паролю или ключу.")
            ui.data.myServers.forEach { srv ->
                if (srv.broken) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(srv.name, style = MaterialTheme.typography.titleMedium)
                        Text("Запись повреждена: так выглядит панель, подключённая в 0.8.1-a, после открытия в 0.8.0-rc4. Уберите её и подключите панель заново кнопкой «У меня уже есть панель».",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { m.forget(srv.id) }) { Text("Убрать из списка") }
                    }
                }
                else if (srv.panelOnly) PanelOnlyCard(c, srv, m, s, onAddUser = { addUserFor = srv }, onConfirm = { confirm = it })
                else ServerCard(c, srv, m, s,
                    onAddClient = { addClientFor = srv },
                    onAddUser = { addUserFor = srv },
                    onLogin = { loginFor = srv },
                    onConfirm = { confirm = it })
            }
        }
    }

    addClientFor?.let { srv ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addClientFor = null },
            title = { Text("Новое устройство") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Для каждого телефона или компьютера — своё устройство: его можно отключить, не трогая остальные.", fontSize = 13.sp)
                    OutlinedTextField(name, { name = it.filter { ch -> ch.isLetterOrDigit() || ch in "-_" }.take(24) }, singleLine = true, label = { Text("Имя латиницей, например phone") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && srv.clients.none { it.name == name }, onClick = { addClientFor = null; m.addClient(srv.id, name) }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { addClientFor = null }) { Text("Отмена") } },
        )
    }
    if (connectPanel) ConnectPanelDialog(onClose = { connectPanel = false }) { name, address, token ->
        connectPanel = false; m.connectPanel(name, address, token)
    }
    addUserFor?.let { srv ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addUserFor = null },
            title = { Text("Новый пользователь панели") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Панель создаст пользователя и выдаст ссылку для подключения.", fontSize = 13.sp)
                    OutlinedTextField(name, { name = it.filter { ch -> ch.isLetterOrDigit() || ch in "-_" }.take(24) }, singleLine = true, label = { Text("Имя латиницей, например anna") })
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { addUserFor = null; m.addPanelClient(srv.id, name) }) { Text("Создать") } },
            dismissButton = { TextButton(onClick = { addUserFor = null }) { Text("Отмена") } },
        )
    }
    confirm?.let { k ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(k.title) },
            text = { Text(k.text, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { confirm = null; k.run() }) { Text(k.button, color = Danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
    loginFor?.let { srv ->
        LoginDialog("Данные входа на «${srv.name}»", srv.sshUser, srv.host, srv.sshPort, null, rememberDefault = m.hasSavedLogin(srv.id), editUser = true,
            onCancel = { loginFor = null }) { t, remember -> loginFor = null; m.changeLogin(srv.id, t, remember) }
    }
    s.login?.let { req ->
        ui.data.myServers.firstOrNull { it.id == req.serverId }?.let { srv ->
            LoginDialog(req.title.removeSuffix("…"), srv.sshUser, srv.host, srv.sshPort, req.error, rememberDefault = true, editUser = false,
                onCancel = { m.cancelLogin() }) { t, remember -> m.provideLogin(t, remember) }
        }
    }
    s.shown?.let { (name, conf) ->
        val isConf = "[Interface]" in conf
        AlertDialog(
            onDismissRequest = { m.hideShown() },
            title = { Text("Подключение «$name»") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (isConf) "Файл настроек AmneziaWG. Hydra добавит его сама; в официальное приложение AmneziaWG — скопируйте текст или сохраните как .conf."
                        else "Ссылка для подключения. Hydra добавит её сама; в другое приложение — скопируйте.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Это ключ доступа: не публикуйте его.", fontSize = 12.sp, color = Danger)
                    SelectionContainer { Text(conf, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { setClipboard(conf); c.toast("Скопировано") }) { Text("Копировать") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { c.import(conf); m.hideShown() }) { Text("Добавить в «Серверы»") }
                    TextButton(onClick = { m.hideShown() }) { Text("Закрыть") }
                }
            },
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

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Новый сервер", style = MaterialTheme.typography.titleMedium)
            if (s.busy && s.opServer == null) Busy(s.progress)
            when (s.stage) {
                Stage.IDLE, Stage.FAILED -> {
                    s.error?.takeIf { s.opServer == null }?.let { ErrorBox(it) }
                    Text("Шаг 1 из 2. Данные из письма хостинга: адрес сервера и пароль root (или SSH-ключ). Что поставить на сервер, выберете после входа.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(host, { host = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text("Адрес сервера (IP или имя)") },
                            isError = host.isNotBlank() && !MyServersManager.validHost(host))
                        OutlinedTextField(name, { name = it.take(40) }, Modifier.weight(1f), singleLine = true, label = { Text("Название (необязательно)") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(sshPort, { sshPort = it.filter(Char::isDigit).take(5) }, Modifier.width(110.dp), singleLine = true, label = { Text("Порт SSH") })
                        OutlinedTextField(user, { user = it.trim() }, Modifier.width(140.dp), singleLine = true, label = { Text("Пользователь") })
                        OutlinedTextField(password, { password = it }, Modifier.weight(1f), singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), label = { Text("Пароль") })
                    }
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().height(90.dp), label = { Text("Или закрытый SSH-ключ (текст файла id_ed25519 / id_rsa) — вместо пароля") })
                    if (key.isNotBlank()) OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), label = { Text("Пароль к ключу (если есть)") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(remember, { remember = it })
                        Text("Запомнить данные входа (зашифрованно, только на этом компьютере)", fontSize = 13.sp)
                    }
                    val port = sshPort.toIntOrNull() ?: 0
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !s.busy && MyServersManager.validHost(host) && port in 1..65535 && (password.isNotEmpty() || key.isNotBlank()),
                            onClick = {
                                m.begin(ServerForm(name.ifBlank { host }, SshTarget(host, port, user.ifBlank { "root" },
                                    password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() }, passphrase.takeIf { key.isNotBlank() && it.isNotEmpty() }), remember))
                            },
                        ) { Text("Подключиться") }
                        TextButton(onClick = onClose) { Text("Закрыть") }
                    }
                }
                Stage.CONFIRM_FINGERPRINT -> {
                    Text("Шаг 2 из 2. Это «отпечаток» сервера — его уникальный признак. Если хостинг показывает отпечаток в панели, сверьте; " +
                        "дальше Hydra будет входить только на сервер с этим отпечатком.", fontSize = 13.sp)
                    SelectionContainer { Text(s.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                    if (s.previousFingerprint.isNotBlank()) {
                        Text("⚠ Отпечаток этого сервера ИЗМЕНИЛСЯ. Раньше был: ${s.previousFingerprint}. Доверяйте, только если вы переустанавливали сервер " +
                            "или меняли его SSH-ключи, — иначе соединение могут перехватывать.", color = Danger, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { m.confirmFingerprint() }, enabled = !s.busy) { Text("Доверять и войти") }
                        TextButton(onClick = onClose) { Text("Отмена") }
                    }
                }
                Stage.DONE -> {
                    Text(s.progress, color = Ok, fontWeight = FontWeight.SemiBold)
                    Text("Сервер появился в списке ниже. Выберите на его карточке, что установить: AmneziaWG (рекомендуем) или узел OpenFlux.", fontSize = 13.sp)
                    Button(onClick = onClose) { Text("Готово") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Карточка сервера

@Composable
private fun ServerCard(c: AppController, srv: MyServer, m: MyServersManager, s: MyServersUi, onAddClient: () -> Unit, onAddUser: () -> Unit, onLogin: () -> Unit, onConfirm: (Confirm) -> Unit) {
    val st = s.status[srv.id]
    val mine = s.opServer == srv.id
    val busy = s.busy
    var installKind by remember { mutableStateOf<ServerKind?>(null) }
    var rename by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(srv.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(st)
            }
            Text("${srv.sshUser}@${srv.host}:${srv.sshPort}" + listOf(st?.osName ?: srv.osName, st?.arch ?: srv.arch).filter { it.isNotBlank() }.joinToString(" · ", " · "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            st?.takeIf { it.error == null && it.uptimeSec > 0 }?.let {
                Text("Работает ${uptime(it.uptimeSec)} · нагрузка ${it.load} · память ${it.mem} · диск ${it.disk}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (srv.id in s.saved) "Данные входа сохранены (зашифрованно)" else "Данные входа не сохранены — Hydra спросит пароль при действии",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipRow {
                OutlinedButton(onClick = { m.refresh(srv.id) }, enabled = !busy) { Text("Проверить") }
                OutlinedButton(onClick = onLogin, enabled = !busy) { Text("Данные входа") }
                OutlinedButton(onClick = { rename = true }, enabled = !busy) { Text("Переименовать") }
                OutlinedButton(onClick = {
                    onConfirm(Confirm("Перезагрузить «${srv.name}»?", "Сервер будет недоступен 1–2 минуты; VPN на нём поднимется сам.", "Перезагрузить") { m.reboot(srv.id) })
                }, enabled = !busy) { Text("Перезагрузить") }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            // нода чужой панели (0.8.2-a4): службы на ней ставит и снимает главная панель
            srv.nodeOf?.let { r ->
                Text("Нода панели ${r.kind.title} сервера «${r.mainName}»", fontWeight = FontWeight.Medium)
                Text("Подключения этой панели работают и через этот сервер. Убрать ноду — на карточке «${r.mainName}», в разделе «Ноды».",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (srv.nodeOf == null) {
            AwgSection(srv, st, m, busy, onAddClient, onConfirm) { installKind = ServerKind.AMNEZIAWG }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            OfxSection(srv, st, m, busy, onConfirm) { installKind = ServerKind.OPENFLUX }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            PanelSection(c, srv, st, m, s, onAddUser, onConfirm) { k -> installKind = k }
            }

            val inst = s.install?.takeIf { it.serverId == srv.id }
            if (installKind != null && inst == null) InstallForm(installKind!!, busy, onCancel = { installKind = null }) { f -> m.prepareInstall(srv.id, f) }
            if (inst != null) InstallPanel(inst, s, m) { installKind = null; m.closeInstall() }

            if (mine && busy && inst == null) Busy(s.progress)
            if (mine && s.log.isNotEmpty() && inst == null) LogBox(s.log)
            if (mine && s.error != null && inst == null) ErrorBox(s.error!!)

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ChipRow {
                TextButton(onClick = {
                    onConfirm(Confirm("Очистить сервер «${srv.name}»?",
                        "С сервера ${srv.host} будет удалено всё, что ставила Hydra: AmneziaWG, узел OpenFlux, панель 3x-ui, их настройки, правила файрвола и копии ключей. " +
                            "Подключения к этому серверу в «Серверах» перестанут работать. Чужие программы не трогаются. Сервер останется в списке — на него можно поставить всё заново.",
                        "Очистить") { m.wipe(srv.id) })
                }, enabled = !busy) { Text("Очистить сервер полностью", color = Danger) }
                TextButton(onClick = {
                    onConfirm(Confirm("Убрать «${srv.name}» из списка?",
                        "Запись и сохранённые данные входа будут удалены из приложения. На самом сервере ничего не изменится — чтобы удалить VPN с сервера, сначала нажмите «Очистить сервер полностью».",
                        "Убрать") { m.forget(srv.id) })
                }, enabled = !busy) { Text("Убрать из списка") }
            }
        }
    }
    if (rename) {
        var n by remember { mutableStateOf(srv.name) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Название сервера") },
            text = { OutlinedTextField(n, { n = it.take(40) }, singleLine = true) },
            confirmButton = { TextButton(enabled = n.isNotBlank(), onClick = { rename = false; m.rename(srv.id, n) }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun AwgSection(srv: MyServer, st: ServerStatus?, m: MyServersManager, busy: Boolean, onAddClient: () -> Unit, onConfirm: (Confirm) -> Unit, onInstall: () -> Unit) {
    val awg = srv.awg
    Text("AmneziaWG", fontWeight = FontWeight.SemiBold)
    if (awg == null) {
        Text("Не установлен. Самый простой путь к своему VPN — подойдёт большинству.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onInstall, enabled = !busy) { Text("Установить AmneziaWG") }
        return
    }
    val state = st?.takeIf { it.error == null }?.let { if (it.awgActive) "работает" else "служба: ${it.awgState.ifBlank { "не запущена" }}" } ?: "состояние не проверено"
    Text("Версия ${awg.generation.label} · UDP ${awg.port} · $state", fontSize = 12.sp,
        color = if (st?.awgActive == true) Ok else MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Устройства:", fontSize = 13.sp)
    srv.clients.forEach { cl ->
        val peer = st?.awgPeers?.firstOrNull { it.publicKey == cl.publicKey }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(320.dp)) {
                Text("${cl.name} · ${cl.address.substringBefore('/')}", fontSize = 13.sp)
                peer?.let { Text(peerLine(it.latestHandshake, it.rx, it.tx), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (cl.privateKey.isEmpty()) Text("создано на другом устройстве — конфиг там же", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (cl.privateKey.isNotEmpty()) OutlinedButton(onClick = { m.clientConf(srv.id, cl.name)?.let { conf -> m.showConf(cl.name, conf) } }) { Text("Конфиг") }
            TextButton(onClick = {
                onConfirm(Confirm("Удалить устройство «${cl.name}»?", "Это устройство больше не сможет подключиться к серверу. Остальные продолжат работать.", "Удалить") { m.removeClient(srv.id, cl.name) })
            }, enabled = !busy && srv.clients.size > 1) { Text("Удалить", color = Danger) }
        }
    }
    ChipRow {
        OutlinedButton(onClick = onAddClient, enabled = !busy) { Text("Добавить устройство") }
        OutlinedButton(onClick = { m.restartService(srv.id, ServerKind.AMNEZIAWG) }, enabled = !busy) { Text("Перезапустить") }
        TextButton(onClick = {
            onConfirm(Confirm("Удалить AmneziaWG с «${srv.name}»?",
                "Служба, её настройки и программы будут удалены с сервера ${srv.host}; порт ${awg.port}/udp закроется, если его открывала Hydra. Все устройства этого VPN перестанут подключаться.",
                "Удалить") { m.uninstallService(srv.id, ServerKind.AMNEZIAWG) })
        }, enabled = !busy) { Text("Удалить AmneziaWG", color = Danger) }
    }
}

@Composable
private fun OfxSection(srv: MyServer, st: ServerStatus?, m: MyServersManager, busy: Boolean, onConfirm: (Confirm) -> Unit, onInstall: () -> Unit) {
    val ofx = srv.ofx
    Text("Узел OpenFlux", fontWeight = FontWeight.SemiBold)
    if (ofx == null) {
        Text("Не установлен. Для опытных: обход блокировок через сервисы вроде Яндекс Документов.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onInstall, enabled = !busy) { Text("Установить узел OpenFlux") }
        return
    }
    val state = st?.takeIf { it.error == null }?.ofxChannels?.get(ofx.channel)
    Text("Канал «${ofx.channel}» · TCP ${ofx.port}" + (state?.let { " · служба: $it" } ?: ""), fontSize = 12.sp,
        color = if (state == "active") Ok else MaterialTheme.colorScheme.onSurfaceVariant)
    ChipRow {
        OutlinedButton(onClick = { m.ofxLink(srv.id)?.let { m.showConf(srv.name, it) } }) { Text("Ссылка openflux://") }
        OutlinedButton(onClick = { m.restartService(srv.id, ServerKind.OPENFLUX) }, enabled = !busy) { Text("Перезапустить") }
        TextButton(onClick = {
            onConfirm(Confirm("Удалить узел OpenFlux с «${srv.name}»?", "Канал «${ofx.channel}», его служба и (если других каналов нет) ядро узла будут удалены. Ссылки на этот узел перестанут работать.",
                "Удалить") { m.uninstallService(srv.id, ServerKind.OPENFLUX) })
        }, enabled = !busy) { Text("Удалить узел", color = Danger) }
    }
}

/** Панель 3x-ui на сервере, управляемом по SSH (0.8.1): сайт панели, пользователи, перезапуск, удаление. */
@Composable
private fun PanelSection(c: AppController, srv: MyServer, st: ServerStatus?, m: MyServersManager, s: MyServersUi, onAddUser: () -> Unit, onConfirm: (Confirm) -> Unit, onInstall: (ServerKind) -> Unit) {
    val p = srv.panel
    val busy = s.busy
    var pickPanel by remember { mutableStateOf(false) }
    Text(p?.let { "Панель ${it.kind.title}" } ?: "Панель управления", fontWeight = FontWeight.SemiBold)
    if (p == null) {
        Text("Не установлена. Сайт-панель, где вы сами заводите пользователей и выдаёте им ссылки — удобно, если VPN нужен нескольким людям. " +
            "3x-ui Hydra ведёт сама (пользователи и ссылки прямо здесь); в s-ui, x-ui, PasarGuard и Remnawave Hydra создаёт подключения, остальное — на их сайте.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val foreign = (st?.xuiInstalled == true && !st.xuiByHydra && !st.xuiAlirezaByHydra) || (st?.suiInstalled == true && !st.suiByHydra)
        if (foreign) Text("На сервере уже стоит панель, поставленная не Hydra: 3x-ui подключите кнопкой «У меня уже есть панель» (адрес и токен).",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow {
            if (st?.xuiInstalled != true) OutlinedButton(onClick = { onInstall(ServerKind.PANEL_3XUI) }, enabled = !busy) { Text("Установить 3x-ui (рекомендуем)") }
            if (st?.suiInstalled != true) OutlinedButton(onClick = { onInstall(ServerKind.PANEL_SUI) }, enabled = !busy) { Text("Установить s-ui") }
            if (st?.xuiInstalled != true) OutlinedButton(onClick = { onInstall(ServerKind.PANEL_XUI) }, enabled = !busy) { Text("Установить x-ui") }
            if (st?.pgInstalled != true) OutlinedButton(onClick = { onInstall(ServerKind.PANEL_PASARGUARD) }, enabled = !busy) { Text("Установить PasarGuard") }
            if (st?.rwInstalled != true) OutlinedButton(onClick = { onInstall(ServerKind.PANEL_REMNAWAVE) }, enabled = !busy) { Text("Установить Remnawave") }
            if (!srv.hasServices) OutlinedButton(onClick = { pickPanel = true }, enabled = !busy) { Text("Установить ноду") }
        }
        if (pickPanel) {
            val panels = m.nodePanels(srv.id)
            AlertDialog(onDismissRequest = { pickPanel = false },
                title = { Text("Нода на сервере «${srv.name}»") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (panels.isEmpty()) Text("Сначала поставьте панель 3x-ui, PasarGuard или Remnawave на другом сервере из «Мои серверы» — нода подключается к ней.", fontSize = 13.sp)
                        else {
                            Text("Выберите панель, к которой подключить этот сервер. Hydra поставит на него всё нужное; порты ноды откроются только для сервера панели. Займёт 2–5 минут.", fontSize = 13.sp)
                            panels.forEach { main ->
                                OutlinedButton(onClick = { pickPanel = false; m.installNode(srv.id, main.id) }) { Text("${main.panel?.kind?.title} · ${main.name} (${main.host})") }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pickPanel = false }) { Text("Отмена") } })
        }
        return
    }
    val kind = ServerKind.ofPanel(p.kind)
    val state = st?.takeIf { it.error == null }?.xuiState?.takeIf { it.isNotBlank() }
    Text("Версия ${p.version.ifBlank { "—" }}" + (state?.let { " · служба: $it" } ?: ""), fontSize = 12.sp,
        color = if (state == "active") Ok else MaterialTheme.colorScheme.onSurfaceVariant)
    PanelBody(c, srv, m, s, onAddUser)
    PanelBackupsSection(srv, m, s)
    ChipRow {
        OutlinedButton(onClick = { m.restartService(srv.id, kind) }, enabled = !busy) { Text("Перезапустить") }
        TextButton(onClick = {
            onConfirm(Confirm("Удалить панель ${p.kind.title} с «${srv.name}»?", "Панель, её ядро и все пользователи панели будут удалены с сервера. Ссылки, выданные панелью, перестанут работать. " +
                    "Копия базы панели (пользователи, настройки) останется на сервере в /root/hydra-backups.",
                "Удалить") { m.uninstallService(srv.id, kind) })
        }, enabled = !busy) { Text("Удалить панель", color = Danger) }
    }
}

/** Общее для панели на своём сервере и подключённой готовой: адрес, вход на сайт, пользователи. */
@Composable
private fun PanelBody(c: AppController, srv: MyServer, m: MyServersManager, s: MyServersUi, onAddUser: () -> Unit) {
    val p = srv.panel ?: return
    val busy = s.busy
    val ssh = srv.fingerprint.isNotBlank()
    val local = p.local && ssh
    val tunnel = s.tunnels[srv.id]
    var openWhenReady by remember { mutableStateOf(false) }
    // сайт панели внутри Hydra (0.8.2-a2): адрес открытого окна; в браузере — как раньше
    var inApp by remember { mutableStateOf<String?>(null) }
    var viaBrowser by remember { mutableStateOf(false) }
    var askLocal by remember { mutableStateOf<Boolean?>(null) }
    var createConn by remember { mutableStateOf(false) }
    val browser = { url: String ->
        if (p.password.isNotBlank()) { copySecret(p.password); c.toast("Пароль скопирован на 30 секунд — вставьте его на странице входа") }
        openUrl(url)
    }
    val open = { url: String ->
        if (!viaBrowser && PanelWebFx.available && PanelWebFx.start()) {
            if (url.contains("://127.0.0.1:")) runCatching { java.net.URL(url).port }.getOrNull()?.let { PanelWebFx.tunnelPorts += it }
            inApp = url
        } else browser(url)
        viaBrowser = false
    }
    // закрытая извне панель (0.8.1-rc2): сайт — через SSH-туннель; адрес туннеля приходит после подключения к серверу
    LaunchedEffect(tunnel, openWhenReady) { if (openWhenReady && tunnel != null) { openWhenReady = false; open(tunnel) } }
    SelectionContainer { Text("Адрес: ${p.baseUrl}" + (if (p.username.isNotBlank()) "\nЛогин: ${p.username}" else ""), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    if (local) Text("Панель закрыта извне: её сайт открывается только через Hydra — по защищённому туннелю к серверу." +
        if (XuiPanel.isHttps(p.baseUrl)) " Браузер предупредит о сертификате: он выписан на IP сервера, а туннель открыт на 127.0.0.1 — это ожидаемо, соединение защищено SSH." else "",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
    if (tunnel != null) Text("Туннель открыт: $tunnel", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    // PasarGuard слушает только 127.0.0.1 сервера: http:// идёт внутри SSH-туннеля — предупреждение не нужно
    if (!XuiPanel.isHttps(p.baseUrl) && !p.kind.tunnelOnly) {
        Text("Панель открывается по http:// — пароль на сайте идёт без шифрования, а провайдер может обрывать такие страницы. " +
            "Включите HTTPS (бесплатный сертификат Let's Encrypt на IP сервера) или входите при включённом VPN.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // сертификат ставится по SSH — только у панели на своём сервере (у подключённой по токену SSH нет); с rc2 — у всех трёх
        if (ssh) OutlinedButton(onClick = { m.enablePanelHttps(srv.id) }, enabled = !busy) { Text("Включить HTTPS") }
    }
    ChipRow {
        Button(onClick = {
            when {
                !local -> open(p.baseUrl)
                tunnel != null -> open(tunnel)
                else -> { openWhenReady = true; m.openPanelTunnel(srv.id) }
            }
        }, enabled = !(local && tunnel == null && busy)) { Text("Открыть панель") }
        OutlinedButton(onClick = {
            viaBrowser = true
            when {
                !local -> open(p.baseUrl)
                tunnel != null -> open(tunnel)
                else -> { openWhenReady = true; m.openPanelTunnel(srv.id) }
            }
        }, enabled = !(local && tunnel == null && busy)) { Text("В браузере") }
        if (p.password.isNotBlank()) OutlinedButton(onClick = { copySecret(p.password); c.toast("Пароль скопирован на 30 секунд") }) { Text("Скопировать пароль") }
        if (tunnel != null) OutlinedButton(onClick = { m.closePanelTunnel(srv.id) }) { Text("Закрыть туннель") }
        if (ssh && !p.kind.tunnelOnly) OutlinedButton(onClick = { askLocal = !p.local }, enabled = !busy) { Text(if (p.local) "Открыть панель извне" else "Закрыть панель извне") }
        // пользователи через API: 3x-ui (токен), с rc2 — s-ui и x-ui (по SSH); у остальных — на сайте панели
        if (ru.gidravpn.hydra.servers.OtherPanelUsers.supported(srv)) {
            OutlinedButton(onClick = { m.loadPanelClients(srv.id) }, enabled = !busy) { Text("Пользователи") }
            OutlinedButton(onClick = onAddUser, enabled = !busy) { Text("Добавить пользователя") }
        }
        if (ru.gidravpn.hydra.servers.OtherPanelConnections.canCreate(srv)) OutlinedButton(onClick = { createConn = true }, enabled = !busy) { Text("Создать подключение") }
    }
    if (!ru.gidravpn.hydra.servers.OtherPanelUsers.supported(srv)) Text("Пользователей и подключения заводите на сайте панели; ссылку подключения из панели можно вставить в «Серверы».",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    inApp?.let { url ->
        PanelWebWindow(p, srv.name, url, onFailed = { if (p.password.isNotBlank()) copySecret(p.password) }) {
            inApp = null
            if (url.contains("://127.0.0.1:")) m.closePanelTunnel(srv.id)
        }
    }
    if (createConn) {
        var type by remember { mutableStateOf(ru.gidravpn.hydra.servers.XuiConnection.VLESS_GRPC_REALITY) }
        var port by remember { mutableStateOf((20000 + java.security.SecureRandom().nextInt(40000)).toString()) }
        var mask by remember { mutableStateOf(XuiPanel.RealityMask.VK) }
        val https = XuiPanel.isHttps(p.baseUrl)
        AlertDialog(onDismissRequest = { createConn = false },
            title = { Text("Новое подключение в панели") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Hydra создаст его в панели и подключит к основному пользователю панели — его подписка в «Серверах» получит подключение сама.", fontSize = 13.sp)
                    ChipRow { ru.gidravpn.hydra.servers.OtherPanelConnections.typesFor(p.kind).forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.title) }) } }
                    if (type.reality) ChipRow {
                        Text("Маскировка:", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        XuiPanel.RealityMask.entries.forEach { r -> FilterChip(selected = mask == r, onClick = { mask = r }, label = { Text(r.sni) }) }
                    }
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text("Порт") })
                    if (type.udp) Text("Работает по UDP — порт откроется как UDP. Часть мобильных сетей в РФ режет UDP: тогда выберите VLESS/Trojan + REALITY.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (type.needsCert && !https) Text("Нужен сертификат сервера: сначала нажмите «Включить HTTPS» у панели.", fontSize = 12.sp, color = Danger)
                }
            },
            confirmButton = { TextButton(enabled = (port.toIntOrNull() ?: 0) in 1..65535 && (!type.needsCert || https),
                onClick = { createConn = false; m.createPanelConnection(srv.id, type, port.toInt(), mask) }) { Text("Создать") } },
            dismissButton = { TextButton(onClick = { createConn = false }) { Text("Отмена") } })
    }
    askLocal?.let { close ->
        AlertDialog(onDismissRequest = { askLocal = null },
            title = { Text(if (close) "Закрыть панель извне?" else "Открыть панель извне?") },
            text = { Text(if (close) "Сайт панели перестанет открываться из интернета: Hydra будет открывать его через защищённое подключение к серверу. VPN-подключения пользователей работают как прежде."
                else "Сайт панели снова будет открываться по своему адресу из интернета. Лучше включить HTTPS.") },
            confirmButton = { TextButton(onClick = { askLocal = null; m.setPanelLocal(srv.id, close) }) { Text(if (close) "Закрыть" else "Открыть") } },
            dismissButton = { TextButton(onClick = { askLocal = null }) { Text("Отмена") } })
    }
    if (ssh && ru.gidravpn.hydra.servers.PanelNodes.supports(p.kind)) PanelNodesSection(srv, m, s)
    s.panelClients[srv.id]?.let { emails ->
        if (emails.isEmpty()) Text("Пользователей пока нет.", fontSize = 12.sp)
        emails.forEach { email ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("• $email", Modifier.width(200.dp), fontSize = 13.sp)
                OutlinedButton(onClick = { m.showPanelLinks(srv.id, email) }, enabled = !busy) { Text("Ссылка") }
                TextButton(onClick = { m.removePanelClient(srv.id, email) }, enabled = !busy) { Text("Удалить", color = Danger) }
            }
        }
    }
}

/**
 * Ноды панели (0.8.2-a4): список, «Добавить ноду» (второй сервер из «Моих серверов»), «Убрать». Подключения Hydra работают и на
 * ноде; у 3x-ui на новой ноде Hydra сразу создаёт подключение VLESS + gRPC + REALITY.
 */
@Composable
private fun PanelNodesSection(srv: MyServer, m: MyServersManager, s: MyServersUi) {
    val p = srv.panel ?: return
    var pick by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<ru.gidravpn.hydra.servers.PanelNode?>(null) }
    Text("Ноды", fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    if (p.nodes.isEmpty()) Text("Нод пока нет. Нода — второй сервер, на котором работают подключения этой панели: пользователи получают и его адрес.",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    p.nodes.forEach { n ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("• ${n.name} — ${n.host}", Modifier.width(260.dp), fontSize = 13.sp)
            TextButton(onClick = { remove = n }, enabled = !s.busy) { Text("Убрать", color = Danger) }
        }
    }
    OutlinedButton(onClick = { pick = true }, enabled = !s.busy) { Text("Добавить ноду") }
    if (pick) {
        val list = m.nodeCandidates(srv.id)
        AlertDialog(onDismissRequest = { pick = false },
            title = { Text("Нода для панели ${p.kind.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (list.isEmpty()) Text("Нет свободного сервера: добавьте второй сервер в «Мои серверы» (кнопка «Добавить сервер») — на нём не должно быть служб Hydra.", fontSize = 13.sp)
                    else {
                        Text("Hydra поставит на выбранный сервер всё нужное для ноды и подключит её к панели. Порты ноды откроются только для этого сервера. Займёт 2–5 минут.", fontSize = 13.sp)
                        ru.gidravpn.hydra.servers.PanelNodes.planPreview(p.kind, srv.name).forEach { Text("• ${it.title}", fontSize = 12.sp) }
                        list.forEach { cand -> OutlinedButton(onClick = { pick = false; m.addPanelNode(srv.id, cand.id) }) { Text("${cand.name} (${cand.host})") } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pick = false }) { Text("Отмена") } })
    }
    remove?.let { n ->
        AlertDialog(onDismissRequest = { remove = null },
            title = { Text("Убрать ноду «${n.name}»?") },
            text = { Text("Нода будет убрана из панели, а с сервера ${n.host} — всё, что Hydra ставила для неё. Подключения через этот сервер перестанут работать.") },
            confirmButton = { TextButton(onClick = { remove = null; m.removePanelNode(srv.id, n.serverId) }) { Text("Убрать", color = Danger) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Отмена") } })
    }
}

/**
 * Восстановление базы панели из копии (0.8.2-rc2, A3): копии лежат в `/root/hydra-backups` (Hydra делает их перед удалением и
 * очисткой сервера). Выбор копии → подтверждение → Hydra проверяет копию, сохраняет текущую базу и подменяет её.
 */
@Composable
private fun PanelBackupsSection(srv: MyServer, m: MyServersManager, s: MyServersUi) {
    val p = srv.panel ?: return
    if (srv.fingerprint.isBlank() || !ru.gidravpn.hydra.servers.PanelBackups.supports(p.kind)) return
    var open by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<ru.gidravpn.hydra.servers.PanelBackups.Backup?>(null) }
    OutlinedButton(onClick = { open = true; m.loadBackups(srv.id) }, enabled = !s.busy) { Text("Восстановить из копии") }
    if (open) {
        val list = s.backups[srv.id]
        AlertDialog(onDismissRequest = { open = false },
            title = { Text("Копии базы панели ${p.kind.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        list == null -> Text("Ищу копии на сервере…", fontSize = 13.sp)
                        list.isEmpty() -> Text("Копий нет. Их делает Hydra перед удалением панели и очисткой сервера — они лежат в /root/hydra-backups.", fontSize = 13.sp)
                        else -> {
                            Text("Пользователи и настройки панели станут такими, какими были в копии. Текущая база сохранится рядом и вернётся, если панель не запустится.", fontSize = 13.sp)
                            list.forEach { b -> OutlinedButton(onClick = { open = false; pick = b }) { Text(b.title) } }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть") } })
    }
    pick?.let { b ->
        AlertDialog(onDismissRequest = { pick = null },
            title = { Text("Восстановить базу из копии от ${b.title}?") },
            text = { Text("Панель ненадолго остановится. Всё, что добавлено в неё после этой копии (пользователи, подключения), пропадёт.") },
            confirmButton = { TextButton(onClick = { pick = null; m.restoreBackup(srv.id, b.file) }) { Text("Восстановить", color = Danger) } },
            dismissButton = { TextButton(onClick = { pick = null }) { Text("Отмена") } })
    }
}

/** Готовая панель, подключённая по адресу и токену (без SSH): только API панели. */
@Composable
private fun PanelOnlyCard(c: AppController, srv: MyServer, m: MyServersManager, s: MyServersUi, onAddUser: () -> Unit, onConfirm: (Confirm) -> Unit) {
    val st = s.status[srv.id]
    val mine = s.opServer == srv.id
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(srv.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(st)
            }
            Text("Панель 3x-ui, подключена по токену (без входа на сервер)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PanelBody(c, srv, m, s, onAddUser)
            if (mine && s.busy) Busy(s.progress.ifBlank { "Работаю…" })
            if (mine && s.error != null) ErrorBox(s.error!!)
            ChipRow {
                OutlinedButton(onClick = { m.refresh(srv.id) }, enabled = !s.busy) { Text("Проверить") }
                TextButton(onClick = {
                    onConfirm(Confirm("Убрать «${srv.name}» из списка?", "Hydra забудет адрес и токен панели. Сама панель и её пользователи не изменятся.", "Убрать") { m.forget(srv.id) })
                }, enabled = !s.busy) { Text("Убрать из списка") }
            }
        }
    }
}

/** «У меня уже есть панель» (0.8.1-a): адрес и токен доступа 3x-ui — без установки и без входа на сервер. */
@Composable
private fun ConnectPanelDialog(onClose: () -> Unit, onOk: (name: String, address: String, token: String) -> Unit) {
    var name by remember { mutableStateOf("Моя панель") }
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val plain = address.isNotBlank() && !XuiPanel.isHttps(XuiPanel.normalizeUrl(address).orEmpty())
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Подключить свою панель 3x-ui") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Если панель 3x-ui уже стоит на вашем сервере, Hydra подключится к ней и сможет заводить пользователей и выдавать ссылки. Устанавливать ничего не нужно.", fontSize = 13.sp)
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it }, singleLine = true, label = { Text("Адрес панели (как в браузере)") },
                    placeholder = { Text("http://203.0.113.5:2053/путь") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("Токен доступа (API)") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("Где взять токен: на сервере команда  x-ui setting -getApiToken  (или файл /etc/x-ui/install-result.env, строка XUI_API_TOKEN).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (plain) Text("Адрес начинается с http:// — токен пойдёт без шифрования. Подключайтесь при включённом VPN или используйте https://.", fontSize = 12.sp, color = Danger)
            }
        },
        confirmButton = { TextButton(enabled = address.isNotBlank() && token.isNotBlank(), onClick = { onOk(name, address, token) }) { Text("Подключить") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

private const val MASK_RU = "Сайт из «белых списков»: подключение пройдёт и тогда, когда мобильный оператор пускает только разрешённые сайты. " +
    "Минус: российский сайт на зарубежном IP — заметный признак для ТСПУ."
private const val MASK_FOREIGN = "Зарубежный сайт без своих узлов в России: адрес сервера не спорит с маскировкой. Минус: не пройдёт при «белых списках» мобильных операторов."

/** Пароль панели в буфер на 30 секунд: потом очищается, если там всё ещё он. */
private fun copySecret(text: String) {
    setClipboard(text)
    kotlin.concurrent.thread(isDaemon = true, name = "clipboard-clear") {
        Thread.sleep(30_000)
        if (runCatching { clipboard() }.getOrNull() == text) setClipboard("")
    }
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
    var sslIp by remember { mutableStateOf(true) }
    var mask by remember { mutableStateOf(XuiPanel.RealityMask.VK) }
    var transport by remember { mutableStateOf(XuiPanel.Transport.GRPC) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (kind) { ServerKind.AMNEZIAWG -> "Установка AmneziaWG"; ServerKind.OPENFLUX -> "Установка узла OpenFlux"; else -> "Установка панели ${kind.panel!!.title}" }, fontWeight = FontWeight.SemiBold)
            if (kind == ServerKind.PANEL_SUI || kind == ServerKind.PANEL_XUI) {
                Text("Hydra поставит последнюю версию ${kind.panel!!.title} официальным установщиком и задаст надёжный логин, пароль и случайный адрес панели. Пользователей вы заведёте на сайте панели.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sslIp, { sslIp = it }); Text("HTTPS для панели: бесплатный сертификат Let's Encrypt на IP (нужен открытый 80/tcp; не выйдет — панель останется на HTTP)", fontSize = 13.sp)
                }
            }
            if (kind == ServerKind.PANEL_REMNAWAVE) {
                Text("Hydra поставит Docker (если его нет), панель Remnawave закреплённой версии с базой и кэшем, небольшой прокси для её сайта и ноду, создаст владельца с надёжным логином и паролем. " +
                    "Панель будет видна только через защищённый туннель Hydra. Подключения (VLESS / Trojan + REALITY) — кнопкой «Создать подключение». Установка занимает 3–10 минут.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == ServerKind.PANEL_PASARGUARD) {
                Text("Hydra поставит Docker (если его нет), панель PasarGuard и её ноду официальными установщиками закреплённой версии, создаст владельца с надёжным логином и паролем. " +
                    "Панель будет видна только через защищённый туннель Hydra. Подключения (VLESS / Trojan + REALITY, VMess) — кнопкой «Создать подключение». Установка занимает 3–10 минут.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == ServerKind.PANEL_3XUI) {
                Text("Hydra поставит последнюю версию 3x-ui официальным установщиком, придумает надёжный логин и пароль, создаст подключение " +
                    "VLESS + gRPC + Reality и первого пользователя, а в «Серверы» добавит его подписку — правки на сайте панели будут приходить сами.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(accessPort, { accessPort = it.filter(Char::isDigit).take(5) }, Modifier.width(220.dp), singleLine = true, label = { Text("Порт подключения (TCP)") })
                ChipRow {
                    Text("Транспорт:", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    XuiPanel.Transport.entries.forEach { t -> FilterChip(selected = transport == t, onClick = { transport = t }, label = { Text(if (t == XuiPanel.Transport.GRPC) "${t.title} (рекомендуем)" else t.title) }) }
                }
                ChipRow {
                    Text("Маскировка:", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    XuiPanel.RealityMask.entries.forEach { r -> FilterChip(selected = mask == r, onClick = { mask = r }, label = { Text(r.sni) }) }
                }
                Text(if (mask == XuiPanel.RealityMask.GITHUB) MASK_FOREIGN else MASK_RU, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sslIp, { sslIp = it }); Text("HTTPS для панели и подписки: бесплатный сертификат Let's Encrypt на IP (нужен открытый 80/tcp)", fontSize = 13.sp)
                }
            } else if (kind == ServerKind.AMNEZIAWG) {
                ChipRow {
                    Text("Версия:", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    AwgGeneration.entries.forEach { g -> FilterChip(selected = gen == g, onClick = { gen = g }, label = { Text(if (g == AwgGeneration.V2_0) "${g.label} (рекомендуем)" else g.label) }) }
                }
                Text(genHint(gen), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(awgPort, { awgPort = it.filter(Char::isDigit).take(5) }, Modifier.width(160.dp), singleLine = true, label = { Text("UDP-порт") })
            } else if (kind == ServerKind.OPENFLUX) {
                Text("Узел ставится официальным скриптом OpenFlux закреплённой версии (с проверкой SHA-256); порт выберет скрипт. Транспорты Яндекс / Mail.ru — по желанию.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(channel, { channel = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(31) }, Modifier.width(260.dp), singleLine = true, label = { Text("Имя канала (a-z, 0-9, дефис)") })
                OutlinedTextField(yandex, { yandex = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Документ Яндекса https://docs.yandex.ru/edit/d/… (необязательно)") })
                OutlinedTextField(mailru, { mailru = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Публичный документ Mail.ru https://cloud.mail.ru/public/… (необязательно)") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(openFw, { openFw = it }); Text("Открыть порт в файрволе сервера (ufw/firewalld), если он включён", fontSize = 13.sp)
            }
            val aport = awgPort.toIntOrNull() ?: 0
            val ok = when (kind) {
                ServerKind.AMNEZIAWG -> aport in 1024..65535
                ServerKind.OPENFLUX -> OfxNodeSpec.validChannel(channel)
                ServerKind.PANEL_3XUI -> (accessPort.toIntOrNull() ?: 0) in 1..65535
                ServerKind.PANEL_SUI, ServerKind.PANEL_XUI, ServerKind.PANEL_PASARGUARD, ServerKind.PANEL_REMNAWAVE -> true
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = ok && !busy, onClick = { onNext(ServiceForm(kind, gen, aport, openFw, channel, yandex, mailru, accessPort = accessPort.toIntOrNull() ?: 443, sslIp = sslIp, mask = mask, transport = transport)) }) { Text("Проверить сервер и показать план") }
                TextButton(onClick = onCancel) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun InstallPanel(i: ru.gidravpn.hydra.servers.InstallUi, s: MyServersUi, m: MyServersManager, onClose: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when (i.form.kind) {
                ServerKind.AMNEZIAWG -> "Установка AmneziaWG ${i.form.generation.label}"
                ServerKind.OPENFLUX -> "Установка узла OpenFlux «${i.form.channel}»"
                else -> "Установка панели ${i.form.kind.panel!!.title}"
            }, fontWeight = FontWeight.SemiBold)
            when (i.stage) {
                InstallStage.CHECKING -> Busy(s.progress.ifBlank { "Проверяю сервер…" })
                InstallStage.READY -> {
                    i.preflight?.let { pre ->
                        FriendlyText.checklist(pre).forEach { item ->
                            Text((if (item.ok) "✓ " else "✗ ") + item.title, color = if (item.ok) Ok else Danger, fontSize = 13.sp)
                            if (!item.ok) Text(item.help, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                    Text("Что будет сделано:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    FriendlyText.planSentences(i.form.kind, i.form.openFirewall, ru.gidravpn.hydra.servers.UiLang.RU, https = i.form.sslIp).forEachIndexed { n, t -> Text("${n + 1}. $t", fontSize = 13.sp) }
                    TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть команды" else "Показать команды") }
                    if (details) SelectionContainer {
                        Column {
                            i.plan.forEachIndexed { n, st ->
                                Text("${n + 1}. ${st.title}", fontSize = 12.sp)
                                (st.command ?: st.upload?.let { "загрузка → ${it.path}" })?.let { Text("   $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { m.runInstall() }, enabled = i.preflight?.ok == true && !s.busy) { Text("Установить") }
                        TextButton(onClick = onClose) { Text("Отмена") }
                    }
                }
                InstallStage.RUNNING -> {
                    Busy(s.progress)
                    LogBox(s.log)
                    OutlinedButton(onClick = { m.stop() }) { Text("Остановить") }
                }
                InstallStage.DONE -> {
                    Text(if (i.form.kind.panel != null && i.form.kind != ServerKind.PANEL_3XUI) "Готово! Панель установлена — откройте её кнопкой «Открыть панель» на карточке сервера."
                        else "Готово! Подключение добавлено в «Серверы» — осталось выбрать его и нажать «Подключить».", color = Ok, fontSize = 13.sp)
                    LogBox(s.log)
                    Button(onClick = onClose) { Text("Закрыть") }
                }
                InstallStage.FAILED -> {
                    s.error?.let { ErrorBox(it) }
                    LogBox(s.log)
                    Button(onClick = onClose) { Text("Закрыть") }
                }
            }
            if (i.stage == InstallStage.CHECKING || i.stage == InstallStage.READY) s.error?.let { ErrorBox(it) }
        }
    }
}

@Composable
private fun StatusChip(st: ServerStatus?) {
    val (text, color) = when {
        st == null -> "не проверялся" to MaterialTheme.colorScheme.onSurfaceVariant
        st.error != null -> "недоступен" to Danger
        else -> "на связи" to Ok
    }
    Text(text, fontSize = 12.sp, color = color)
}

@Composable
private fun Busy(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text(text, fontSize = 13.sp)
}

@Composable
private fun ErrorBox(raw: String) {
    val f = FriendlyText.explain(raw)
    var details by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.08f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.title, color = Danger, fontWeight = FontWeight.SemiBold)
            Text(f.advice, fontSize = 13.sp)
            Row {
                TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть подробности" else "Подробности") }
                TextButton(onClick = { setClipboard(f.raw) }) { Text("Скопировать подробности") }
            }
            if (details) SelectionContainer { Text(f.raw, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}

private fun genHint(g: AwgGeneration) = when (g) {
    AwgGeneration.V1_0 -> "1.0: Jc, Jmin, Jmax, S1, S2, H1–H4. Работает с любыми клиентами AmneziaWG."
    AwgGeneration.V1_5 -> "1.5: параметры как в 1.0 (сигнатурные пакеты I1–I5 не заданы); совместим с клиентами 1.0 и 1.5."
    AwgGeneration.V2_0 -> "2.0: + S3, S4 и диапазоны H1–H4. Нужен клиент 2.0 и новее (Hydra подходит)."
    AwgGeneration.V3 -> "3.x: + HeaderProtectionKey, дополнение содержимого, случайные хвосты. Нужен клиент 3.x (Hydra подходит)."
}

internal fun uptime(sec: Long): String = when {
    sec >= 86_400 -> "${sec / 86_400} дн ${sec % 86_400 / 3600} ч"
    sec >= 3600 -> "${sec / 3600} ч ${sec % 3600 / 60} мин"
    else -> "${sec / 60} мин"
}

internal fun peerLine(handshake: Long, rx: Long, tx: Long): String {
    if (handshake <= 0) return "ещё не подключалось"
    val ago = (System.currentTimeMillis() / 1000 - handshake).coerceAtLeast(0)
    val t = when { ago < 180 -> "на связи"; ago < 3600 -> "${ago / 60} мин назад"; ago < 86_400 -> "${ago / 3600} ч назад"; else -> "${ago / 86_400} дн назад" }
    return "$t · принято ${bytes(rx)} · отправлено ${bytes(tx)}"
}

@Composable
private fun LogBox(lines: List<String>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        SelectionContainer {
            Column(Modifier.padding(8.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
                lines.takeLast(200).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun LoginDialog(
    title: String, user: String, host: String, port: Int, error: String?, rememberDefault: Boolean, editUser: Boolean,
    onCancel: () -> Unit, onOk: (SshTarget, Boolean) -> Unit,
) {
    var u by remember { mutableStateOf(user) }
    var password by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(rememberDefault) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = Danger, fontSize = 13.sp) }
                Text("Вход на $host:$port", fontSize = 12.sp)
                if (editUser) OutlinedTextField(u, { u = it.trim() }, singleLine = true, label = { Text("Пользователь") })
                OutlinedTextField(password, { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Пароль") })
                OutlinedTextField(key, { key = it }, Modifier.height(80.dp), label = { Text("или закрытый SSH-ключ") })
                if (key.isNotBlank()) OutlinedTextField(passphrase, { passphrase = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Пароль к ключу (если есть)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remember, { remember = it }); Text("Запомнить (зашифрованно, только на этом компьютере)", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() || key.isNotBlank(), onClick = {
                onOk(SshTarget(host, port, u.ifBlank { user }, password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() },
                    passphrase.takeIf { key.isNotBlank() && it.isNotEmpty() }), remember)
            }) { Text("Войти") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
