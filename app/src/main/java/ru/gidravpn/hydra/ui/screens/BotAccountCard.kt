package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.Label
import ru.gidravpn.hydra.ui.theme.*

/**
 * «Аккаунт бота» в Профиле: вход в аккаунт бота «Радар» по одноразовому коду и забор выданных
 * там подписок. Код человек берёт в боте: «VPN» → «Подключить приложение».
 */
@Composable
fun BotAccountCard(vm: MainViewModel) {
    val bot by vm.bot.collectAsState()
    var server by rememberSaveable { mutableStateOf(bot.server) }
    var code by rememberSaveable { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Label(stringResource(R.string.bot_title))

        if (bot.linked) {
            Text(
                if (bot.username.isNotBlank()) stringResource(R.string.bot_linked_as, bot.username)
                else stringResource(R.string.bot_linked),
                color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(bot.server, color = TextMuted, fontSize = 12.sp)
            bot.panels?.let {
                Text(stringResource(R.string.bot_panels, it), color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.syncBot() }, enabled = !bot.busy) {
                    Text(stringResource(R.string.bot_sync))
                }
                TextButton(onClick = { vm.unlinkBot() }, enabled = !bot.busy) {
                    Text(stringResource(R.string.bot_unlink), color = TextSecondary)
                }
            }
        } else {
            Text(stringResource(R.string.bot_hint), color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            BotField(stringResource(R.string.bot_server), server, KeyboardType.Uri) { server = it }
            Spacer(Modifier.height(8.dp))
            BotField(stringResource(R.string.bot_code), code, KeyboardType.NumberPassword) {
                code = it.filter(Char::isDigit).take(8)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.linkBot(server, code); code = "" },
                enabled = !bot.busy && server.isNotBlank() && code.length == 8,
            ) { Text(stringResource(R.string.bot_connect)) }
        }

        if (bot.busy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        bot.message?.let { text ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.dismissBotMessage() }) {
                    Text(stringResource(R.string.backup_dismiss), color = TextMuted)
                }
            }
        }
    }
}

@Composable
private fun BotField(label: String, value: String, type: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = TextMuted, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
            focusedContainerColor = InputBg, unfocusedContainerColor = InputBg,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
