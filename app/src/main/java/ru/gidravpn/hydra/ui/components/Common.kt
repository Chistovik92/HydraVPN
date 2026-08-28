package ru.gidravpn.hydra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.ui.theme.*

/** Клик без ripple — под минималистичный дизайн макета. */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** Полупрозрачная карточка-контейнер, как в макете. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    borderColor: androidx.compose.ui.graphics.Color = Border,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun Label(text: String) = Text(
    text.uppercase(), color = TextMuted, fontSize = 11.sp,
    fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
    modifier = Modifier.padding(bottom = 8.dp)
)

/** Плашка BETA для ознакомительных/экспериментальных протоколов (WDTT, olcRTC). */
@Composable
fun BetaBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AccentViolet.copy(alpha = 0.15f))
            .border(1.dp, AccentViolet.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("BETA", color = AccentViolet, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}
