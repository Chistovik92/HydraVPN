package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.UiState
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

private const val GUIDE_URL = "https://github.com/Chistovik92/HydraVPN/blob/master/docs/SERVERS.md"

/** Действие над уже развёрнутым сервером, которому нужен пароль (он запрашивается и нигде не сохраняется). */
private class NeedLogin(val title: String, val why: String, val server: MyServer, val run: (SshTarget) -> Unit)

private fun kindTitle(k: ServerKind) = when (k) {
    ServerKind.AMNEZIAWG -> "Личный VPN-сервер"
    ServerKind.OPENFLUX -> "Узел OpenFlux"
    ServerKind.PANEL_3XUI -> "Панель управления 3x-ui"
}

private fun kindText(k: ServerKind) = when (k) {
    ServerKind.AMNEZIAWG -> "Ваш собственный VPN: весь трафик устройств идёт через ваш сервер в другой стране, а защита AmneziaWG не даёт провайдеру распознать VPN."
    ServerKind.OPENFLUX -> "Обходной путь для сложных блокировок: связь идёт через сервисы вроде Яндекс Документов, поэтому её трудно заблокировать. Для опытных пользователей."
    ServerKind.PANEL_3XUI -> "Готовый сайт-панель, где вы сами заводите пользователей и выдаёте им ссылки. Удобно, если VPN нужен нескольким людям."
}

