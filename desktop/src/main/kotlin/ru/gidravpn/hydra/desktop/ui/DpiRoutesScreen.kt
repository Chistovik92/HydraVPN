package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.data.dpi.DpiArgs
import ru.gidravpn.hydra.data.dpi.DpiStrategies
import ru.gidravpn.hydra.data.geo.CustomGeoSource
import ru.gidravpn.hydra.data.geo.GeoKind
import ru.gidravpn.hydra.data.geo.GeoSources
import ru.gidravpn.hydra.data.model.Engine
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.routing.RouteConfig
import ru.gidravpn.hydra.data.routing.RouteGroup
import ru.gidravpn.hydra.data.tgws.TgWsPreset
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import ru.gidravpn.hydra.data.routing.RouteKind
import ru.gidravpn.hydra.data.routing.RouteRule
import ru.gidravpn.hydra.data.routing.RouteTarget
import ru.gidravpn.hydra.data.routing.viaOf
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.Platform
import ru.gidravpn.hydra.desktop.UiState
import java.text.DateFormat
import java.util.Date

private val kindNames = mapOf(
    RouteKind.APP to "Приложение (Android)", RouteKind.PROCESS to "Программа", RouteKind.DOMAIN to "Домен", RouteKind.SUFFIX to "Суффикс домена",
    RouteKind.KEYWORD to "Ключевое слово", RouteKind.REGEX to "Regex", RouteKind.CIDR to "Диапазон IP", RouteKind.SRC_CIDR to "IP клиента",
    RouteKind.PORT to "Порт", RouteKind.PROTOCOL to "Протокол (tls, http, quic…)", RouteKind.NETWORK to "Сеть (tcp/udp)",
    RouteKind.GEOIP to "Страна (IP)", RouteKind.GEOSITE to "Страна (домены)",
)

private fun targetName(t: String, servers: List<ServerProfile>, groups: List<RouteGroup>): String = when (t) {
    RouteTarget.PROXY -> "VPN"
    RouteTarget.DIRECT -> "Напрямую"
    RouteTarget.DPI -> "Обход DPI"
    RouteTarget.TGWS -> "Telegram по WebSocket"
    RouteTarget.BLOCK -> "Блок"
    else -> RouteTarget.nodeId(t)?.let { id -> servers.firstOrNull { it.id == id }?.name }
        ?: groups.firstOrNull { it.tag == t }?.let { "◎ ${it.name}" } ?: t
}

/** Разделы вкладок «Маршрутизации» (0.7.14): на каждую вкладку — свой набор. */
internal fun androidx.compose.foundation.lazy.LazyListScope.sitesRulesItems(c: AppController, ui: UiState) { item { RulesSection(c, ui, programs = false) } }
internal fun androidx.compose.foundation.lazy.LazyListScope.programRulesItems(c: AppController, ui: UiState) { item { RulesSection(c, ui, programs = true) } }
internal fun androidx.compose.foundation.lazy.LazyListScope.dpiItems(c: AppController, ui: UiState) {
    item { DpiWizardSection(c, ui) }
    item { DpiSection(c, ui) }
    item { DpiProbeSettingsSection(c, ui) }
    item { TgWsSection(c, ui) }
    item { TelegramProxySection(c, ui) }
}
internal fun androidx.compose.foundation.lazy.LazyListScope.exitsItems(c: AppController, ui: UiState) {
    item { GroupsSection(c, ui) }
    item { ChainsSection(c, ui) }
}
internal fun androidx.compose.foundation.lazy.LazyListScope.geoLayerItems(c: AppController, ui: UiState) { item { GeoLayerSection(c, ui) } }

private val dpiAvailable get() = Platform.bundledByeDpi() != null

