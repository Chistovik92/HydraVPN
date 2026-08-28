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
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

@Composable
fun ServersScreen(vm: MainViewModel, onSelected: () -> Unit) {
    val servers by vm.servers.collectAsState()
    val selectedId by vm.selectedId.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Серверы", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GradientButton("+ Добавить сервер", Modifier.weight(1f)) { showAdd = true }
            OutlinedActionButton("📥 Импорт", Modifier.weight(1f)) { showImport = true }
        }
        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(servers, key = { it.id }) { s ->
                ServerCard(s, selected = (selectedId ?: servers.firstOrNull()?.id) == s.id,
                    onClick = { vm.select(s.id); onSelected() },
                    onDelete = { vm.delete(s) })
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
private fun ServerCard(s: ServerProfile, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
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
        Column(Modifier.weight(1f)) {
            Text("${s.flag} ${s.name}", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(s.summary, color = TextMuted, fontSize = 12.sp)
        }
        Box(Modifier.size(12.dp).clip(CircleShape)
            .background(if (selected) Success else TextMuted))
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
                    OutlinedActionButton("Протокол: ${proto.displayName}", Modifier.fillMaxWidth()) { expanded = true }
                    DropdownMenu(expanded, { expanded = false }) {
                        Protocol.entries.forEach { p ->
                            DropdownMenuItem(text = { Text(p.displayName) },
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