// ============================================================== Мои серверы
@Composable
internal fun MyServersScreen(c: AppController, ui: UiState, startWizard: Boolean = false, startStep: Int = 1) {
    val m = c.myServers
    val s by m.ui.collectAsState()
    var wizard by remember { mutableStateOf(startWizard) }
    var help by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<NeedLogin?>(null) }
    var confirm by remember { mutableStateOf<NeedLogin?>(null) }
    var forget by remember { mutableStateOf<MyServer?>(null) }
    var addUserFor by remember { mutableStateOf<MyServer?>(null) }
    var addDeviceFor by remember { mutableStateOf<MyServer?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Мои серверы") {
            OutlinedButton(onClick = { help = true }) { Text("Что это такое?") }
            Button(onClick = { m.reset(); wizard = true }, enabled = !s.busy && !wizard) { Text("Добавить сервер") }
        }
        if (ui.data.myServers.isEmpty() && !wizard) IntroCard(onStart = { m.reset(); wizard = true }, onGuide = { openUrl(GUIDE_URL) })
        if (wizard || (s.stage != Stage.IDLE)) WizardCard(c, m, s, startStep) { wizard = false; m.reset() }

        ui.data.myServers.forEach { srv ->
            ServerCard(c, srv, m, s,
                onAddDevice = { addDeviceFor = srv },
                onAddUser = { addUserFor = srv },
                onRemoveDevice = { name -> login = NeedLogin("Удалить устройство «$name»", "Устройство «$name» перестанет подключаться к этому серверу.", srv) { t -> m.removeClient(srv.id, t, name) } },
                onUninstall = { confirm = NeedLogin("Удалить сервер «${srv.name}» с компьютера в интернете", "", srv) { t -> m.uninstall(srv.id, t) } },
                onForget = { forget = srv })
        }
        if (s.stage == Stage.IDLE && !wizard) {
            s.error?.let { ErrorBox(it) }
        }
    }

    if (help) AlertDialog(
        onDismissRequest = { help = false },
        title = { Text("Свой сервер — что это и зачем") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Обычный VPN — это чужой сервер, которым пользуются тысячи людей. Свой сервер — только ваш: он быстрее, реже блокируется, и никто, кроме вас, к нему не подключён.")
                Text("Как это устроено:", fontWeight = FontWeight.SemiBold)
                Text("1. Вы арендуете у хостинга маленький компьютер в интернете (VPS) — от нескольких долларов в месяц. Выбирайте страну без ограничений и систему Debian или Ubuntu.")
                Text("2. Хостинг присылает письмо с адресом сервера (IP) и паролем.")
                Text("3. Вы вводите их здесь. Hydra сама подключится к серверу, всё установит и настроит.")
                Text("4. В разделе «Серверы» появится готовое подключение — останется нажать «Подключить».")
                Text("Ничего вводить в чёрных окнах не нужно. Пароль от сервера Hydra нигде не сохраняет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { help = false }) { Text("Понятно") } },
        dismissButton = { TextButton(onClick = { openUrl(GUIDE_URL) }) { Text("Подробная инструкция") } },
    )

    addDeviceFor?.let { srv ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDeviceFor = null },
            title = { Text("Новое устройство") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Каждому телефону или компьютеру нужен свой доступ. Назовите устройство, например: телефон-мамы.", fontSize = 13.sp)
                    OutlinedTextField(name, { name = it.filter { ch -> ch.isLetterOrDigit() || ch in "-_" }.take(24) }, singleLine = true, label = { Text("Название латиницей, без пробелов") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && srv.clients.none { it.name == name }, onClick = {
                    addDeviceFor = null
                    login = NeedLogin("Добавить устройство «$name»", "Чтобы добавить устройство, Hydra подключится к серверу.", srv) { t -> m.addClient(srv.id, t, name) }
                }) { Text("Далее") }
            },
            dismissButton = { TextButton(onClick = { addDeviceFor = null }) { Text("Отмена") } },
        )
    }
    addUserFor?.let { srv ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addUserFor = null },
            title = { Text("Новый пользователь панели") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Пользователь — это один человек или одно устройство. Вы получите ссылку для подключения.", fontSize = 13.sp)
                    OutlinedTextField(name, { name = it.filter { ch -> ch.isLetterOrDigit() || ch in "-_" }.take(24) }, singleLine = true, label = { Text("Имя латиницей, без пробелов") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { addUserFor = null; m.addPanelClient(srv.id, name) }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { addUserFor = null }) { Text("Отмена") } },
        )
    }
    forget?.let { srv ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text("Убрать «${srv.name}» из списка?") },
            text = { Text("Сам сервер продолжит работать, он просто исчезнет из приложения. Вы больше не сможете управлять им отсюда (добавлять устройства, удалять его). Подключения, уже добавленные в «Серверы», останутся.") },
            confirmButton = { TextButton(onClick = { forget = null; m.forget(srv.id) }) { Text("Убрать", color = Danger) } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Отмена") } },
        )
    }
    confirm?.let { n ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(n.title + "?") },
            text = {
                Text("Hydra подключится к серверу ${n.server.host} и удалит с него всё, что установила: службу, настройки и программы. " +
                    "Сам сервер у хостинга останется арендованным — отказаться от аренды можно в панели хостинга. Подключения, выданные этим сервером, перестанут работать.")
            },
            confirmButton = { TextButton(onClick = { confirm = null; login = n }) { Text("Продолжить", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
    login?.let { n -> LoginDialog(n, onCancel = { login = null }) { t -> login = null; n.run(t) } }
    s.shown?.let { (name, text) -> ShownDialog(c, name, text) { m.hideShown() } }
}

@Composable
private fun IntroCard(onStart: () -> Unit, onGuide: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Свой VPN за несколько минут", style = MaterialTheme.typography.titleMedium)
            Text("Вы арендуете маленький сервер в интернете — Hydra сама установит на него VPN и подключит к вашему приложению. " +
                "Знать ничего про серверы не нужно.", fontSize = 13.sp)
            Text("Что понадобится:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("• арендованный сервер (VPS) с Debian или Ubuntu — от нескольких долларов в месяц;\n• из письма хостинга: адрес сервера (набор чисел вроде 203.0.113.5) и пароль.", fontSize = 13.sp)
            ChipRow {
                Button(onClick = onStart) { Text("Начать") }
                OutlinedButton(onClick = onGuide) { Text("Как арендовать сервер") }
            }
        }
    }
}

@Composable
private fun ErrorBox(raw: String) {
    val f = FriendlyText.explain(raw)
    var details by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), border = BorderStroke(1.dp, Danger)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(f.title, color = Danger, fontWeight = FontWeight.SemiBold)
            Text(f.advice, fontSize = 13.sp)
            ChipRow {
                TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть подробности" else "Подробности") }
                TextButton(onClick = { setClipboard(f.raw) }) { Text("Скопировать подробности") }
            }
            if (details) SelectionContainer { Text(f.raw, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Мастер

private val STEP_TITLES = listOf("Что установить", "Данные сервера", "Это ваш сервер?", "Проверка", "Установка", "Готово")

@Composable
private fun WizardCard(c: AppController, m: MyServersManager, s: MyServersUi, startStep: Int, onClose: () -> Unit) {
    var local by remember { mutableStateOf(startStep.coerceIn(1, 2)) }
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
    val defaultName = when (kind) { ServerKind.AMNEZIAWG -> "Мой VPN"; ServerKind.OPENFLUX -> "Мой узел"; ServerKind.PANEL_3XUI -> "Моя панель" }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Шаг $step из 6 · ${STEP_TITLES[step - 1]}", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { step / 6f }, Modifier.fillMaxWidth())

            if (s.stage == Stage.FAILED) {
                s.error?.let { ErrorBox(it) }
                if (s.log.isNotEmpty()) TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Скрыть журнал" else "Показать журнал установки") }
                if (showLog) LogBox(s.log)
            }

            when (step) {
                1 -> {
                    Text("Выберите, что поставить на ваш сервер. Если не уверены — оставьте первый вариант.", fontSize = 13.sp)
                    ServerKind.entries.forEach { k ->
                        Card(
                            Modifier.fillMaxWidth().clickable { kind = k },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(if (kind == k) 2.dp else 1.dp, if (kind == k) Accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text((if (kind == k) "● " else "○ ") + kindTitle(k), fontWeight = FontWeight.SemiBold)
                                Text(kindText(k), fontSize = 13.sp)
                                Text(FriendlyText.recommendation(k), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    ChipRow {
                        Button(onClick = { local = 2 }) { Text("Далее") }
                        TextButton(onClick = onClose) { Text("Закрыть") }
                    }
                }
                2 -> {
                    Text("Эти данные пришли в письме от хостинга после аренды сервера. Пароль нигде не сохраняется — он нужен только на время установки.", fontSize = 13.sp)
                    OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Адрес сервера") }, supportingText = { Text("Четыре числа через точки, например 203.0.113.5") })
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Пароль") }, supportingText = { Text("Пароль пользователя root из письма. Лучше вставить, чем набирать.") })
                    OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Как назвать (для вас)") }, placeholder = { Text(defaultName) })
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Скрыть «Дополнительно»" else "Дополнительно (обычно ничего менять не нужно)") }
                    if (advanced) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(sshPort, { sshPort = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), singleLine = true,
                                    label = { Text("Порт для входа") }, supportingText = { Text("Обычно 22") })
                                OutlinedTextField(user, { user = it.trim() }, Modifier.weight(1f), singleLine = true,
                                    label = { Text("Пользователь") }, supportingText = { Text("Обычно root") })
                            }
                            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().height(90.dp),
                                label = { Text("Ключ входа (вместо пароля, для опытных)") })
                            when (kind) {
                                ServerKind.AMNEZIAWG -> {
                                    Text("Версия защиты (рекомендуем 2.0):", fontSize = 13.sp)
                                    ChipRow { AwgGeneration.entries.forEach { g -> FilterChip(selected = gen == g, onClick = { gen = g }, label = { Text(if (g == AwgGeneration.V2_0) "${g.label} · рекомендуем" else g.label) }) } }
                                    Text(genHint(gen), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedTextField(awgPort, { awgPort = it.filter(Char::isDigit).take(5) }, Modifier.widthIn(max = 260.dp), singleLine = true,
                                        label = { Text("Порт VPN") }, supportingText = { Text("Любой от 1024 до 65535") })
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(openFw, { openFw = it })
                                        Column {
                                            Text("Открыть порт в защите сервера", fontSize = 13.sp)
                                            Text("Нужно, только если на сервере включён файрвол (проверка покажет). По умолчанию мы ничего не открываем без вашего согласия.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                ServerKind.OPENFLUX -> {
                                    OutlinedTextField(channel, { channel = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(31) }, Modifier.widthIn(max = 300.dp), singleLine = true,
                                        label = { Text("Имя канала") }, supportingText = { Text("Латиница, цифры и дефис") })
                                    OutlinedTextField(yandex, { yandex = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                                        label = { Text("Документ Яндекса (по желанию)") }, supportingText = { Text("Ссылка вида https://docs.yandex.ru/edit/d/…") })
                                    OutlinedTextField(mailru, { mailru = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                                        label = { Text("Публичный документ Mail.ru (по желанию)") })
                                }
                                ServerKind.PANEL_3XUI -> OutlinedTextField(panelPort, { panelPort = it.filter(Char::isDigit).take(5) }, Modifier.widthIn(max = 260.dp), singleLine = true,
                                    label = { Text("Порт подключения") }, supportingText = { Text("Через него будут подключаться пользователи. Обычно 443") })
                            }
                        }
                    }
                    val port = sshPort.toIntOrNull() ?: 0
                    val extraOk = when (kind) {
                        ServerKind.AMNEZIAWG -> (awgPort.toIntOrNull() ?: 0) in 1024..65535
                        ServerKind.OPENFLUX -> OfxNodeSpec.validChannel(channel)
                        ServerKind.PANEL_3XUI -> (panelPort.toIntOrNull() ?: 0) in 1..65535
                    }
                    val hostOk = MyServersManager.validHost(host)
                    if (host.isNotBlank() && !hostOk) Text("Адрес не похож на настоящий — проверьте, нет ли лишних символов.", color = Danger, fontSize = 12.sp)
                    ChipRow {
                        TextButton(onClick = { local = 1 }) { Text("← Назад") }
                        Button(enabled = !s.busy && hostOk && port in 1..65535 && extraOk && (password.isNotEmpty() || key.isNotBlank()), onClick = {
                            m.begin(ServerForm(name.ifBlank { defaultName }, SshTarget(host, port, user.ifBlank { "root" },
                                password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() }), gen, awgPort.toIntOrNull() ?: 51820, openFw,
                                kind, channel, yandex, mailru, accessPort = panelPort.toIntOrNull() ?: 443))
                        }) { Text("Проверить сервер") }
                        TextButton(onClick = onClose) { Text("Закрыть") }
                    }
                    if (s.busy) Busy(s.progress.ifBlank { "Подключаюсь к серверу…" })
                }
                3 -> {
                    Text("Мы подключились к серверу. У каждого сервера есть «отпечаток» — как номер паспорта. Hydra запомнит его и в будущем будет подключаться только к серверу с таким же номером: так никто не сможет подменить ваш сервер.", fontSize = 13.sp)
                    Text("Если вы только что арендовали этот сервер — просто нажмите «Да, это мой сервер». Сверить отпечаток можно в панели хостинга (это по желанию).", fontSize = 13.sp)
                    SelectionContainer { Text(s.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                    ChipRow {
                        Button(onClick = { m.confirmFingerprint() }, enabled = !s.busy) { Text("Да, это мой сервер") }
                        TextButton(onClick = onClose) { Text("Нет, отмена") }
                    }
                    if (s.busy) Busy(s.progress.ifBlank { "Вхожу на сервер…" })
                }
                4 -> {
                    s.preflight?.let { pre ->
                        Text("Проверили ваш сервер:", fontWeight = FontWeight.SemiBold)
                        FriendlyText.checklist(pre).forEach { item ->
                            Column {
                                Text((if (item.ok) "✓ " else "✗ ") + item.title, color = if (item.ok) Accent else Danger, fontSize = 13.sp)
                                if (!item.ok) Text(item.help, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 18.dp))
                            }
                        }
                    }
                    Text("Что мы сейчас сделаем:", fontWeight = FontWeight.SemiBold)
                    FriendlyText.planSentences(kind, openFw).forEachIndexed { i, t -> Text("${i + 1}. $t", fontSize = 13.sp) }
                    Text("Это займёт 1–3 минуты. Всё установленное можно будет удалить одной кнопкой.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showCommands = !showCommands }) { Text(if (showCommands) "Скрыть технические подробности" else "Показать технические подробности (для опытных)") }
                    if (showCommands) SelectionContainer {
                        Column {
                            s.plan.forEachIndexed { i, st ->
                                Text("${i + 1}. ${st.title}", fontSize = 12.sp)
                                (st.command ?: st.upload?.let { "загрузка файла → ${it.path}" })?.let { Text("   $it", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    ChipRow {
                        Button(onClick = { m.install() }, enabled = s.preflight?.ok == true) { Text("Установить") }
                        TextButton(onClick = onClose) { Text("Отмена") }
                    }
                    if (s.preflight?.ok == false) Text("Пока есть красные пункты, установить нельзя. Исправьте их и начните заново.", color = Danger, fontSize = 12.sp)
                }
                5 -> {
                    Busy(s.progress.ifBlank { "Устанавливаю…" })
                    Text("Идёт установка. Не закрывайте приложение — обычно это занимает 1–3 минуты.", fontSize = 13.sp)
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Скрыть журнал" else "Показать журнал (для опытных)") }
                    if (showLog) LogBox(s.log)
                    OutlinedButton(onClick = { m.stop() }) { Text("Остановить") }
                }
                else -> {
                    Text("Готово! ✓", style = MaterialTheme.typography.titleLarge, color = Accent)
                    val done = c.ui.value.data.myServers.lastOrNull()
                    when (kind) {
                        ServerKind.AMNEZIAWG -> Text("Подключение уже добавлено в раздел «Серверы». Откройте его, выберите «${done?.name ?: "ваш сервер"}» и нажмите «Подключить».\n\nЧтобы подключить ещё один телефон или компьютер — в карточке сервера ниже нажмите «Добавить устройство».", fontSize = 13.sp)
                        ServerKind.OPENFLUX -> Text("Узел запущен, ссылка для подключения добавлена в раздел «Серверы». Выберите её и нажмите «Подключить». Ссылку можно отправить и другим устройствам — кнопка «Ссылка openflux://» в карточке сервера.", fontSize = 13.sp)
                        ServerKind.PANEL_3XUI -> {
                            Text("Панель установлена, первый пользователь создан и добавлен в раздел «Серверы» — осталось нажать «Подключить».", fontSize = 13.sp)
                            done?.panel?.let { p ->
                                Text("Запишите вход в панель (он нужен, если захотите управлять пользователями на сайте):", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                SelectionContainer { Text("Адрес: ${p.baseUrl}\nЛогин: ${p.username}\nПароль: ${p.password}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                                ChipRow {
                                    OutlinedButton(onClick = { setClipboard("Адрес: ${p.baseUrl}\nЛогин: ${p.username}\nПароль: ${p.password}"); c.toast("Данные для входа скопированы") }) { Text("Скопировать данные") }
                                    OutlinedButton(onClick = { setClipboard(p.password); openUrl(p.baseUrl); c.toast("Пароль скопирован — вставьте его на странице входа") }) { Text("Открыть панель") }
                                }
                            }
                        }
                    }
                    Button(onClick = onClose) { Text("Закрыть") }
                }
            }
        }
    }
}

private fun genHint(g: AwgGeneration) = when (g) {
    AwgGeneration.V1_0 -> "Самая старая версия. Работает с любыми программами AmneziaWG."
    AwgGeneration.V1_5 -> "Промежуточная версия; совместима со старыми и новыми программами."
    AwgGeneration.V2_0 -> "Современная защита от распознавания VPN. Подходит Hydra и новым версиям AmneziaWG."
    AwgGeneration.V3 -> "Самая новая версия защиты. Нужна свежая программа AmneziaWG (Hydra подходит)."
}

@Composable
private fun Busy(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    CircularProgressIndicator(Modifier.height(18.dp).widthIn(max = 18.dp), strokeWidth = 2.dp); Text(text, fontSize = 13.sp)
}

@Composable
private fun LogBox(lines: List<String>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        SelectionContainer {
            Column(Modifier.padding(8.dp).height(180.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
                lines.takeLast(200).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Карточка сервера

@Composable
private fun ServerCard(
    c: AppController, srv: MyServer, m: MyServersManager, st: MyServersUi,
    onAddDevice: () -> Unit, onAddUser: () -> Unit, onRemoveDevice: (String) -> Unit, onUninstall: () -> Unit, onForget: () -> Unit,
) {
    val busy = st.busy
    var more by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val kind = when { srv.panel != null -> ServerKind.PANEL_3XUI; srv.ofx != null -> ServerKind.OPENFLUX; else -> ServerKind.AMNEZIAWG }
            Text(srv.name, style = MaterialTheme.typography.titleMedium)
            Text(kindTitle(kind) + " · сервер ${srv.host}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (kind) {
                ServerKind.AMNEZIAWG -> {
                    Text("Устройства, которым разрешено подключаться:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    srv.clients.forEach { cl ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("• ${cl.name}", Modifier.widthIn(min = 160.dp), fontSize = 13.sp)
                            OutlinedButton(onClick = { m.clientConf(srv.id, cl.name)?.let { conf -> m.showConf(cl.name, conf) } }) { Text("Показать доступ") }
                            TextButton(onClick = { onRemoveDevice(cl.name) }, enabled = !busy && srv.clients.size > 1) { Text("Удалить", color = Danger) }
                        }
                    }
                    OutlinedButton(onClick = onAddDevice, enabled = !busy) { Text("Добавить устройство") }
                }
                ServerKind.OPENFLUX -> {
                    Text("Узел работает. Ссылка для подключения уже добавлена в «Серверы»; её же можно отправить на другое устройство.", fontSize = 13.sp)
                    OutlinedButton(onClick = { m.clientConf(srv.id, "")?.let { link -> m.showConf("Ссылка openflux://", link) } }) { Text("Показать ссылку openflux://") }
                }
                ServerKind.PANEL_3XUI -> {
                    val p = srv.panel!!
                    Text("Панель — это сайт, где вы заводите пользователей. Версия ${p.version.ifBlank { "—" }}.", fontSize = 13.sp)
                    SelectionContainer { Text("Адрес: ${p.baseUrl}\nЛогин: ${p.username}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    ChipRow {
                        Button(onClick = { setClipboard(p.password); openUrl(p.baseUrl); c.toast("Пароль скопирован — вставьте его на странице входа") }) { Text("Открыть панель") }
                        OutlinedButton(onClick = { setClipboard(p.password); c.toast("Пароль скопирован") }) { Text("Скопировать пароль") }
                        OutlinedButton(onClick = { m.loadPanelClients(srv.id) }, enabled = !busy) { Text("Пользователи") }
                        OutlinedButton(onClick = onAddUser, enabled = !busy) { Text("Добавить пользователя") }
                    }
                    st.panelClients[srv.id]?.let { emails ->
                        if (emails.isEmpty()) Text("Пользователей пока нет.", fontSize = 12.sp)
                        emails.forEach { email ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("• $email", Modifier.widthIn(min = 160.dp), fontSize = 13.sp)
                                OutlinedButton(onClick = { m.showPanelLinks(srv.id, email) }, enabled = !busy) { Text("Ссылка") }
                                TextButton(onClick = { m.removePanelClient(srv.id, email) }, enabled = !busy) { Text("Удалить", color = Danger) }
                            }
                        }
                    }
                    if (st.error != null && st.stage == Stage.IDLE) ErrorBox(st.error!!)
                }
            }
            if (busy) Busy("Работаю…")
            TextButton(onClick = { more = !more }) { Text(if (more) "Скрыть" else "Ещё…") }
            if (more) {
                Text("Отпечаток сервера: ${srv.fingerprint}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipRow {
                    OutlinedButton(onClick = onUninstall, enabled = !busy) { Text("Удалить с сервера", color = Danger) }
                    TextButton(onClick = onForget, enabled = !busy) { Text("Убрать из списка") }
                }
                Text("«Удалить с сервера» — Hydra зайдёт на сервер и уберёт всё, что установила. «Убрать из списка» — сервер продолжит работать, просто пропадёт из приложения.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        title = { Text(n.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (n.why.isNotBlank()) Text(n.why, fontSize = 13.sp)
                Text("Введите пароль от сервера ${srv.host} (пользователь ${srv.sshUser}). Hydra его не запоминает.", fontSize = 12.sp)
                OutlinedTextField(password, { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text("Пароль") })
                OutlinedTextField(key, { key = it }, Modifier.height(80.dp), label = { Text("или ключ входа (для опытных)") })
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() || key.isNotBlank(), onClick = {
                onOk(SshTarget(srv.host, srv.sshPort, srv.sshUser, password.takeIf { key.isBlank() && it.isNotEmpty() }, key.takeIf { it.isNotBlank() }))
            }) { Text("Выполнить") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}

/** Окно с доступом: файл настроек WireGuard/AmneziaWG, ссылка openflux:// или ссылка vless:// — с пояснением, что с ней делать. */
@Composable
private fun ShownDialog(c: AppController, name: String, text: String, onClose: () -> Unit) {
    val isConf = "[Interface]" in text
    val hint = when {
        isConf -> "Это файл настроек для устройства «$name». На этом компьютере нажмите «Добавить в «Серверы»». На телефоне или другом компьютере установите приложение Hydra или AmneziaWG и вставьте эти настройки (или откройте их как файл)."
        text.startsWith("openflux://") -> "Это ссылка для подключения. На этом компьютере нажмите «Добавить в «Серверы»». На другом устройстве вставьте её в Hydra: «Серверы» → «Вставить из буфера»."
        else -> "Это ссылка для подключения пользователя «$name». На этом компьютере нажмите «Добавить в «Серверы»». На другом устройстве вставьте её в Hydra: «Серверы» → «Вставить из буфера»."
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Доступ: $name") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).height(320.dp).widthIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, fontSize = 13.sp)
                Text("Не показывайте это посторонним: по ссылке или файлу можно пользоваться вашим сервером.", fontSize = 12.sp, color = Danger)
                SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { setClipboard(text); c.toast("Скопировано") }) { Text("Скопировать") } },
        dismissButton = {
            Row {
                TextButton(onClick = { c.import(text); onClose() }) { Text("Добавить в «Серверы»") }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        },
    )
}
