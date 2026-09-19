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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.BetaBadge
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*
import androidx.compose.ui.graphics.Color

@Composable
fun ServersScreen(vm: MainViewModel, onSelected: () -> Unit) {
    val servers by vm.servers.collectAsState()
    val subscriptions by vm.subscriptions.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    val measuringIds by vm.measuringIds.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    val importMessage by vm.importMessage.collectAsState()
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

    val grouped = remember(servers, subscriptions) {
        val bySub = servers.groupBy { it.subscriptionId }
        val ordered = mutableListOf<Pair<Subscription?, List<ServerProfile>>>()
        bySub[null]?.let { ordered.add(null to it) }
        subscriptions.forEach { sub -> bySub[sub.id]?.let { ordered.add(sub to it) } }
        ordered
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tab_servers), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(stringResource(R.string.servers_refresh_ping), color = AccentCyan, fontSize = 12.sp,
                modifier = Modifier.clickableNoRipple { vm.measureAllPings() })
        }
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientButton(stringResource(R.string.servers_add), Modifier.weight(1f)) { showAdd = true }
            OutlinedActionButton(stringResource(R.string.servers_import), Modifier.weight(1f)) { showImport = true }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedActionButton(stringResource(R.string.servers_scan_qr), Modifier.weight(1f)) { startScan() }
            OutlinedActionButton(stringResource(R.string.servers_qr_photo), Modifier.weight(1f)) { startPhoto() }
        }
        importMessage?.let { msg ->
            Spacer(Modifier.height(12.dp))
            ru.gidravpn.hydra.ui.components.Card(
                Modifier.fillMaxWidth().clickableNoRipple { vm.dismissImportMessage() },
                borderColor = AccentCyan
            ) {
                Text(msg, color = TextPrimary, fontSize = 13.sp)
                Text(stringResource(R.string.backup_dismiss), color = TextMuted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            grouped.forEach { (sub, list) ->
                if (sub != null) {
                    item(key = "header_${sub.id}") { SubscriptionHeader(sub, list.size) }
                }
                items(list, key = { it.id }) { s ->
                    ServerCard(s, selected = (selectedId ?: servers.firstOrNull()?.id) == s.id,
                        measuring = s.id in measuringIds,
                        onClick = { vm.select(s.id); onSelected() },
                        onDelete = { vm.delete(s) },
                        onMeasure = { vm.measurePing(s) })
                }
            }
        }
    }

    if (showAdd) AddServerDialog(
        onDismiss = { showAdd = false },
        onSave = { n, a, p, proto -> vm.addServer(n, a, p, proto); showAdd = false }
    )
    if (showImport) ImportDialog(
        onDismiss = { showImport = false },
        onImport = { text, name -> vm.importAuto(listOf(text), name); showImport = false },
        onScan = { showImport = false; startScan() },
        onPhoto = { showImport = false; startPhoto() },
    )
}

@Composable
private fun SubscriptionHeader(sub: Subscription, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(sub.name.uppercase(), color = TextMuted, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        Text("$count", color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun ServerCard(
    s: ServerProfile, selected: Boolean, measuring: Boolean,
    onClick: () -> Unit, onDelete: () -> Unit, onMeasure: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Surface,
            title = { Text(stringResource(R.string.servers_delete_title), color = TextPrimary) },
            text = { Text(stringResource(R.string.servers_delete_msg, s.name), color = TextMuted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = PingSlow)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel), color = TextMuted) }
            }
        )
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) SurfaceDim else CardBg)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) AccentCyan else Border, RoundedCornerShape(16.dp))
            .clickableNoRipple(onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(SurfaceDim),
            contentAlignment = Alignment.Center) {
            Text(s.flag, fontSize = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (s.protocol?.beta == true) {
                    Spacer(Modifier.width(6.dp))
                    BetaBadge()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.protocol?.displayName ?: s.protocolId.uppercase(), color = TextMuted, fontSize = 12.sp)
                Text(" • ", color = TextMuted, fontSize = 12.sp)
                Text(
                    when {
                        measuring -> stringResource(R.string.servers_measuring)
                        s.pingMs >= 0 -> stringResource(R.string.servers_ping_ms, s.pingMs)
                        else -> stringResource(R.string.servers_measure)
                    },
                    color = if (measuring) TextMuted else pingColor(s.pingMs),
                    fontSize = 12.sp,
                    modifier = Modifier.clickableNoRipple { if (!measuring) onMeasure() }
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        s.protocol?.let { ProtocolChip(it.shortCode) }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.servers_delete_title), tint = TextMuted)
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