@Composable
private fun DpiWizardSection(c: AppController, ui: UiState) {
    val probe by c.dpiProbe.collectAsState()
    Section("Подобрать обход за меня") {
        Text("Отметьте, что должно открываться. Hydra переберёт готовые стратегии ByeDPI на вашем подключении и покажет лучшие.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!dpiAvailable) {
            Text("ByeDPI (ciadpi) не вошёл в эту сборку — подбор недоступен.", color = Warn, fontSize = 12.sp)
            return@Section
        }
        val ps = ui.data.settings.routing.routes.dpi.probe
        var extra by remember { mutableStateOf("") }
        var full by remember { mutableStateOf(false) }
        var listsDialog by remember { mutableStateOf(false) }
        var showAll by remember { mutableStateOf(false) }
        val chosen = ps.selectedSites().keys.map { groupNames[it] ?: it }.joinToString(", ").ifEmpty { "Ничего не выбрано" }
        Box {
            OutlinedTextField(chosen, {}, Modifier.fillMaxWidth(), readOnly = true, singleLine = true, label = { Text("Списки доменов") },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
            Box(Modifier.matchParentSize().clickable { listsDialog = true })
        }
        if (listsDialog) SiteListsDialog(c, ui) { listsDialog = false }
        OutlinedTextField(extra, { extra = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Свой сайт (необязательно), например example.com") })
        ChipRow {
            FilterChip(selected = !full, onClick = { full = false }, label = { Text("Быстро (около минуты)") })
            FilterChip(selected = full, onClick = { full = true }, label = { Text("Тщательно (все стратегии)") })
        }
        if (probe.running) OutlinedButton(onClick = { c.cancelProbe() }) { Text("Остановить") }
        else Button(onClick = { c.probeDpi(extra, full) }) { Text("Начать подбор") }
        if (ui.active && ui.data.settings.mode == ru.gidravpn.hydra.desktop.ConnectionMode.TUN)
            Text("Идёт подключение в режиме TUN: проверка пойдёт через VPN. Отключитесь для честного результата.", color = Warn, fontSize = 12.sp)
        if (probe.running) {
            LinearProgressIndicator(progress = { if (probe.total == 0) 0f else probe.done.toFloat() / probe.total }, Modifier.fillMaxWidth())
            Text("Проверено ${probe.done} из ${probe.total}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        probe.error?.let { Text(it, color = Danger, fontSize = 12.sp) }
        probe.baseline?.let { b ->
            Text(if (b.percent >= 95) "Без обхода уже открывается ${b.percent}% сайтов — скорее всего, обход вам не нужен." else "Без обхода открывается только ${b.percent}% сайтов.",
                fontSize = 12.sp, color = if (b.percent >= 95) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (if (showAll) probe.results else probe.results.take(3)).forEachIndexed { i, r -> StrategyResult(c, i, r, best = i == 0) }
        if (probe.results.size > 3) OutlinedButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Свернуть" else "Показать все результаты (${probe.results.size})") }
        if (probe.finished) Text(
            if (probe.results.firstOrNull()?.ok ?: 0 == 0) "Ничего не помогло. Попробуйте «Тщательно» или другое подключение."
            else "Готово. «Использовать» включает обход для трафика напрямую; «Сохранить профилем» добавляет отдельный профиль «Обход DPI».",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DpiSection(c: AppController, ui: UiState) {
    val dpi = ui.data.settings.routing.routes.dpi
    var picker by remember { mutableStateOf(false) }
    Section("Обход DPI для трафика напрямую") {
        Text("ByeDPI — локальный прокси: режет и подделывает первые пакеты соединения, чтобы DPI провайдера не узнал сайт. Это не VPN: ваш IP он не скрывает.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Пускать трафик «напрямую» через ByeDPI", Modifier.weight(1f))
            Switch(checked = dpi.enabled, onCheckedChange = { v -> c.setDpi { it.copy(enabled = v) } }, enabled = dpiAvailable)
        }
        val idx = DpiStrategies.PRESETS.indexOf(dpi.strategy)
        Box {
            OutlinedTextField(if (idx >= 0) "Готовая стратегия ${idx + 1} из ${DpiStrategies.PRESETS.size}" else "Своя", {}, Modifier.fillMaxWidth(),
                readOnly = true, singleLine = true, label = { Text("Стратегия") }, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
            Box(Modifier.matchParentSize().clickable { picker = true })
        }
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(dpi.strategy, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        var custom by remember(dpi.strategy) { mutableStateOf(dpi.strategy) }
        OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), label = { Text("Или впишите свою (аргументы ciadpi, для опытных)") }, isError = !DpiArgs.isUsable(custom))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.setDpi { it.copy(strategy = custom.trim().ifBlank { DpiStrategies.DEFAULT }) } }) { Text("Применить") }
            OutlinedButton(onClick = { c.addByeDpiProfile(dpi.strategy) }) { Text("Добавить профиль «Обход DPI»") }
        }
        if (picker) PickDialog("Выберите стратегию", DpiStrategies.PRESETS, dpi.strategy, { i, s -> "${i + 1}. $s" },
            { s -> c.setDpi { it.copy(strategy = s) }; picker = false }, { picker = false })
    }
}

@Composable
private fun TgWsSection(c: AppController, ui: UiState) {
    val on = TgWsPreset.isApplied(ui.data.settings.routing.routes.rules)
    Section("Telegram по WebSocket") {
        Text("Когда Telegram не грузится даже с обходом DPI: его трафик идёт к серверам Telegram по WebSocket поверх TLS (kws*.web.telegram.org) " +
            "вместо обычного TCP, который провайдеры душат. Свой сервер не нужен. ",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Пускать Telegram через WebSocket", Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { c.toggleTgWsPreset() })
        }
    }
}

/** Варианты выхода для списка: VPN, напрямую, обход DPI, Telegram WS, блок, группы, серверы. */
private fun exitOptions(cfg: RouteConfig, servers: List<ServerProfile>): List<Pair<String, String>> {
    val nodes = servers.filter { it.protocol?.engine == Engine.SINGBOX || it.protocol?.engine == Engine.OPENFLUX || it.protocol?.engine == Engine.OLCRTC }
    return (listOf(RouteTarget.PROXY, RouteTarget.DIRECT, RouteTarget.DPI, RouteTarget.TGWS, RouteTarget.BLOCK) + cfg.groups.map { it.tag } + nodes.map { RouteTarget.node(it.id) })
        .map { it to targetName(it, servers, cfg.groups) }
}

/** Правила «что → через какой выход»: [programs] — по программам (ПК), иначе сайты, IP, страны, порты. */
@Composable
private fun RulesSection(c: AppController, ui: UiState, programs: Boolean) {
    val cfg = ui.data.settings.routing.routes
    val servers = ui.data.servers
    val presetRules = remember { TgWsPreset.rules().toSet() }
    val rules = cfg.rules.filter { r -> r !in presetRules && if (programs) r.kind == RouteKind.PROCESS else (r.kind != RouteKind.PROCESS && r.kind != RouteKind.APP) }
    val tgOn = !programs && TgWsPreset.isApplied(cfg.rules)
    val exits = exitOptions(cfg, servers)
    Section(if (programs) "Свой выход для программы" else "Правила для сайтов, IP, стран и портов") {
        Text(if (programs) "Например: одна программа — через выбранный сервер или обход DPI, остальные — по режиму выше. Имя процесса (chrome.exe, firefox) или полный путь."
            else "Что подходит → какой выход. Правила проверяются сверху вниз, срабатывает первое подходящее.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rules.isEmpty() && !tgOn) Text("Правил пока нет", fontSize = 12.sp)
        if (tgOn) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Telegram  →  ${targetName(RouteTarget.TGWS, servers, cfg.groups)}", Modifier.weight(1f), fontSize = 13.sp)
            TextButton(onClick = { c.toggleTgWsPreset() }) { Text("Удалить", color = Danger) }
        }
        rules.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text((if (r.invert) "НЕ " else "") + "${kindNames[r.kind]}  ${r.value}" + (if (r.group.isNotBlank()) " [${r.group}]" else "") +
                    "  →  ${targetName(r.target, servers, cfg.groups)}", Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = { c.removeRouteRule(r) }) { Text("Удалить", color = Danger) }
            }
        }
        var kind by remember { mutableStateOf(RouteKind.DOMAIN) }
        var value by remember { mutableStateOf("") }
        var target by remember { mutableStateOf(RouteTarget.DIRECT) }
        var invert by remember { mutableStateOf(false) }
        var group by remember { mutableStateOf("") }
        var more by remember { mutableStateOf(false) }
        val kinds = RouteKind.entries.filter { it != RouteKind.APP && it != RouteKind.PROCESS }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            if (programs) {
                OutlinedTextField(value, { value = it }, Modifier.weight(1f), singleLine = true, label = { Text("Программа: имя процесса или путь") })
            } else {
                DropField("Условие", kindNames.getValue(kind), kinds.map { it to kindNames.getValue(it) }, { kind = it }, Modifier.weight(0.45f), selected = kind)
                OutlinedTextField(value, { value = it }, Modifier.weight(0.55f), singleLine = true, label = { Text(kindHints.getValue(kind)) })
            }
        }
        DropField("Выход", targetName(target, servers, cfg.groups), exits, { target = it }, selected = target)
        if (!programs) {
            TextButton(onClick = { more = !more }) { Text((if (more) "▾ " else "▸ ") + "Дополнительно") }
            if (more) {
                Text("«НЕ» переворачивает условие. Правила с одной меткой склеиваются по «И» (например, порт 443 И страна).",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = invert, onClick = { invert = !invert }, label = { Text("НЕ") })
                    OutlinedTextField(group, { group = it }, Modifier.weight(1f), singleLine = true, label = { Text("Метка группы «И» (необязательно)") })
                }
            }
        }
        ChipRow {
            Button(onClick = {
                if (value.isNotBlank()) { c.addRouteRule(RouteRule(if (programs) RouteKind.PROCESS else kind, value.trim(), target, invert, group.trim())); value = "" }
            }) { Text("Добавить правило") }
            if (!programs) {
                OutlinedButton(onClick = { c.applyBlockedRuPreset() }) { Text("Заблокированное в РФ → обход DPI") }
                OutlinedButton(onClick = { c.toggleTgWsPreset() }) { Text(if (tgOn) "Telegram → WebSocket ✓" else "Telegram → WebSocket") }
            }
        }
    }
}

private val kindHints = mapOf(
    RouteKind.DOMAIN to "Домен, например example.com", RouteKind.SUFFIX to "Окончание домена, например .ru", RouteKind.KEYWORD to "Слово в домене, например google",
    RouteKind.REGEX to "Регулярное выражение для домена", RouteKind.CIDR to "Диапазон IP, например 149.154.160.0/20", RouteKind.SRC_CIDR to "IP клиента или диапазон (раздача в сеть)",
    RouteKind.PORT to "Порт или диапазон: 443 или 6881-6889", RouteKind.PROTOCOL to "Протокол: tls, http, quic, dns…", RouteKind.NETWORK to "tcp или udp",
    RouteKind.GEOIP to "Код страны или набор, например ru", RouteKind.GEOSITE to "Набор доменов, например category-ru, youtube",
    RouteKind.APP to "Приложение", RouteKind.PROCESS to "Программа",
)

@Composable
private fun GroupsSection(c: AppController, ui: UiState) {
    val cfg = ui.data.settings.routing.routes
    val servers = ui.data.servers
    val nodes = servers.filter { it.protocol?.engine == Engine.SINGBOX || it.protocol?.engine == Engine.OPENFLUX || it.protocol?.engine == Engine.OLCRTC }
    Section("Группы выходов") {
        Text("Автоматически (самый быстрый живой выход, с переключением при сбое) или ручной выбор. Группа может быть целью правила или членом другой группы.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        cfg.groups.forEach { g ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("◎ ${g.name} · ${if (g.type == RouteGroup.TYPE_URLTEST) "авто" else "ручной"}: " + g.members.joinToString { targetName(it, servers, cfg.groups) },
                    Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = { c.removeRouteGroup(g) }) { Text("Удалить", color = Danger) }
            }
        }
        var name by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(RouteGroup.TYPE_URLTEST) }
        var members by remember { mutableStateOf(setOf<String>()) }
        var picker by remember { mutableStateOf(false) }
        val options = (listOf(RouteTarget.PROXY, RouteTarget.DIRECT, RouteTarget.DPI, RouteTarget.TGWS) + nodes.map { RouteTarget.node(it.id) })
            .map { it to targetName(it, servers, cfg.groups) }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название группы") })
        val auto = "Авто — самый быстрый живой выход, с переключением при сбое"
        val manual = "Ручной — выход выбираете вы"
        DropField("Тип группы", if (type == RouteGroup.TYPE_URLTEST) auto else manual,
            listOf(RouteGroup.TYPE_URLTEST to auto, RouteGroup.TYPE_SELECTOR to manual), { type = it }, selected = type)
        Box {
            OutlinedTextField(if (members.isEmpty()) "Нажмите, чтобы выбрать выходы" else members.joinToString { targetName(it, servers, cfg.groups) }, {}, Modifier.fillMaxWidth(),
                readOnly = true, singleLine = true, label = { Text("Состав группы") }, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
            Box(Modifier.matchParentSize().clickable { picker = true })
        }
        Button(onClick = { if (name.isNotBlank() && members.isNotEmpty()) { c.addRouteGroup(RouteGroup(name.trim(), type, members.toList())); name = ""; members = emptySet() } }) {
            Text("Создать группу")
        }
        if (picker) MultiPickDialog("Состав группы", options, members, { t -> members = if (t in members) members - t else members + t }, { picker = false })
    }
}

