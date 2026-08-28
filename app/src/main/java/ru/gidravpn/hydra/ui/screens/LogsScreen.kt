package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.components.clickableNoRipple
import ru.gidravpn.hydra.ui.theme.*

@Composable
fun LogsScreen(vm: MainViewModel) {
    val logs by vm.logs.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Логи подключения", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Box(Modifier.clip(RoundedCornerShape(8.dp))
                .background(Danger.copy(alpha = 0.2f))
                .border(1.dp, Danger.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .clickableNoRipple { vm.clearLogs() }
                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Очистить", color = Danger, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF020617))
                .border(1.dp, Border, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            items(logs) { line ->
                Text(line, color = colorFor(line), fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp, lineHeight = 18.sp)
            }
        }
    }
}

private fun colorFor(line: String) = when {
    "✓" in line -> Success
    "Ошибка" in line -> Danger
    "Подключение" in line -> AccentCyan
    else -> TextSecondary
}
