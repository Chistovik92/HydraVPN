package ru.gidravpn.hydra.ui.screens

import androidx.compose.ui.res.stringResource
import ru.gidravpn.hydra.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.BetaBadge
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.components.humanBytes
import ru.gidravpn.hydra.ui.theme.*
import androidx.compose.ui.graphics.Color

/**
 * Экран «Серверы» (переделан в 0.6.22): компактная шапка с действиями-иконками, поиск и сортировка
 * по пингу, меню «Добавить» снизу вместо четырёх больших кнопок, подписки — карточками с трафиком
 * и действиями, серверы — компактными строками с меню (пинг / поделиться / удалить).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(vm: MainViewModel, onSelected: () -> Unit) {
    val servers by vm.servers.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    val measuringIds by vm.measuringIds.collectAsState()
    val refreshingSubs by vm.refreshingSubs.collectAsState()
    val toggles by vm.engineToggles.collectAsState()
    val importMessage by vm.importMessage.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var showAddSheet by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var sortByPing by remember { mutableStateOf(false) }
    var shareTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val shareNoLink = stringResource(R.string.share_no_link)
    val clipboardEmpty = stringResource(R.string.srv_clipboard_empty)

    val scanPrompt = stringResource(R.string.qr_scan_prompt)
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { res -> res.contents?.let { vm.importAuto(listOf(it)) } }
    val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.importFromImage(it) } }
    val startScan = {
        scanLauncher.launch(
            com.journeyapps.barcodescanner.ScanOptions()
                .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                .setPrompt(scanPrompt).setBeepEnabled(false).setOrientationLocked(false)
        )
    }
    val startPhoto = {
        photoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val pasteClipboard: () -> Unit = {
        val text = ctx.getSystemService(android.content.ClipboardManager::class.java)
            ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (text.isNullOrBlank()) android.widget.Toast.makeText(ctx, clipboardEmpty, android.widget.Toast.LENGTH_SHORT).show()
        else vm.importAuto(listOf(text))
    }

    // Фильтр и сортировка применяются к серверам внутри каждой группы.
    val q = query.trim()
    val visible: (List<ServerProfile>) -> List<ServerProfile> = { list ->
        val f = if (q.isEmpty()) list else list.filter {
            it.name.contains(q, true) || (it.protocol?.displayName?.contains(q, true) == true)
        }
        if (sortByPing) f.sortedBy { if (it.pingMs >= 0) it.pingMs else Int.MAX_VALUE } else f
    }
    val bySub = remember(servers) { servers.groupBy { it.subscriptionId } }
    val standalone = visible(bySub[null].orEmpty())
    val effectiveSelected = selectedId ?: servers.firstOrNull()?.id
    val xrayBuilt = ru.gidravpn.hydra.BuildConfig.XRAY_AVAILABLE

    val serverRow: @Composable (ServerProfile) -> Unit = { s ->
        ServerRow(
            s = s,
            selected = effectiveSelected == s.id,
            measuring = s.id in measuringIds,
            engineOff = toggles.isDisabled(s.protocol, xrayBuilt),
            onClick = { vm.select(s.id); onSelected() },
            onMeasure = { vm.measurePing(s) },
            onShare = {
                val link = ru.gidravpn.hydra.data.subscription.LinkBuilder.toLink(s)
                if (link != null) shareTarget = s.name to link
                else android.widget.Toast.makeText(ctx, shareNoLink, android.widget.Toast.LENGTH_SHORT).show()
            },
            onDelete = { vm.delete(s) },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // --- Шапка ---
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tab_servers), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text(stringResource(R.string.srv_summary, servers.size, subscriptions.size), color = TextMuted, fontSize = 12.sp)
            }
            HeaderIcon(Icons.Filled.Search, stringResource(R.string.srv_search), active = searchOpen) {
                searchOpen = !searchOpen; if (!searchOpen) query = ""
            }
            HeaderIcon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.srv_sort_ping), active = sortByPing) { sortByPing = !sortByPing }
            HeaderIcon(Icons.Filled.NetworkCheck, stringResource(R.string.servers_refresh_ping)) { vm.measureAllPings() }
            if (subscriptions.isNotEmpty()) {
                HeaderIcon(Icons.Filled.Refresh, stringResource(R.string.sub_refresh_all), active = refreshingSubs.isNotEmpty()) {
                    vm.refreshAllSubscriptions()
                }
            }
        }
        if (searchOpen) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.srv_search_hint), color = TextMuted, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextMuted) },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
                    focusedContainerColor = InputBg, unfocusedContainerColor = InputBg,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        // Главное действие — одна кнопка, остальное в меню снизу.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(AccentCyan, AccentIndigo)))
                .clickableNoRipple { showAddSheet = true }.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.srv_add), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        importMessage?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg)
                    .border(1.dp, AccentCyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.dismissImportMessage() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, stringResource(R.string.backup_dismiss), tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (servers.isEmpty() && subscriptions.isEmpty()) {
            EmptyServers(onPaste = pasteClipboard, onScan = startScan, onAdd = { showAddSheet = true })
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                subscriptions.forEach { sub ->
                    val all = bySub[sub.id].orEmpty()
                    val list = visible(all)
                    if (q.isNotEmpty() && list.isEmpty()) return@forEach
                    item(key = "sub_${sub.id}") {
                        SubscriptionCard(
                            sub = sub, count = all.size,
                            refreshing = sub.id in refreshingSubs,
                            expanded = !sub.collapsed || q.isNotEmpty(),
                            onToggle = { vm.toggleSubscriptionCollapsed(sub) },
                            onRefresh = { vm.refreshSubscription(sub) },
                            onPing = { vm.pingSubscription(sub) },
                            onShare = { shareTarget = sub.displayName to sub.url },
                            onAutoUpdate = { vm.setSubscriptionAutoUpdate(sub, it) },
                            onDelete = { vm.deleteSubscription(sub) },
                        )
                    }
                    if (!sub.collapsed || q.isNotEmpty()) {
                        items(list, key = { it.id }) { s -> Box(Modifier.padding(start = 10.dp)) { serverRow(s) } }
                    }
                }
                if (standalone.isNotEmpty()) {
                    item(key = "standalone_header") {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sub_standalone).uppercase(), color = TextMuted, fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, modifier = Modifier.weight(1f))
                            Text("${standalone.size}", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    items(standalone, key = { it.id }) { s -> serverRow(s) }
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }, containerColor = Surface) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.srv_add), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp))
                SheetItem(Icons.Filled.ContentPaste, stringResource(R.string.srv_add_paste), stringResource(R.string.srv_add_paste_desc)) {
                    showAddSheet = false; pasteClipboard()
                }
                SheetItem(Icons.Filled.QrCodeScanner, stringResource(R.string.srv_add_scan), stringResource(R.string.srv_add_scan_desc)) {
                    showAddSheet = false; startScan()
                }
                SheetItem(Icons.Filled.Image, stringResource(R.string.srv_add_photo), stringResource(R.string.srv_add_photo_desc)) {
                    showAddSheet = false; startPhoto()
                }
                SheetItem(Icons.Filled.Link, stringResource(R.string.srv_add_link), stringResource(R.string.srv_add_link_desc)) {
                    showAddSheet = false; showImport = true
                }
                SheetItem(Icons.Filled.Edit, stringResource(R.string.srv_add_manual), stringResource(R.string.srv_add_manual_desc)) {
                    showAddSheet = false; showManual = true
                }
            }
        }
    }
    shareTarget?.let { (title, link) ->
        ru.gidravpn.hydra.ui.components.ShareDialog(title, link) { shareTarget = null }
    }
    if (showManual) AddServerDialog(
        onDismiss = { showManual = false },
        onSave = { n, a, p, proto -> vm.addServer(n, a, p, proto); showManual = false }
    )
    if (showImport) ImportDialog(
        onDismiss = { showImport = false },
        onImport = { text, name -> vm.importAuto(listOf(text), name); showImport = false },
        onScan = { showImport = false; startScan() },
        onPhoto = { showImport = false; startPhoto() },
    )
}

@Composable
private fun HeaderIcon(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (active) AccentCyan.copy(alpha = 0.18f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, description, tint = if (active) AccentCyan else TextSecondary, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickableNoRipple(onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AccentCyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center) { Icon(icon, null, tint = AccentCyan, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyServers(onPaste: () -> Unit, onScan: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg)
            .border(1.dp, Border, RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.srv_empty_title), color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.srv_empty_desc), color = TextMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPaste) { Text(stringResource(R.string.srv_add_paste), color = AccentCyan) }
            TextButton(onClick = onScan) { Text(stringResource(R.string.srv_add_scan), color = AccentCyan) }
            TextButton(onClick = onAdd) { Text(stringResource(R.string.srv_more), color = AccentCyan) }
        }
    }
}

/** Подписка (0.6.22): название от панели, трафик шкалой, срок, действия иконками, спойлер. */
@Composable
private fun SubscriptionCard(
    sub: Subscription,
    count: Int,
    refreshing: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onPing: () -> Unit,
    onShare: () -> Unit,
    onAutoUpdate: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Surface,
            title = { Text(stringResource(R.string.sub_delete_title), color = TextPrimary) },
            text = { Text(stringResource(R.string.sub_delete_msg, sub.displayName, count), color = TextMuted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete), color = PingSlow) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel), color = TextMuted) }
            },
        )
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(18.dp)).background(CardBg)
            .border(1.dp, AccentCyan.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickableNoRipple(onToggle).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(AccentCyan.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center) {
                Text(sub.displayName.take(1).uppercase(), color = AccentCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sub.displayName, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val updated = if (sub.lastUpdated > 0) android.text.format.DateUtils.getRelativeTimeSpanString(
                    sub.lastUpdated, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                else stringResource(R.string.sub_never)
                Text(stringResource(R.string.sub_servers_count, count) + " · " + stringResource(R.string.sub_updated, updated),
                    color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = TextMuted)
        }

        // Трафик и срок — если панель их сообщила.
        if (sub.totalBytes > 0 || sub.usedBytes > 0 || sub.expireAt > 0 || sub.autoUpdate) {
            if (sub.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (sub.usedBytes.toFloat() / sub.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (sub.usedBytes > sub.totalBytes * 0.9) PingSlow else AccentCyan,
                    trackColor = Border,
                )
            }
            Row {
                val traffic = when {
                    sub.totalBytes > 0 -> "${humanBytes(sub.usedBytes)} / ${humanBytes(sub.totalBytes)}"
                    sub.usedBytes > 0 -> humanBytes(sub.usedBytes)
                    else -> ""
                }
                val auto = if (sub.autoUpdate) stringResource(R.string.sub_auto_every, sub.autoUpdateHours) else ""
                Text(listOf(traffic, auto).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (sub.expireAt > 0) Text(stringResource(R.string.sub_until,
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(sub.expireAt * 1000))),
                    color = TextSecondary, fontSize = 11.sp)
            }
        }
        if (sub.lastError.isNotBlank()) Text(sub.lastError, color = Danger, fontSize = 11.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionChip(Icons.Filled.Refresh, stringResource(if (refreshing) R.string.sub_refreshing else R.string.sub_refresh_short),
                enabled = !refreshing, onClick = onRefresh)
            Spacer(Modifier.width(6.dp))
            ActionChip(Icons.Filled.NetworkCheck, stringResource(R.string.sub_ping), onClick = onPing)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Share, stringResource(R.string.share_action), tint = AccentCyan, modifier = Modifier.size(20.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, null, tint = TextMuted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sub_auto_update)) },
                        trailingIcon = { Checkbox(checked = sub.autoUpdate, onCheckedChange = null) },
                        onClick = { menu = false; onAutoUpdate(!sub.autoUpdate) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sub_delete_title), color = PingSlow) },
                        leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = PingSlow) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(SurfaceDim)
            .clickableNoRipple { if (enabled) onClick() }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) AccentCyan else TextMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = if (enabled) AccentCyan else TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Компактная строка сервера (0.6.22): флаг, имя, протокол и пинг; действия — в меню. */
