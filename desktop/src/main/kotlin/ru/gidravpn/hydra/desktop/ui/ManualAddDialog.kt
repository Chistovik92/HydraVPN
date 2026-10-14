package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.data.manual.ManualLink
import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.desktop.AppController

/**
 * Ручное добавление сервера на ПК (0.8.0-rc4; раньше — только ссылкой). Поля — общие с Android ([ManualLink]); собранная
 * ссылка/`.conf` проверяется тем же парсером и добавляется обычным импортом.
 */
@Composable
internal fun ManualAddDialog(c: AppController, onClose: () -> Unit) {
    val protos = Protocol.entries.filter { it.id in ManualLink.SUPPORTED }
    var proto by remember { mutableStateOf(Protocol.VLESS) }
    var name by remember { mutableStateOf("") }
    val values = remember { mutableStateMapOf<String, String>().apply { putAll(ManualLink.defaults(Protocol.VLESS.id)) } }
    var pick by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Новый сервер вручную") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { pick = true }, modifier = Modifier.fillMaxWidth()) { Text("Протокол: ${proto.displayName}") }
                    DropdownMenu(pick, { pick = false }) {
                        protos.forEach { p ->
                            DropdownMenuItem(text = { Text(p.displayName) }, onClick = {
                                proto = p; pick = false; error = null; values.clear(); values.putAll(ManualLink.defaults(p.id))
                            })
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название (необязательно)") })
                ManualLink.fields(proto.id).filter { it.visible(values) }.forEach { f ->
                    val label = f.label(false) + if (f.required) " *" else ""
                    when (f.kind) {
                        ManualLink.Kind.CHOICE -> {
                            var open by remember(f.key, proto) { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${f.choiceLabel(values[f.key].orEmpty(), false)}") }
                                DropdownMenu(open, { open = false }) {
                                    f.choices.forEach { ch -> DropdownMenuItem(text = { Text(f.choiceLabel(ch, false)) }, onClick = { values[f.key] = ch; open = false }) }
                                }
                            }
                        }
                        ManualLink.Kind.MULTILINE -> OutlinedTextField(values[f.key].orEmpty(), { values[f.key] = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp), label = { Text(label) })
                        else -> OutlinedTextField(values[f.key].orEmpty(), { values[f.key] = if (f.kind == ManualLink.Kind.NUMBER) it.filter(Char::isDigit).take(5) else it },
                            Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
                            visualTransformation = if (f.kind == ManualLink.Kind.SECRET) PasswordVisualTransformation() else VisualTransformation.None)
                    }
                    f.hint(false).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                error?.let { Text(it, color = Danger, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = ManualLink.missing(proto.id, values).isEmpty(), onClick = {
                val link = ManualLink.build(proto.id, name, values)
                val ok = link != null && runCatching { LinkParser.parseLine(link) }.getOrNull() != null
                if (ok) { c.import(link!!, name.ifBlank { null }); onClose() } else error = "Из этих полей не получается рабочий сервер — проверьте адрес, ключи и порт."
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}
