package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.ManualServer
import ru.gidravpn.hydra.ui.components.BetaBadge
import ru.gidravpn.hydra.ui.theme.AccentCyan
import ru.gidravpn.hydra.ui.theme.Border
import ru.gidravpn.hydra.ui.theme.InputBg
import ru.gidravpn.hydra.ui.theme.Surface
import ru.gidravpn.hydra.ui.theme.TextMuted
import ru.gidravpn.hydra.ui.theme.TextPrimary

/**
 * Ручное добавление сервера (0.8.0-rc3). Поля зависят от протокола: UUID / пароль / пользователь / SNI / шифр, для WireGuard и
 * AmneziaWG — вставка `.conf` целиком; для протоколов, у которых есть только ссылка (OpenFlux, olcRTC, ByeDPI), — переход в «Импорт».
 * Раньше форма спрашивала только имя, адрес и порт, и профиль без ключа или пароля не мог подключиться.
 */
@Composable
internal fun AddServerDialog(
    onDismiss: () -> Unit,
    onSaveProfile: (ServerProfile) -> Unit,
    onSaveConf: (conf: String, name: String?) -> Unit,
    onOpenImport: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var proto by remember { mutableStateOf(Protocol.VLESS) }
    var secret by remember { mutableStateOf("") }
    var secret2 by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var sni by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(ManualServer.DEFAULT_METHOD) }
    var conf by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val kind = ManualServer.kind(proto)
    val secretLabel = stringResource(
        if (proto == Protocol.VLESS || proto == Protocol.VMESS || proto == Protocol.TUIC) R.string.field_uuid else R.string.field_password
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(stringResource(R.string.servers_new_title), color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedActionButton(stringResource(R.string.servers_protocol, proto.displayName) + if (proto.beta) " [BETA]" else "", Modifier.fillMaxWidth()) { expanded = true }
                    DropdownMenu(expanded, { expanded = false }) {
                        Protocol.entries.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(p.displayName)
                                        if (p.beta) {
                                            Spacer(Modifier.width(6.dp))
                                            BetaBadge()
                                        }
                                    }
                                },
                                onClick = { proto = p; port = ManualServer.defaultPort(p).toString(); expanded = false },
                            )
                        }
                    }
                }
                ManualField(stringResource(R.string.field_name), name) { name = it }
                when (kind) {
                    ManualServer.Kind.CONF -> {
                        Text(stringResource(R.string.manual_conf_hint), color = TextMuted, fontSize = 12.sp)
                        MultilineField(stringResource(R.string.manual_conf_field), conf) { conf = it }
                    }
                    ManualServer.Kind.LINK_ONLY -> {
                        Text(stringResource(R.string.manual_link_only), color = TextMuted, fontSize = 12.sp)
                        OutlinedActionButton(stringResource(R.string.import_title), Modifier.fillMaxWidth(), onOpenImport)
                    }
                    ManualServer.Kind.CREDENTIALS -> {
                        ManualField(stringResource(R.string.field_address), addr) { addr = it }
                        ManualField(stringResource(R.string.field_port), port, number = true) { port = it.filter(Char::isDigit) }
                        if (ManualServer.needsUser(proto)) ManualField(stringResource(R.string.field_username), user) { user = it }
                        ManualField(secretLabel, secret) { secret = it }
                        if (ManualServer.needsSecret2(proto)) ManualField(stringResource(R.string.field_password), secret2) { secret2 = it }
                        if (ManualServer.needsMethod(proto)) ManualField(stringResource(R.string.field_cipher), method) { method = it }
                        if (ManualServer.needsSni(proto)) ManualField(stringResource(R.string.field_sni), sni) { sni = it }
                    }
                }
            }
        },
        confirmButton = {
            if (kind != ManualServer.Kind.LINK_ONLY) {
                val ready = if (kind == ManualServer.Kind.CONF) conf.isNotBlank()
                    else addr.isNotBlank() && (secret.isNotBlank() || ManualServer.needsUser(proto))
                TextButton(enabled = ready, onClick = {
                    if (kind == ManualServer.Kind.CONF) onSaveConf(conf, name.takeIf { it.isNotBlank() })
                    else ManualServer.build(proto, name, addr, port.toIntOrNull() ?: 0, secret, secret2, user, sni, method)?.let(onSaveProfile)
                }) { Text(stringResource(R.string.action_save), color = AccentCyan) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel), color = TextMuted) } },
    )
}

@Composable
private fun ManualField(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = TextMuted, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number) else KeyboardOptions.Default,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Многострочное поле (0.8.0-rc3): однострочное склеивало вставленный `.conf` в одну строку, и он не разбирался. */
@Composable
internal fun MultilineField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = TextMuted, fontSize = 12.sp) },
        minLines = 3, maxLines = 8,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
    focusedBorderColor = AccentCyan, unfocusedBorderColor = Border,
    focusedContainerColor = InputBg, unfocusedContainerColor = InputBg,
)