@Composable
private fun ChainsSection(c: AppController, ui: UiState) {
    val servers = ui.data.servers
    val chainable = servers.filter { it.protocolId in setOf("vless", "vmess", "trojan", "ss") }
    val nodes = servers.filter { it.protocol?.engine == Engine.SINGBOX || it.protocol?.engine == Engine.OPENFLUX || it.protocol?.engine == Engine.OLCRTC }
    Section("Цепочки «протокол внутри протокола»") {
        Text("Пускать соединение самого сервера через обход DPI или через другой сервер (только TCP-протоколы). Цепочка может быть любой глубины.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chainable.isEmpty()) Text("Добавьте сервер VLESS, VMess, Trojan или Shadowsocks.", fontSize = 12.sp)
        chainable.forEach { s ->
            val via = viaOf(s)
            val opts = listOf<String?>(null, RouteTarget.DPI) + nodes.filter { it.id != s.id }.map { RouteTarget.node(it.id) }
            DropField<String?>(s.name, if (via == null) "Напрямую" else targetName(via, servers, emptyList()),
                opts.map { it to (if (it == null) "Напрямую" else targetName(it, servers, emptyList())) }, { c.setServerVia(s, it) }, selected = via)
        }
        Text("UDP-протоколы (Hysteria2, TUIC, WireGuard) в цепочку не ставятся.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GeoLayerSection(c: AppController, ui: UiState) {
    val g = ui.data.settings.routing.geo
    val entries = remember(ui.geoTick) { Platform.geoStore.entries() }
    Section("Geo-базы") {
        Text("Списки стран и сайтов для маршрутизации Hydra скачивает сама и заменяет атомарно. Вшитые списки — запасные: на первый запуск и офлайн.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Автообновление (раз в ${g.intervalHours} ч)", Modifier.weight(1f))
            Switch(checked = g.autoUpdate, onCheckedChange = { v -> c.setGeo { it.copy(autoUpdate = v) } })
        }
        ChipRow {
            listOf(6, 12, 24, 72, 168).forEach { h -> FilterChip(selected = g.intervalHours == h, onClick = { c.setGeo { it.copy(intervalHours = h) } }, label = { Text("$h ч") }) }
        }
        Button(onClick = { c.updateGeo(manual = true) }) { Text("Обновить сейчас") }
        Text("IP-диапазоны из:", fontSize = 12.sp)
        ChipRow { GeoSources.ALL.forEach { s -> FilterChip(selected = g.ipSource == s.id, onClick = { c.setGeo { it.copy(ipSource = s.id) } }, label = { Text(s.title) }) } }
        Text("Домены из:", fontSize = 12.sp)
        ChipRow { GeoSources.ALL.forEach { s -> FilterChip(selected = g.siteSource == s.id, onClick = { c.setGeo { it.copy(siteSource = s.id) } }, label = { Text(s.title) }) } }
        Text("Зеркала пробуются по очереди, поэтому заблокированный GitHub не мешает обновлению. Источники .dat конвертируются на устройстве.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        var name by remember { mutableStateOf("") }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Имя набора, например ru-blocked или youtube") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { val n = name.trim().lowercase(); if (n.isNotEmpty()) { c.setGeo { it.copy(extraIp = (it.extraIp + n).distinct()) }; name = "" } }) { Text("+ список IP") }
            OutlinedButton(onClick = { val n = name.trim().lowercase(); if (n.isNotEmpty()) { c.setGeo { it.copy(extraSite = (it.extraSite + n).distinct()) }; name = "" } }) { Text("+ список доменов") }
        }
        (g.extraIp.map { GeoKind.IP to it } + g.extraSite.map { GeoKind.SITE to it }).forEach { (k, n) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${k.dir}: $n", Modifier.weight(1f), fontSize = 13.sp)
                TextButton(onClick = { c.setGeo { if (k == GeoKind.IP) it.copy(extraIp = it.extraIp - n) else it.copy(extraSite = it.extraSite - n) } }) { Text("Убрать", color = Danger) }
            }
        }

        Text("Свой список — прямая ссылка (https): текстовый список (CIDR или домены), готовый .srs или запись из .dat.", fontSize = 12.sp)
        var ckind by remember { mutableStateOf(GeoKind.IP) }
        var ctype by remember { mutableStateOf(CustomGeoSource.TYPE_LIST) }
        var cname by remember { mutableStateOf("") }
        var curl by remember { mutableStateOf("") }
        ChipRow {
            FilterChip(selected = ckind == GeoKind.IP, onClick = { ckind = GeoKind.IP }, label = { Text("geoip") })
            FilterChip(selected = ckind == GeoKind.SITE, onClick = { ckind = GeoKind.SITE }, label = { Text("geosite") })
            listOf(CustomGeoSource.TYPE_LIST, CustomGeoSource.TYPE_SRS, CustomGeoSource.TYPE_DAT).forEach { t ->
                FilterChip(selected = ctype == t, onClick = { ctype = t }, label = { Text(".$t") })
            }
        }
        OutlinedTextField(cname, { cname = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Имя") })
        OutlinedTextField(curl, { curl = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("URL") })
        Button(onClick = {
            val n = cname.trim().lowercase(); val u = curl.trim()
            if (n.isNotEmpty() && u.startsWith("https://")) {
                c.setGeo { it.copy(custom = it.custom.filterNot { x -> x.kind == ckind && x.name == n } + CustomGeoSource(ckind, n, u, ctype)) }
                cname = ""; curl = ""
            }
        }) { Text("Сохранить") }
        g.custom.forEach { x ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${x.kind.dir}/${x.name} ← ${x.url}", Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { c.setGeo { it.copy(custom = it.custom - x) } }) { Text("Убрать", color = Danger) }
            }
        }

        Text("Скачано", fontWeight = FontWeight.Medium)
        if (entries.isEmpty()) Text("Пока ничего не скачано — работают вшитые списки", fontSize = 12.sp)
        val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${e.kind.dir}/${e.name} · ${e.source} · ${e.size / 1024} КБ · ${df.format(Date(e.updatedAt))} · ${e.sha256.take(8)}", Modifier.weight(1f), fontSize = 12.sp)
                if (e.hasPrev) TextButton(onClick = { c.rollbackGeo(e.kind, e.name) }) { Text("Откат") }
                TextButton(onClick = { c.removeGeo(e.kind, e.name) }) { Text("Удалить", color = Danger) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// 0.7.15: настройки подбора как в ByeByeDPI, полный просмотр стратегий, Telegram → локальный прокси
// ---------------------------------------------------------------------------------------------------------------------

private val groupNames = mapOf(
    "youtube" to "YouTube", "discord" to "Discord", "telegram" to "Telegram", "general" to "Общие",
    "cloudflare" to "Cloudflare", "googlevideo" to "Googlevideo", "social" to "Соцсети", "turkiye" to "Türkiye", "custom" to "Свой сайт",
)

/** Окно «Списки доменов»: галочки у встроенных и своих списков, «Добавить список», удаление своих. */
@Composable
private fun SiteListsDialog(c: AppController, ui: UiState, onDismiss: () -> Unit) {
    val ps = ui.data.settings.routing.routes.dpi.probe
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var domains by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Списки доменов") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(DpiStrategies.GROUP_ORDER.size) { i ->
                    val g = DpiStrategies.GROUP_ORDER[i]
                    SiteListRow(groupNames[g] ?: g, DpiStrategies.SITES[g].orEmpty(), g in ps.groups, null) {
                        c.setProbe { p -> p.copy(groups = if (g in p.groups) p.groups - g else p.groups + g) }
                    }
                }
                items(ps.custom.size) { i ->
                    val x = ps.custom[i]
                    SiteListRow(x.name, x.domains, x.name in ps.groups, { c.setProbe { p -> p.copy(custom = p.custom - x, groups = p.groups - x.name) } }) {
                        c.setProbe { p -> p.copy(groups = if (x.name in p.groups) p.groups - x.name else p.groups + x.name) }
                    }
                }
                item {
                    if (adding) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название списка") })
                        OutlinedTextField(domains, { domains = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("Домены, по одному в строке") })
                        Button(onClick = {
                            val list = domains.split('\n', ' ', ',', ';').map { it.trim().removePrefix("https://").removePrefix("http://").substringBefore('/') }.filter { it.isNotEmpty() }.distinct()
                            val n = name.trim()
                            if (n.isNotEmpty() && list.isNotEmpty() && n.lowercase() !in DpiStrategies.GROUP_ORDER && n !in groupNames.values) {
                                c.setProbe { p -> p.copy(custom = p.custom.filterNot { it.name == n } + ru.gidravpn.hydra.data.dpi.CustomSiteList(n, list), groups = p.groups + n) }
                                name = ""; domains = ""; adding = false
                            }
                        }) { Text("Сохранить список") }
                    } else OutlinedButton(onClick = { adding = true }) { Text("Добавить список") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}

@Composable
private fun SiteListRow(title: String, domains: List<String>, on: Boolean, onDelete: (() -> Unit)?, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(on, null)
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(domains.take(4).joinToString(", ") + if (domains.size > 4) ", …" else "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Удалить", color = Danger) }
    }
}

/** Результат подбора целиком: сводка по спискам, ПОЛНАЯ строка стратегии (выделяется), «Использовать», «Сохранить профилем», «Копировать». */
@Composable
private fun StrategyResult(c: AppController, i: Int, r: ru.gidravpn.hydra.data.dpi.DpiProbe.Result, best: Boolean) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Section((if (best) "★ " else "") + "Вариант ${i + 1} — открылось ${r.ok} из ${r.total} (${r.percent}%)") {
        Text(r.groups.joinToString(" · ") { "${groupNames[it.name] ?: it.name} ${it.ok}/${it.total}" }, fontSize = 12.sp)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(r.strategy, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ChipRow {
            Button(onClick = { c.useDpiStrategy(r.strategy) }) { Text("Использовать") }
            OutlinedButton(onClick = { c.addByeDpiProfile(r.strategy) }) { Text("Сохранить профилем") }
            OutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(r.strategy)); copied = true }) { Text(if (copied) "Скопировано" else "Копировать") }
        }
    }
}

