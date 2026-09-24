package ru.gidravpn.hydra.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.ui.theme.AccentCyan
import ru.gidravpn.hydra.ui.theme.Bg
import ru.gidravpn.hydra.ui.theme.TextMuted
import ru.gidravpn.hydra.ui.theme.TextPrimary

/**
 * Экран блокировки (Фаза 8). [onUnlock] == null — настройка блокировки ещё читается из
 * DataStore: показываем только фон, чтобы серверы не мелькнули на экране до проверки.
 */
@Composable
fun LockScreen(onUnlock: (() -> Unit)?) {
    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        if (onUnlock == null) return@Surface
        // Системный диалог сразу, без лишнего нажатия; кнопка — если его закрыли.
        LaunchedEffect(Unit) { onUnlock() }
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_hydra_ambient), contentDescription = null,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
            )
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.lock_title), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.lock_hint), color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
                Text(stringResource(R.string.lock_unlock), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
