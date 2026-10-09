package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.data.model.GeoRoutingMode
import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.SplitTunnelMode
import ru.gidravpn.hydra.data.model.countryName
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.ConnectionMode
import ru.gidravpn.hydra.desktop.Os
import ru.gidravpn.hydra.desktop.Platform
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.desktop.core.Processes
import ru.gidravpn.hydra.desktop.core.Rules
import java.io.File

/**
 * Маршрутизация — то же, что на Android в «Раздельном туннелировании» и «Маршрутизации»:
 * по приложениям (здесь — по процессам/программам), по сайтам и IP, по странам и
 * профили маршрутизации.
 */
@Composable
internal fun RoutingScreen(c: AppController, ui: UiState) {
    val s = ui.data.settings
    val r = s.routing
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header("Маршрутизация") {} }
        if (ui.active) item { Text("Изменения применятся при следующем подключении.", color = Warn, fontSize = 13.sp) }

        item { ProfilesSection(c, ui) }

        item {
            Section("По приложениям") {
                Text(
                    "Какие программы идут через VPN. Укажите имя процесса (chrome.exe, firefox, Telegram) или полный путь к программе. " +
                        if (s.mode == ConnectionMode.PROXY) "В режиме «Системный прокси» правило действует только для программ, которые сами ходят через прокси; " +
                            "для всех программ выберите режим TUN." else "В режиме TUN правило действует для всего трафика программы.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SplitModeChips(r.appMode, "Все программы через VPN", "Только выбранные через VPN", "Выбранные — мимо VPN") { c.setAppMode(it) }
                if (r.appMode != SplitTunnelMode.OFF) {
                    AppsEditor(c, r.apps)
                    if (r.apps.isEmpty()) Text("Список пуст — правило не действует.", color = Warn, fontSize = 12.sp)
                }
            }
        }

        item {
            Section("По сайтам и IP-адресам") {
                Text(
                    "Домены (youtube.com — вместе с поддоменами), ключевые слова и IP/подсети. Работает в обоих режимах и с обоими движками.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SplitModeChips(r.netMode, "Выключено", "Только эти — через VPN", "Эти — мимо VPN") { c.setNetMode(it) }
                if (r.netMode != SplitTunnelMode.OFF) NetRulesEditor(c, ui)
            }
        }

        item { GeoSection(c, ui) }
        dpiRoutesItems(c, ui)
    }
}

@Composable
private fun SplitModeChips(mode: SplitTunnelMode, off: String, include: String, exclude: String, onChange: (SplitTunnelMode) -> Unit) {
    ChipRow {
        listOf(SplitTunnelMode.OFF to off, SplitTunnelMode.INCLUDE to include, SplitTunnelMode.EXCLUDE to exclude).forEach { (m, label) ->
            FilterChip(selected = mode == m, onClick = { onChange(m) }, label = { Text(label) })
        }
    }
}

@Composable
private fun AppsEditor(c: AppController, apps: List<String>) {
    var input by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true,
            label = { Text(if (Platform.os == Os.WINDOWS) "chrome.exe или C:\\Program Files\\…\\app.exe" else "firefox или /usr/bin/app") })
        Button(onClick = { c.addApps(listOf(input)); input = "" }, enabled = input.isNotBlank()) { Text("Добавить") }
        OutlinedButton(onClick = { picking = true }) { Text("Из запущенных…") }
    }
    apps.forEach { a ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(a, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Text(if (Rules.isPath(a)) "путь" else "имя процесса", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { c.removeApp(a) }) { Icon(Icons.Default.Close, "Убрать") }
        }
    }
    if (picking) ProcessPicker(apps, onDismiss = { picking = false }) { chosen -> c.addApps(chosen); picking = false }
}

