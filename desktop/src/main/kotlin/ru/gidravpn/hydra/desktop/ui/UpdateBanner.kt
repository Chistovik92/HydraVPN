package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.gidravpn.hydra.desktop.AppController
import ru.gidravpn.hydra.desktop.UiState
import ru.gidravpn.hydra.desktop.core.Updates

/** Новая версия: «Обновить» скачивает файл для этой ОС напрямую (SHA-256 сверяется) и запускает установщик. */
@Composable
internal fun UpdateBanner(c: AppController, ui: UiState, u: Updates.Release) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val p = ui.updateProgress
        if (p != null) {
            Text("Загрузка Hydra ${u.version}… ${(p * 100).toInt()}%", color = Accent)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.width(160.dp))
        } else if (u.asset != null) {
            TextButton(onClick = { c.installUpdate() }) { Text("Доступна Hydra ${u.version} — обновить", color = Accent) }
            TextButton(onClick = { openUrl(u.pageUrl) }) { Text("Что нового") }
        } else {
            TextButton(onClick = { openUrl(u.pageUrl) }) { Text("Доступна Hydra ${u.version} — открыть страницу загрузки", color = Accent) }
        }
    }
}
