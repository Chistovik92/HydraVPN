package ru.gidravpn.hydra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.gidravpn.hydra.ui.theme.*

/**
 * Клик без ripple — под минималистичный дизайн макета.
 *
 * Фаза 8: элемент в фокусе (пульт Android TV, клавиатура, переключатель доступа) обводится
 * рамкой акцентного цвета — без неё на TV не видно, где курсор, а ripple тут выключен.
 * Role.Button — TalkBack объявляет элемент кнопкой, а не просто текстом.
 */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    return this
        .then(if (focused) Modifier.border(2.dp, AccentCyan, RoundedCornerShape(12.dp)) else Modifier)
        .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
}

/** Карточка-контейнер: тональная поверхность Material You (0.7.9), скругление M3 «large» — 20 dp. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    borderColor: androidx.compose.ui.graphics.Color = Border,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
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

/**
 * Байты в человекочитаемый вид. Раньше везде было "%.1f MB", из-за чего
 * реальные килобайты трафика показывались как «0,0 MB» и выглядели поломкой.
 */
fun humanBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f %s".format(bytes / 1_000_000_000.0, byteUnit(3))
    bytes >= 1_000_000 -> "%.1f %s".format(bytes / 1_000_000.0, byteUnit(2))
    bytes >= 1_000 -> "%.0f %s".format(bytes / 1_000.0, byteUnit(1))
    else -> "$bytes ${byteUnit(0)}"
}

/** Скорость «4,2 МБ/с». */
fun humanSpeed(bytesPerSec: Long): String =
    humanBytes(bytesPerSec) + if (java.util.Locale.getDefault().language == "ru") "/с" else "/s"

private fun byteUnit(i: Int): String {
    val ru = java.util.Locale.getDefault().language == "ru"
    return (if (ru) listOf("Б", "КБ", "МБ", "ГБ") else listOf("B", "KB", "MB", "GB"))[i]
}
/** Простой линейный график по последним замерам — для экрана Профиля. */
@Composable
fun Sparkline(samples: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val max = samples.max().coerceAtLeast(1f)
        val stepX = size.width / (samples.size - 1)
        val path = Path().apply {
            samples.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height * (1f - v / max)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path, color, style = Stroke(width = 3f))
        drawLine(color.copy(alpha = 0.15f), Offset(0f, size.height), Offset(size.width, size.height))
    }
}

/**
 * Выпадающий список (0.7.14): поле с подписью и текущим значением, по нажатию — меню с прокруткой.
 * Заменяет ряды «чипов», которые на узком экране не помещались и не листались.
 */
@Composable
fun <T> DropdownField(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    selected: T? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(InputBg)
                .border(1.dp, if (open) AccentCyan else Border, RoundedCornerShape(12.dp))
                .clickableNoRipple { open = true }.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(label, color = TextMuted, fontSize = 10.sp)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(value, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("▾", color = TextMuted, fontSize = 14.sp)
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (key, title) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(title, color = if (key == selected) AccentCyan else TextPrimary, fontSize = 13.sp,
                        fontWeight = if (key == selected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; onSelect(key) },
                )
            }
        }
    }
}

/**
 * Окно выбора из длинного списка с прокруткой (стратегии ByeDPI — 60 строк по 100+ знаков, в меню они не помещаются).
 * [items] — пары (ключ, заголовок); [subtitle] — вторая строка мелким шрифтом.
 */
@Composable
fun <T> PickerDialog(
    title: String,
    items: List<T>,
    selected: T?,
    itemTitle: (Int, T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    subtitle: ((T) -> String)? = null,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(CardBg)
                .border(1.dp, Border, RoundedCornerShape(20.dp)).padding(vertical = 16.dp)
        ) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(items) { i, item ->
                    val on = item == selected
                    Column(Modifier.fillMaxWidth().clickableNoRipple { onPick(item) }
                        .background(if (on) AccentCyan.copy(alpha = 0.10f) else Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(itemTitle(i, item), color = if (on) AccentCyan else TextPrimary, fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        subtitle?.let { Text(it(item), color = TextMuted, fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}