/** Запущенные программы с поиском; можно отметить несколько. */
@Composable
private fun ProcessPicker(current: List<String>, onDismiss: () -> Unit, onPick: (List<String>) -> Unit) {
    val all = remember { Processes.running() }
    var query by remember { mutableStateOf("") }
    var byPath by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val have = current.map { it.lowercase() }.toSet()
    val shown = all.filter { query.isBlank() || it.name.contains(query, true) || it.path.contains(query, true) }
        .let { list -> if (byPath) list else list.distinctBy { it.name.lowercase() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Запущенные программы") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Поиск") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(byPath, { byPath = it })
                    Text("Точнее: по полному пути (не совпадёт с одноимёнными программами из других папок)", fontSize = 12.sp)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { if (byPath) it.path else it.name.lowercase() }) { p ->
                        val value = if (byPath) p.path else p.name
                        val already = value.lowercase() in have
                        Row(Modifier.fillMaxWidth().clickable(enabled = !already) {
                            chosen = if (value in chosen) chosen - value else chosen + value
                        }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(already || value in chosen, null, enabled = !already)
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(p.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (all.isEmpty()) Text("Список процессов недоступен — впишите имя вручную.", color = Warn, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text("Добавить (${chosen.size})") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun NetRulesEditor(c: AppController, ui: UiState) {
    var input by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<NetRuleType?>(null) }
    val types = listOf(null to "Авто", NetRuleType.DOMAIN_SUFFIX to "Домен + поддомены", NetRuleType.DOMAIN to "Точный домен",
        NetRuleType.DOMAIN_KEYWORD to "Слово в домене", NetRuleType.IP_CIDR to "IP / подсеть")
    ChipRow {
        types.forEach { (t, label) -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(label, fontSize = 12.sp) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true,
            label = { Text("youtube.com, 8.8.8.8, 10.0.0.0/8 … (можно несколько через пробел или запятую)") })
        Button(onClick = {
            val parts = input.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            val failed = parts.filterNot { c.addNetRule(type, it) }
            input = failed.joinToString(", ")
        }, enabled = input.isNotBlank()) { Text("Добавить") }
    }
    val rules = ui.data.settings.routing.netRules
    if (rules.isEmpty()) Text("Правил нет — фильтр не действует.", color = Warn, fontSize = 12.sp)
    rules.forEach { rule ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(rule.value, Modifier.weight(1f), fontSize = 13.sp)
            Text(types.firstOrNull { it.first == rule.type }?.second ?: rule.type.name, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { c.removeNetRule(rule) }) { Icon(Icons.Default.Close, "Убрать") }
        }
    }
}

@Composable
private fun GeoSection(c: AppController, ui: UiState) {
    val r = ui.data.settings.routing
    val geoDir = remember { Platform.geoDir() }
    val all = remember(geoDir) {
        geoDir?.let { File(it, "geoip").list()?.filter { n -> n.endsWith(".srs") }?.map { n -> n.removeSuffix(".srs") } }
            .orEmpty().sorted().map { it to countryName(it) }
    }
    val withDomains = remember(geoDir) {
        geoDir?.let { File(it, "geosite").list()?.map { n -> n.removeSuffix(".srs") }?.toSet() }.orEmpty()
    }
    Section("По странам (geoip/geosite)") {
        if (geoDir == null) { Text("Базы geo не найдены в пакете — функция недоступна.", color = Warn, fontSize = 13.sp); return@Section }
        ChipRow {
            listOf(GeoRoutingMode.OFF to "Выключено", GeoRoutingMode.DIRECT to "Эти страны — мимо VPN", GeoRoutingMode.VIA_PROXY to "Только эти страны — через VPN")
                .forEach { (m, label) -> FilterChip(selected = r.geoMode == m, onClick = { c.updateRouting { it.copy(geoMode = m) } }, label = { Text(label) }) }
        }
        if (r.geoMode == GeoRoutingMode.OFF) return@Section
        Text(
            if (r.geoCountries.isEmpty()) "Страны не выбраны" else r.geoCountries.sorted().joinToString(", ") { countryName(it) },
            color = if (r.geoCountries.isEmpty()) Danger else Accent, fontSize = 13.sp,
        )
        Text("По доменам (geosite), кроме IP: ${withDomains.sorted().joinToString(", ") { countryName(it) }}; для остальных стран — только IP-адреса.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var query by remember { mutableStateOf("") }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Найти страну (название или код)") })
        val q = query.trim()
        val shown = if (q.isEmpty()) all.filter { it.first in r.geoCountries }
        else all.filter { (code, name) -> name.contains(q, true) || code.equals(q, true) }.sortedBy { it.second }.take(30)
        shown.forEach { (code, name) ->
            Row(Modifier.fillMaxWidth().clickable { c.toggleGeoCountry(code) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(code in r.geoCountries, { c.toggleGeoCountry(code) })
                Text(name, Modifier.weight(1f), fontSize = 13.sp)
                Text(code.uppercase() + if (code in withDomains) " · IP+домены" else " · IP", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (q.isNotEmpty() && shown.isEmpty()) Text("Не найдено", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProfilesSection(c: AppController, ui: UiState) {
    var naming by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<String?>(null) }
    Section("Профили маршрутизации") {
        Text("Снимок всех настроек маршрутизации (DNS, страны, MTU, фрагментация, IPv6, правила по программам и сайтам) — «Дом», «Работа», «Поездка».",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.data.profiles.isEmpty()) Text("Сохранённых профилей нет.", fontSize = 13.sp)
        ui.data.profiles.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                TextButton(onClick = { c.applyProfile(p.name) }) { Text("Применить") }
                TextButton(onClick = { toDelete = p.name }) { Text("Удалить", color = Danger) }
            }
        }
        OutlinedButton(onClick = { naming = true }) { Text("Сохранить текущие настройки как профиль…") }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Название профиля") },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Например, «Дом»") }) },
            confirmButton = { TextButton(onClick = { c.saveProfile(name); naming = false }, enabled = name.isNotBlank()) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Отмена") } },
        )
    }
    toDelete?.let { n ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить профиль «$n»?") },
            confirmButton = { TextButton(onClick = { c.deleteProfile(n); toDelete = null }) { Text("Удалить", color = Danger) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}
