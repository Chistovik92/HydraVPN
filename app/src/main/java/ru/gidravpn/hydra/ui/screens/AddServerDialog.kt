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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.manual.ManualLink
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.ui.components.BetaBadge
import ru.gidravpn.hydra.ui.theme.AccentCyan
import ru.gidravpn.hydra.ui.theme.Border
import ru.gidravpn.hydra.ui.theme.Danger
import ru.gidravpn.hydra.ui.theme.InputBg
import ru.gidravpn.hydra.ui.theme.Surface
import ru.gidravpn.hydra.ui.theme.TextMuted
import ru.gidravpn.hydra.ui.theme.TextPrimary

/**
 * Ручное добавление сервера. Поля каждого протокола описаны в общем с ПК [ManualLink] (0.8.0-rc4): VLESS с Reality и
 * транспортом, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, SSTP/L2TP/PPTP, WireGuard/AmneziaWG (конфиг целиком или по полям),
 * OpenFlux, olcRTC, ByeDPI. Форма собирает обычную ссылку или `.conf`, проверяет её тем же парсером, что и импорт, и
 * отдаёт в [onSave] (дальше — обычный импорт с именем). Для WDTT ручной формы нет — только ссылка, «Импорт».
 */
@Composable
internal fun AddServerDialog(
    onDismiss: () -> Unit,
    onSave: (linkOrConf: String, name: String?) -> Unit,
    onOpenImport: () -> Unit,
) {
    val en = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language != "ru"
    var name by remember { mutableStateOf("") }
    var proto by remember { mutableStateOf(Protocol.VLESS) }
    val values = remember { mutableStateMapOf<String, String>().apply { putAll(ManualLink.defaults(Protocol.VLESS.id)) } }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val supported = proto.id in ManualLink.SUPPORTED
    val badLink = stringResource(R.string.manual_bad)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(stringResource(R.string.servers_new_title), color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedActionButton(stringResource(R.string.servers_protocol, proto.displayName) + if (proto.beta) " [BETA]" else "", Modifier.fillMaxWidth()) { expanded = true }
                    DropdownMenu(expanded, { expanded = false }) {
                        Protocol.entries.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(p.displayName)
                                        if (p.beta) { Spacer(Modifier.width(6.dp)); BetaBadge() }
                                    }
                                },
                                onClick = {
                                    proto = p; expanded = false; error = null
                                    values.clear(); values.putAll(ManualLink.defaults(p.id))
                                },
                            )
                        }
                    }
                }
                ManualField(stringResource(R.string.field_name), name) { name = it }
                if (!supported) {
                    Text(stringResource(R.string.manual_link_only), color = TextMuted, fontSize = 12.sp)
                    OutlinedActionButton(stringResource(R.string.import_title), Modifier.fillMaxWidth(), onOpenImport)
                } else {
                    ManualLink.fields(proto.id).filter { it.visible(values) }.forEach { f ->
                        val label = f.label(en) + if (f.required) " *" else ""
                        when (f.kind) {
                            ManualLink.Kind.CHOICE -> ChoiceField(label, values[f.key].orEmpty(), f.choices, { f.choiceLabel(it, en) }) { values[f.key] = it }
                            ManualLink.Kind.MULTILINE -> MultilineField(label, values[f.key].orEmpty()) { values[f.key] = it }
                            else -> ManualField(label, values[f.key].orEmpty(), number = f.kind == ManualLink.Kind.NUMBER, secret = f.kind == ManualLink.Kind.SECRET) {
                                values[f.key] = if (f.kind == ManualLink.Kind.NUMBER) it.filter(Char::isDigit).take(5) else it
                            }
                        }
                        f.hint(en).takeIf { it.isNotBlank() }?.let { Text(it, color = TextMuted, fontSize = 11.sp) }
                    }
                }
                error?.let { Text(it, color = Danger, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            if (supported) {
                val missing = ManualLink.missing(proto.id, values, en)
                TextButton(enabled = missing.isEmpty(), onClick = {
                    val link = ManualLink.build(proto.id, name, values)
                    // Проверка тем же парсером, что и импорт: неверная ссылка не попадает в список молча.
                    val ok = link != null && runCatching { LinkParser.parseLine(link) }.getOrNull()?.let { it.protocolId == proto.id || proto.id == "awg" || proto.id == "wireguard" } == true
                    if (ok) onSave(link!!, name.takeIf { it.isNotBlank() }) else error = badLink
                }) { Text(stringResource(R.string.action_save), color = AccentCyan) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel), color = TextMuted) } },
    )
}

@Composable
private fun ManualField(label: String, value: String, number: Boolean = false, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, color = TextMuted, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number) else KeyboardOptions.Default,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ChoiceField(label: String, value: String, choices: List<String>, show: (String) -> String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedActionButton("$label: ${show(value)}", Modifier.fillMaxWidth()) { open = true }
        DropdownMenu(open, { open = false }) {
            choices.forEach { c -> DropdownMenuItem(text = { Text(show(c)) }, onClick = { onPick(c); open = false }) }
        }
    }
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