@Composable
private fun ServerRow(
    s: ServerProfile, selected: Boolean, measuring: Boolean, engineOff: Boolean,
    onClick: () -> Unit, onMeasure: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Surface,
            title = { Text(stringResource(R.string.servers_delete_title), color = TextPrimary) },
            text = { Text(stringResource(R.string.servers_delete_msg, s.name), color = TextMuted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete), color = PingSlow) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel), color = TextMuted) }
            },
        )
    }
    // Флаг-эмодзи из названия (панели кладут его в начало) — в кружок, из имени убираем.
    val flag = Regex("^\\p{So}\\p{So}|^[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]").find(s.name)?.value
    val title = if (flag != null) s.name.removePrefix(flag).trim() else s.name
    Row(
        Modifier.fillMaxWidth().alpha(if (engineOff) 0.45f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) AccentCyan.copy(alpha = 0.10f) else CardBg)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) AccentCyan else Border, RoundedCornerShape(14.dp))
            .clickableNoRipple(onClick)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(SurfaceDim), contentAlignment = Alignment.Center) {
            Text(flag ?: s.flag, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title.ifBlank { s.name }, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (s.protocol?.beta == true) { Spacer(Modifier.width(6.dp)); BetaBadge() }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                s.protocol?.let { ProtocolChip(it.shortCode) }
                Spacer(Modifier.width(8.dp))
                if (engineOff) {
                    Text(stringResource(R.string.srv_engine_off), color = PingSlow, fontSize = 11.sp)
                } else {
                    Text(
                        when {
                            measuring -> stringResource(R.string.servers_measuring)
                            s.pingMs >= 0 -> stringResource(R.string.servers_ping_ms, s.pingMs)
                            else -> stringResource(R.string.servers_measure)
                        },
                        color = if (measuring) TextMuted else pingColor(s.pingMs), fontSize = 12.sp,
                        modifier = Modifier.clickableNoRipple { if (!measuring) onMeasure() },
                    )
                }
            }
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = AccentCyan, modifier = Modifier.size(20.dp))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.MoreVert, null, tint = TextMuted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.sub_ping)) },
                    leadingIcon = { Icon(Icons.Filled.NetworkCheck, null) }, onClick = { menu = false; onMeasure() })
                DropdownMenuItem(text = { Text(stringResource(R.string.share_action)) },
                    leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = PingSlow) },
                    leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = PingSlow) },
                    onClick = { menu = false; confirmDelete = true })
            }
        }
    }
}

