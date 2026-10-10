package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.data.model.DnsEndpoint
import ru.gidravpn.hydra.data.model.EngineToggles
import ru.gidravpn.hydra.data.model.HotspotSettings
import ru.gidravpn.hydra.data.model.MtuPreset
import ru.gidravpn.hydra.data.model.TlsFragmentMode
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.AppIcon
import ru.gidravpn.hydra.desktop.AppTheme
import ru.gidravpn.hydra.desktop.ConnectionMode
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.desktop.core.Autostart
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
internal fun SettingsScreen(c: AppController, ui: UiState) {
    val s = ui.data.settings
    val r = s.routing
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header("Настройки") {} }
        if (ui.active) item { Text("Изменения применятся при следующем подключении.", color = Warn, fontSize = 13.sp) }

        item {
            // 0.7.9: темы и иконки как в Android-приложении.
            Section("Оформление") {
                Text("Тема", fontSize = 13.sp)
                ChipRow {
                    AppTheme.entries.forEach { t ->
                        FilterChip(selected = s.theme == t, onClick = { c.updateSettings { it.copy(theme = t) } },
                            label = { Text(t.title) })
                    }
                }
                Text(s.theme.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Иконка окна, панели задач и трея", fontSize = 13.sp)
                // 0.7.14: настоящие миниатюры вместо подписей — видно, что выбираешь.
                ChipRow {
                    AppIcon.entries.filter { it != AppIcon.FOLLOW_THEME }.forEach { i ->
                        val on = s.appIcon == i
                        androidx.compose.foundation.layout.Column(
                            Modifier.clickable { c.updateSettings { it.copy(appIcon = i) } }.padding(4.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        ) {
                            androidx.compose.foundation.Image(
                                androidx.compose.ui.res.painterResource(i.resource(s.theme).removePrefix("/")), i.title,
                                Modifier.size(64.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                    .border(2.dp, if (on) Accent else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                            )
                            Text(i.title.substringBefore(" ("), fontSize = 11.sp, color = if (on) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FilterChip(selected = s.appIcon == AppIcon.FOLLOW_THEME, onClick = { c.updateSettings { it.copy(appIcon = AppIcon.FOLLOW_THEME) } },
                        label = { Text(AppIcon.FOLLOW_THEME.title) })
                }
                Text("Выбор меняет окно, панель задач и трей сразу; ярлыки на рабочем столе, в «Пуске» (Windows) и в меню приложений (Linux) обновляются сами. Закреплённый на панели задач значок Windows хранит сам — открепите и закрепите заново.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }


        item {
            Section("Режим подключения") {
                ModeOption(s.mode == ConnectionMode.PROXY, "Системный прокси",
                    "HTTP/SOCKS5 на 127.0.0.1:${s.proxyPort}. Права администратора не нужны. Работает для браузеров и программ, " +
                        "которые используют системный прокси.") { c.updateSettings { it.copy(mode = ConnectionMode.PROXY) } }
                ModeOption(s.mode == ConnectionMode.TUN, "TUN — весь трафик",
                    when (Platform.os) {
                        Os.WINDOWS -> "Виртуальный адаптер для всех программ. Нужен запуск Hydra от имени администратора."
                        Os.LINUX -> "Виртуальный адаптер для всех программ. При первом включении система один раз спросит пароль (права CAP_NET_ADMIN для ядра)."
                        Os.MACOS -> "Виртуальный адаптер для всех программ. При подключении и отключении macOS спросит пароль администратора."
                    }) { c.updateSettings { it.copy(mode = ConnectionMode.TUN) } }
            }
        }

        item { EnginesSection(c, ui) }

        item {
            Section("Безопасность") {
                ToggleRow("Kill switch", s.killSwitch,
                    "Если соединение оборвалось, программы не пойдут в интернет мимо VPN. Режим прокси: системный прокси остаётся " +
                        "включённым, пока вы не нажмёте «Отключить». В режиме TUN блокировки на уровне ОС нет: до переподключения трафик идёт напрямую.") {
                    v -> c.updateSettings { it.copy(killSwitch = v) }
                }
                ToggleRow("Переподключаться автоматически", s.autoReconnect,
                    "Если ядро остановилось, Hydra переподключится: через 2, 4, 8, 16, 30 с… до 10 попыток.") {
                    v -> c.updateSettings { it.copy(autoReconnect = v) }
                }
                ToggleRow("Подключаться при запуске Hydra", s.autoConnect, "К выбранному серверу, сразу после старта.") {
                    v -> c.updateSettings { it.copy(autoConnect = v) }
                }
                ToggleRow("Запускать Hydra при входе в систему", s.launchAtLogin,
                    if (Autostart.available) "Hydra стартует свёрнутой в трей; вместе с предыдущим пунктом — подключение сразу после входа."
                    else "Доступно в установленной Hydra (не в режиме разработки).", enabled = Autostart.available) { v -> c.setLaunchAtLogin(v) }
            }
        }

        item {
            Section("Прокси") {
                var port by remember(s.proxyPort) { mutableStateOf(s.proxyPort.toString()) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(port, { v ->
                        port = v.filter(Char::isDigit).take(5)
                        port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> c.updateSettings { it.copy(proxyPort = p) } }
                    }, Modifier.width(140.dp), singleLine = true, label = { Text("Порт") },
                        isError = port.toIntOrNull()?.let { it !in 1024..65535 } ?: true)
                    Switch(s.setSystemProxy, { v -> c.updateSettings { it.copy(setSystemProxy = v) } })
                    Text("Включать системный прокси автоматически")
                }
            }
        }

        if (c.elevationAvailable) item {
            Section("Права администратора") {
                Text(if (c.isElevated) "Hydra запущена от администратора." else "Hydra запущена с обычными правами.")
                Text("Нужны для режима TUN; системному прокси не обязательны, но так Hydra сможет всё, что доступно администратору. " +
                    "Подключение после перезапуска восстановится само (если включено «Подключаться при запуске»).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { c.relaunchElevated() }, enabled = !c.isElevated) { Text("Перезапустить от администратора") }
            }
        }

        item { LanSection(c, ui) }

        item { BotAccountSection(c, ui) }

        item {
            Section("DNS") {
                var dns by remember(r.dns) { mutableStateOf(r.dns) }
                var invalid by remember { mutableStateOf(false) }
                ChipRow {
                    (listOf("1.1.1.1" to "Cloudflare", "8.8.8.8" to "Google", "9.9.9.9" to "Quad9", "94.140.14.14" to "AdGuard", "system" to "Системный") +
                        // Приватный DNS проекта — только вошедшим через бота (адрес с токеном в настройки не пишется).
                        if (c.hydraDnsAvailable) listOf(ru.gidravpn.hydra.desktop.HYDRA_DNS to "Hydra VPN (приватный)") else emptyList())
                        .forEach { (v, label) ->
                            FilterChip(selected = r.dns == v, onClick = { dns = v; invalid = false; c.setDns(v) }, label = { Text(label) })
                        }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(dns, { v -> dns = v; invalid = false }, Modifier.weight(1f), singleLine = true, isError = invalid,
                        label = { Text("Свой DNS: IP, https://…/dns-query, tls://…, udp://…") })
                    OutlinedButton(onClick = { invalid = !c.setDns(dns) }, enabled = dns.trim() != r.dns) { Text("Сохранить") }
                }
                if (invalid) Text("Не похоже на адрес DNS-сервера", color = Danger, fontSize = 12.sp)
                if (DnsEndpoint.parse(r.dns)?.type == DnsEndpoint.TYPE_TLS) {
                    Text("DoT Xray не поддерживает — при движке Xray имя сервера разрешит резолвер ОС (DNS приложений всё равно идёт через туннель).",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Section("Туннель") {
                Text("Фрагментация TLS ClientHello (обход DPI; только движок sing-box, VLESS/VMess/Trojan с TLS)", fontSize = 13.sp)
                ChipRow {
                    listOf(TlsFragmentMode.OFF to "Выключено", TlsFragmentMode.RECORD to "TLS-записи", TlsFragmentMode.TCP to "TCP-сегменты")
                        .forEach { (m, label) -> FilterChip(selected = r.tlsFragment == m, onClick = { c.updateRouting { it.copy(tlsFragment = m) } }, label = { Text(label) }) }
                }
                Text("MTU адаптера TUN (меньше — если сайты «висят» на загрузке: туннель поверх туннеля, мобильный интернет)", fontSize = 13.sp)
                ChipRow {
                    MtuPreset.entries.forEach { m ->
                        FilterChip(selected = r.mtu == m, onClick = { c.updateRouting { it.copy(mtu = m) } },
                            label = { Text(if (m == MtuPreset.AUTO) "Авто (9000)" else "${m.value}") })
                    }
                }
                ToggleRow("IPv6 через туннель", r.ipv6,
                    "Выключено (как на Android по умолчанию): в TUN IPv6 блокируется, имена разрешаются только в IPv4 — без утечек. " +
                        "Включайте, если у сервера есть IPv6 наружу.") { v -> c.updateRouting { it.copy(ipv6 = v) } }
            }
        }

        item {
            Section("Проверка утечек") {
                Text("Подключитесь и откройте проверку в браузере: в режиме TUN — любым браузером, в режиме прокси — браузером, использующим системный прокси.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipRow {
                    OutlinedButton(onClick = { openUrl("https://ipleak.net/") }) { Text("ipleak.net") }
                    OutlinedButton(onClick = { openUrl("https://browserleaks.com/dns") }) { Text("browserleaks.com") }
                }
            }
        }

        item { BackupSection(c, ui) }

        item {
            Section("О программе") {
                Text("Hydra ${Platform.version} · sing-box 1.12.25 · Xray-core 26.9.30 · ${System.getProperty("os.name")} ${System.getProperty("os.arch")}", fontSize = 13.sp)
                Text("Данные: ${Platform.dataDir.absolutePath}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("На ПК поддерживаются: VLESS (в т.ч. REALITY), VMess, Trojan, Shadowsocks (sing-box или Xray), Hysteria2, TUIC, WireGuard (sing-box), " +
                    "olcRTC и OpenFlux (бета). AmneziaWG, SSTP и L2TP пока только в Android.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToggleRow("Проверять обновления при запуске", s.checkUpdates, "Запрос к GitHub: есть ли релиз новее. Скачивание — только по кнопке «Обновить».") {
                    v -> c.updateSettings { it.copy(checkUpdates = v) }
                }
                ChipRow {
                    OutlinedButton(onClick = { c.checkForUpdates(manual = true) }) { Text("Проверить сейчас") }
                    ui.update?.let { u -> UpdateBanner(c, ui, u) }
                }
            }
        }
    }
}

@Composable
private fun EnginesSection(c: AppController, ui: UiState) {
    val s = ui.data.settings
    val xrayBuilt = c.xrayAvailable
    Section("Движки") {
        Text("Как на Android: какое ядро обслуживает протокол. Маршрутизация, DNS и режимы TUN/прокси работают одинаково с обоими.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow("sing-box", s.singBoxEnabled,
            "VLESS/REALITY, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard.") { v -> c.setEngine(EngineToggles.Kind.SINGBOX, v) }
        ToggleRow("Xray-core", s.xrayEnabled && xrayBuilt,
            if (xrayBuilt) "VLESS (XTLS Vision, REALITY, XHTTP), VMess, Trojan, Shadowsocks. Запускается отдельным процессом, sing-box — мост к нему."
            else "Ядро Xray не найдено в пакете — переустановите Hydra.", enabled = xrayBuilt) { v -> c.setEngine(EngineToggles.Kind.XRAY, v) }
        if (xrayBuilt && s.xrayEnabled && s.singBoxEnabled) {
            Row(Modifier.padding(start = 24.dp)) {
                ToggleRow("Предпочитать Xray для VLESS/VMess/Trojan/Shadowsocks", s.preferXray,
                    "Выключено — эти протоколы обслуживает sing-box, Xray — только если sing-box выключен.") {
                    v -> c.updateSettings { it.copy(preferXray = v) }
                }
            }
        }
        // 0.7.4 (BETA): клиенты «подпроцесс → SOCKS5», sing-box — мост к ним (как на Android).
        ToggleRow("olcRTC (BETA)", s.olcRtcEnabled && c.olcRtcAvailable,
            if (c.olcRtcAvailable) "TCP поверх WebRTC через сервисы видеозвонков (Jitsi, Телемост, WB Stream). Нужен свой сервер olcRTC с тем же ключом; апстрим заморожен."
            else "Клиент olcRTC не вошёл в эту сборку.", enabled = c.olcRtcAvailable) { v -> c.setEngine(EngineToggles.Kind.OLCRTC, v) }
        ToggleRow("OpenFlux (BETA)", s.openFluxEnabled && c.openFluxAvailable,
            if (c.openFluxAvailable) "TCP-туннель через сервисы документов и чатов (Яндекс, MAX, Mail.ru, Cups) или напрямую до своего узла. Нужен exit-узел OpenFlux."
            else "Клиент OpenFlux не вошёл в эту сборку.", enabled = c.openFluxAvailable) { v -> c.setEngine(EngineToggles.Kind.OPENFLUX, v) }
        if (!s.singBoxEnabled && !(s.xrayEnabled && xrayBuilt)) Text("Все движки выключены — подключиться не к чему.", color = Danger, fontSize = 12.sp)
        else if (!s.singBoxEnabled) Text("Hysteria2, TUIC и WireGuard без sing-box недоступны.", color = Warn, fontSize = 12.sp)
    }
}

@Composable
private fun LanSection(c: AppController, ui: UiState) {
    val lan = ui.data.settings.lanShare
    Section("Раздача VPN в локальную сеть") {
        Text("Другие устройства вашей сети (телефон, ТВ, приставка) смогут ходить через этот VPN, указав прокси " +
            "с логином и паролем. Порт открывается на всех сетевых адаптерах — брандмауэр ОС может спросить разрешение.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow("Включить", lan.enabled, "") { v -> c.setLanShare(v) }
        if (!lan.enabled) return@Section
        var port by remember(lan.port) { mutableStateOf(lan.port.toString()) }
        var user by remember(lan.username) { mutableStateOf(lan.username) }
        var pass by remember(lan.password) { mutableStateOf(lan.password) }
        ChipRow {
            OutlinedTextField(port, { v ->
                port = v.filter(Char::isDigit).take(5)
                port.toIntOrNull()?.takeIf { it in HotspotSettings.PORT_RANGE }?.let { p -> c.updateLanShare { it.copy(port = p) } }
            }, Modifier.width(110.dp), singleLine = true, label = { Text("Порт") })
            OutlinedTextField(user, { v -> user = v.take(64); if (user.isNotBlank()) c.updateLanShare { it.copy(username = user.trim()) } },
                Modifier.width(160.dp), singleLine = true, label = { Text("Логин") })
            OutlinedTextField(pass, { v -> pass = v.take(128); c.updateLanShare { it.copy(password = pass) } },
                Modifier.widthIn(min = 220.dp), singleLine = true, label = { Text("Пароль (от ${HotspotSettings.MIN_PASSWORD} символов)") },
                isError = pass.length < HotspotSettings.MIN_PASSWORD)
            OutlinedButton(onClick = { c.regenerateLanPassword() }) { Text("Новый пароль") }
        }
        if (ui.data.settings.mode == ConnectionMode.PROXY && lan.port == ui.data.settings.proxyPort) {
            Text("Порт совпадает с локальным прокси — выберите другой.", color = Danger, fontSize = 12.sp)
        }
        if (!lan.isUsable) Text("Раздача не поднимется, пока не заданы порт, логин и пароль.", color = Warn, fontSize = 12.sp)
        val ips = remember { localIps() }
        if (ips.isNotEmpty()) {
            Text("На устройстве укажите HTTP- или SOCKS5-прокси: " + ips.joinToString(" или ") { "$it:${lan.port}" } +
                ", логин «${lan.username}».", fontSize = 12.sp)
        }
    }
}

@Composable
private fun BackupSection(c: AppController, ui: UiState) {
    var confirmFile by remember { mutableStateOf<File?>(null) }
    Section("Резервная копия") {
        Text("Серверы, подписки, настройки и профили в одном файле. В файле пароли серверов — храните его в надёжном месте.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow {
            OutlinedButton(onClick = { chooseFile(save = true)?.let { c.exportBackup(it) } }) { Text("Сохранить в файл…") }
            OutlinedButton(onClick = { chooseFile(save = false)?.let { confirmFile = it } }, enabled = !ui.active) { Text("Восстановить из файла…") }
        }
    }
    confirmFile?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmFile = null },
            title = { Text("Восстановить из «${f.name}»?") },
            text = { Text("Текущие серверы, подписки и настройки будут заменены содержимым файла.") },
            confirmButton = { TextButton(onClick = { c.importBackup(f); confirmFile = null }) { Text("Восстановить", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmFile = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, text: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { onChange(!checked) }.padding(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (text.isNotEmpty()) Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected, onClick)
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun chooseFile(save: Boolean): File? = runCatching {
    val d = FileDialog(null as Frame?, if (save) "Сохранить резервную копию" else "Открыть резервную копию",
        if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (save) d.file = "hydra-backup.json"
    d.isVisible = true
    val name = d.file ?: return null
    File(d.directory, name)
}.getOrNull()

private fun localIps(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("hydra") && it.displayName?.startsWith("Hydra") != true }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress }
        .distinct()
}.getOrDefault(emptyList())
