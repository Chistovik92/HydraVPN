package ru.gidravpn.hydra.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.ui.components.Card
import ru.gidravpn.hydra.ui.components.Label
import ru.gidravpn.hydra.ui.components.humanBytes
import ru.gidravpn.hydra.ui.theme.*

/**
 * Статус аккаунта на главном экране (0.7.0): срок и трафик подписок, выданных ботом «Радар»
 * (Subscription.botPanel). Данные — те, что панель отдала при последнем обновлении подписки.
 * Нет подписок из бота — карточки нет.
 */
@Composable
fun AccountStatusCard(subscriptions: List<Subscription>) {
    val items = subscriptions.filter { it.botPanel.isNotEmpty() }
    if (items.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Label(stringResource(R.string.main_account))
        items.forEachIndexed { i, sub ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(sub.displayName, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (sub.expireAt > 0) Text(
                    stringResource(R.string.sub_until,
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(sub.expireAt * 1000))),
                    color = if (sub.expireAt * 1000 < System.currentTimeMillis()) Danger else TextSecondary, fontSize = 12.sp,
                )
            }
            if (sub.totalBytes > 0) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (sub.usedBytes.toFloat() / sub.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (sub.usedBytes > sub.totalBytes * 0.9) PingSlow else AccentCyan,
                    trackColor = Border,
                )
                Text("${humanBytes(sub.usedBytes)} / ${humanBytes(sub.totalBytes)}", color = TextSecondary, fontSize = 11.sp)
            } else if (sub.usedBytes > 0) {
                Text(humanBytes(sub.usedBytes), color = TextSecondary, fontSize = 11.sp)
            }
            if (sub.lastError.isNotBlank()) Text(sub.lastError, color = Danger, fontSize = 11.sp)
        }
    }
}
