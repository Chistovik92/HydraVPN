package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
            Text("Серверы", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text("Обновить пинг", color = AccentCyan, fontSize = 12.sp,
                modifier = Modifier.clickableNoRipple { vm.measureAllPings() })
        }
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientButton("+ Добавить сервер", Modifier.weight(1f)) { showAdd = true }
            OutlinedActionButton("📥 Импорт", Modifier.weight(1f)) { showImport = true }
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
        onLink = { vm.importLink(it); showImport = false },
        onSubscription = { name, url -> vm.addSubscription(name, url); showImport = false }
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
                        measuring -> "измерение…"
                        s.pingMs >= 0 -> "${s.pingMs}мс"
                        else -> "измерить"
                    },
                    color = if (measuring) TextMuted else pingColor(s.pingMs),
                    fontSize = 12.sp,
                    modifier = Modifier.clickableNoRipple { if (!measuring) onMeasure() }
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        s.protocol?.let { ProtocolChip(it.shortCode) }
    }
}

@Composable
private fun pingColor(pingMs: Int): Color = when {
    pingMs in 0..99 -> AccentCyan
    pingMs in 100..200 -> Warning
    else -> TextMuted
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Новый сервер", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Название", name) { name = it }
                Field("Адрес", addr) { addr = it }
                Field("Порт", port) { port = it.filter(Char::isDigit) }
                Box {
                    OutlinedActionButton("Протокол: ${proto.displayName}${if (proto.beta) " [BETA]" else ""}", Modifier.fillMaxWidth()) { expanded = true }
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
            TextButton(onClick = { onSave(name.ifBlank { "Сервер" }, addr, port.toIntOrNull() ?: 443, proto) }) {
                Text("Сохранить", color = AccentCyan)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", color = TextMuted) } }
    )
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onLink: (String) -> Unit, onSubscription: (String, String) -> Unit) {
    var value by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("Подписка") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Импорт", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Вставьте ссылку (vless://, vmess://, trojan://, ss://, hysteria2://, tuic://, wireguard://, awg://, sstp://, l2tp://), .conf WireGuard/AmneziaWG или URL подписки.",
                    color = TextMuted, fontSize = 12.sp)
                Field("Ссылка / URL", value) { value = it }
                Field("Имя подписки (если URL)", name) { name = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = value.trim()
                if (v.startsWith("http")) onSubscription(name, v) else onLink(v)
            }) { Text("Импортировать", color = AccentCyan) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена", color = TextMuted) } }
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