// Пороги как в макетах: быстрый — изумруд, средний — янтарь, медленный — оранжевый.
// Не завязано на палитру темы: смысл цвета одинаков в обеих.
@Composable
private fun pingColor(pingMs: Int): Color = when {
    pingMs in 0..99 -> PingFast
    pingMs in 100..200 -> PingMed
    pingMs > 200 -> PingSlow
    else -> TextMuted   // -1 — ещё не измеряли
}

@Composable
private fun ProtocolChip(code: String) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp))
            .background(AccentIndigo.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(code, color = AccentIndigo, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GradientButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(AccentCyan, AccentIndigo)))
            .clickableNoRipple(onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun OutlinedActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(CardBg)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun AddServerDialog(onDismiss: () -> Unit, onSave: (String, String, Int, Protocol) -> Unit) {
    var name by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var proto by remember { mutableStateOf(Protocol.VLESS) }
    var expanded by remember { mutableStateOf(false) }
    val defaultServerName = stringResource(R.string.default_server_name)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(stringResource(R.string.servers_new_title), color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(stringResource(R.string.field_name), name) { name = it }
                Field(stringResource(R.string.field_address), addr) { addr = it }
                Field(stringResource(R.string.field_port), port) { port = it.filter(Char::isDigit) }
                Box {
                    OutlinedActionButton(stringResource(R.string.servers_protocol, proto.displayName) + if (proto.beta) " [BETA]" else "", Modifier.fillMaxWidth()) { expanded = true }
                    DropdownMenu(expanded, { expanded = false }) {
                        Protocol.entries.forEach { p ->
                            DropdownMenuItem(text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.displayName)
                                    if (p.beta) {
                                        Spacer(Modifier.width(6.dp))
                                        BetaBadge()
                                    }
                                }
                            },
                                onClick = { proto = p; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.ifBlank { defaultServerName }, addr, port.toIntOrNull() ?: 443, proto) }) {
                Text(stringResource(R.string.action_save), color = AccentCyan)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel), color = TextMuted) } }
    )
}

@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImport: (text: String, subName: String?) -> Unit,
    onScan: () -> Unit,
    onPhoto: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var value by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(stringResource(R.string.import_title), color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.import_hint),
                    color = TextMuted, fontSize = 12.sp)
                Field(stringResource(R.string.import_link_field), value) { value = it }
                Text(stringResource(R.string.import_paste), color = AccentCyan, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickableNoRipple {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            ?.let { value = it }
                    })
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.servers_scan_qr), color = AccentCyan, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.clickableNoRipple(onScan))
                    Text(stringResource(R.string.servers_qr_photo), color = AccentCyan, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.clickableNoRipple(onPhoto))
                }
                Field(stringResource(R.string.import_sub_name_field), name) { name = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onImport(value, name.takeIf { it.isNotBlank() })
            }) { Text(stringResource(R.string.import_action), color = AccentCyan) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel), color = TextMuted) } }
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = TextMuted, fontSize = 12.sp) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
            focusedContainerColor = InputBg, unfocusedContainerColor = InputBg
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