/** «Настройки подбора» — те же параметры, что в ByeByeDPI: пауза, число запросов, параллельность, таймаут, SNI, свой список стратегий. */
@Composable
private fun DpiProbeSettingsSection(c: AppController, ui: UiState) {
    val dpi = ui.data.settings.routing.routes.dpi
    val ps = dpi.probe
    var sni by remember(dpi.fakeSni) { mutableStateOf(dpi.fakeSni) }
    var own by remember(ps.customStrategies) { mutableStateOf(ps.customStrategies) }
    Section("Настройки подбора") {
        Text("Как проверять стратегии. Больше запросов и пауза — стабильнее, больше параллельных запросов — быстрее.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropField("Ожидание между проверками", "${ps.delaySec} с", (0..10).map { it to "$it с" }, { v -> c.setProbe { it.copy(delaySec = v) } }, selected = ps.delaySec)
        Text("Увеличение значения повышает стабильность на слабой сети", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropField("Количество запросов к домену", "${ps.requests}", (1..5).map { it to "$it" }, { v -> c.setProbe { it.copy(requests = v) } }, selected = ps.requests)
        Text("Сайт считается открытым, если ответило большинство попыток. Больше — точнее, но дольше", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropField("Максимум параллельных запросов", "${ps.parallel}", listOf(1, 2, 4, 8, 12, 16, 20, 32, 50).map { it to "$it" }, { v -> c.setProbe { it.copy(parallel = v) } }, selected = ps.parallel)
        Text("Больше — быстрее, но может снизить стабильность", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropField("Таймаут ответа домена", "${ps.timeoutSec} с", (1..15).map { it to "$it с" }, { v -> c.setProbe { it.copy(timeoutSec = v) } }, selected = ps.timeoutSec)
        Text("Больше — медленнее, но стабильнее и точнее", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(sni, { sni = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("SNI фейк-пакетов") })
        Text("Подставляется вместо {sni} в стратегиях — и при проверке, и в обычной работе", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { c.setDpi { it.copy(fakeSni = sni.ifBlank { DpiStrategies.FAKE_SNI }) } }) { Text("Применить") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Свой список стратегий")
                Text("По одной в строке, строки с # пропускаются. Пока включено, встроенные 60 не проверяются.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = ps.customStrategiesOn, onCheckedChange = { v -> c.setProbe { it.copy(customStrategiesOn = v) } })
        }
        if (ps.customStrategiesOn) {
            OutlinedTextField(own, { own = it }, Modifier.fillMaxWidth(), minLines = 4)
            Button(onClick = { c.setProbe { it.copy(customStrategies = own) } }) { Text("Применить список") }
        }
    }
}

/** Передать Telegram локальный прокси Hydra (`tg://proxy?…` для TG WS, `tg://socks?…` для остального): Telegram спросит, включить ли. Прокси работает, пока Hydra подключена. */
@Composable
internal fun TelegramProxySection(c: AppController, ui: UiState, compact: Boolean = false) {
    val port = remember(ui.data.settings.routing.routes) { c.telegramProxyPort() }
    val tg = TgWsPreset.isApplied(ui.data.settings.routing.routes.rules)
    val body: @Composable () -> Unit = {
        if (!compact) Text("Передаёт Telegram прокси, который Hydra поднимает на этом компьютере. Telegram спросит, включить ли прокси.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Адрес 127.0.0.1:$port (${if (tg) "MTProto-прокси" else "SOCKS5"}) · ${if (tg) "Telegram по WebSocket" else "обход DPI"}", fontSize = 12.sp)
        Button(onClick = { c.openTelegramProxy(port) }) { Text("Подключить Telegram к прокси") }
        Text("Прокси работает, пока Hydra подключена.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Section("Telegram через локальный прокси") { body() }
}
