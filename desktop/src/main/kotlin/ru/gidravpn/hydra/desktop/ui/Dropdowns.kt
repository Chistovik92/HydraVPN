package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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

/**
 * Выпадающие списки (0.7.14): вместо рядов чипов, которые на узком окне переносились простынёй и ничего не выбирали удобно.
 * Меню прокручивается колесом мыши; длинные списки (стратегии ByeDPI) — в окне [PickDialog].
 */
@Composable
internal fun <T> DropField(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    selected: T? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(value, {}, Modifier.fillMaxWidth(), readOnly = true, singleLine = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 380.dp)) {
            options.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(title, fontSize = 13.sp, fontWeight = if (key == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (key == selected) Accent else MaterialTheme.colorScheme.onSurface) },
                    onClick = { open = false; onSelect(key) },
                )
            }
        }
    }
}

/** Окно выбора одного из длинного списка (строки до 100+ знаков), с прокруткой. */
@Composable
internal fun <T> PickDialog(title: String, items: List<T>, selected: T?, itemTitle: (Int, T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(items) { i, item ->
                    Text(itemTitle(i, item), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = if (item == selected) Accent else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (item == selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 7.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** Окно выбора нескольких пунктов галочками. */
@Composable
internal fun <T> MultiPickDialog(title: String, items: List<Pair<T, String>>, selected: Set<T>, onToggle: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(items) { _, (key, name) ->
                    Row(Modifier.fillMaxWidth().clickable { onToggle(key) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(key in selected, null)
                        Text(name, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
