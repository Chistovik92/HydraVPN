package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.UiState
import java.text.SimpleDateFormat
import java.util.Date

/**
 * «Аккаунт Hydra VPN» (бот «Радар») в Настройках: вход по одноразовому коду из бота
 * («VPN» → «Подключить приложение») и забор выданных там подписок. Как «Аккаунт бота» на Android.
 */
@Composable
internal fun BotAccountSection(c: AppController, ui: UiState) {
    val link = ui.data.bot
    Section("Аккаунт Hydra VPN (бот «Радар»)") {
        if (link != null) {
            Text(if (link.username.isNotBlank()) "Подключено как @${link.username}" else "Подключено к боту", fontWeight = FontWeight.Medium)
            Text(link.server, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            link.panels?.let { Text("Панелей с доступом в боте: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ChipRow {
                OutlinedButton(onClick = { c.syncBot() }, enabled = !ui.botBusy) { Text("Получить подписки") }
                TextButton(onClick = { c.unlinkBot() }, enabled = !ui.botBusy) { Text("Отключить") }
            }
        } else {
            var server by remember { mutableStateOf("") }
            var code by remember { mutableStateOf("") }
            Text("Войдите в аккаунт бота «Радар», чтобы получить выданные вам подписки. В боте откройте «VPN» → «Подключить приложение» и введите код здесь.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Адрес сервера бота") })
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Код из бота (8 цифр)") })
            OutlinedButton(
                onClick = { c.linkBot(server, code); code = "" },
                enabled = !ui.botBusy && server.isNotBlank() && code.length == 8,
            ) { Text("Подключить") }
        }
        if (ui.botBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Статус аккаунта на главной: срок и трафик подписок, выданных ботом. Нет таких подписок — ничего. */
@Composable
internal fun AccountStatus(ui: UiState) {
    val subs = ui.data.subscriptions.filter { it.botPanel.isNotEmpty() }
    if (subs.isEmpty()) return
    Card(Modifier.widthIn(max = 520.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Аккаунт", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            subs.forEach { sub ->
                Row(Modifier.fillMaxWidth()) {
                    Text(sub.displayName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (sub.expireAt > 0) {
                        val expired = sub.expireAt * 1000 < System.currentTimeMillis()
                        Text("до " + SimpleDateFormat("dd.MM.yyyy").format(Date(sub.expireAt * 1000)), fontSize = 12.sp,
                            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (sub.totalBytes > 0) {
                    LinearProgressIndicator(progress = { (sub.usedBytes.toFloat() / sub.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp))
                    Text("${bytes(sub.usedBytes)} / ${bytes(sub.totalBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (sub.usedBytes > 0) {
                    Text(bytes(sub.usedBytes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (sub.lastError.isNotBlank()) Text(sub.lastError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
