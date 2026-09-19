package ru.gidravpn.hydra.ui.screens

import androidx.compose.ui.res.stringResource
import ru.gidravpn.hydra.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.data.log.LogLevel
import ru.gidravpn.hydra.data.log.LogPersistMode
import ru.gidravpn.hydra.data.log.LogRetention
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.components.humanBytes
import ru.gidravpn.hydra.ui.theme.*

private enum class LogFilter(@androidx.annotation.StringRes val label: Int, val min: LogLevel) {
    ALL(R.string.log_filter_all, LogLevel.DEBUG), WARN(R.string.log_filter_warn, LogLevel.WARN), ERROR(R.string.log_filter_error, LogLevel.ERROR)
}

@Composable
fun LogsScreen(vm: MainViewModel) {
    val logs by vm.logs.collectAsState()
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var showStorage by remember { mutableStateOf(false) }
    val shown = remember(logs, filter) {
        if (filter == LogFilter.ALL) logs else logs.filter { LogLevel.of(it) >= filter.min }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { vm.exportLogs(it) }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.log_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            SmallButton(stringResource(R.string.log_clear), Danger) { vm.clearLogs() }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogFilter.entries.forEach { f -> SmallButton(stringResource(f.label), if (filter == f) AccentCyan else TextSecondary) { filter = f } }
            SmallButton(stringResource(R.string.log_to_file), AccentViolet) {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
                exportLauncher.launch("hydra-log-$stamp.txt")
            }
            SmallButton(stringResource(if (showStorage) R.string.log_storage_open else R.string.log_storage_closed), TextSecondary) {
                showStorage = !showStorage
                if (showStorage) vm.refreshStoredLogSize()
            }
        }
        if (showStorage) {
            Spacer(Modifier.height(12.dp))
            LogStorageSettings(vm)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF020617))
                .border(1.dp, Border, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            items(shown) { line ->
                Text(line, color = colorFor(line), fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun LogStorageSettings(vm: MainViewModel) {
    val mode by vm.logPersistMode.collectAsState()
    val retention by vm.logRetention.collectAsState()
    val bytes by vm.storedLogBytes.collectAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg)
            .border(1.dp, Border, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.log_save_on_device), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        LogPersistMode.entries.forEach { m ->
            Column(Modifier.fillMaxWidth().clickableNoRipple { vm.setLogPersistMode(m) }) {
                Text((if (m == mode) "● " else "○ ") + stringResource(m.labelRes),
                    color = if (m == mode) AccentCyan else TextSecondary, fontSize = 12.sp)
                Text(stringResource(m.descriptionRes), color = if (m == LogPersistMode.ALL) Danger.copy(alpha = 0.8f) else TextMuted,
                    fontSize = 10.sp, modifier = Modifier.padding(start = 14.dp))
            }
        }
        if (mode != LogPersistMode.OFF) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.log_keep), color = TextMuted, fontSize = 12.sp)
                LogRetention.entries.forEach { r ->
                    SmallButton(stringResource(r.labelRes), if (r == retention) AccentCyan else TextSecondary) { vm.setLogRetention(r) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.log_saved, humanBytes(bytes)), color = TextMuted, fontSize = 12.sp)
            SmallButton(stringResource(R.string.log_delete_saved), Danger) { vm.clearStoredLogs() }
        }
    }
}

@Composable
private fun SmallButton(text: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp))
        .background(color.copy(alpha = 0.12f))
        .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
        .clickableNoRipple(onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

@Composable
private fun colorFor(line: String) = when {
    "✓" in line -> Success
    LogLevel.of(line) == LogLevel.ERROR -> Danger
    LogLevel.of(line) == LogLevel.WARN -> AccentViolet
    "Подключение" in line -> AccentCyan
    else -> TextSecondary
}
