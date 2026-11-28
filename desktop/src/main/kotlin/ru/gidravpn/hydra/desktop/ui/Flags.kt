package ru.gidravpn.hydra.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.gidravpn.hydra.data.model.ServerLocation
import ru.gidravpn.hydra.data.model.ServerProfile
import java.util.concurrent.ConcurrentHashMap

/**
 * Флаги стран на ПК — картинками, а не эмодзи: в Windows нет шрифта с флагами (Segoe UI Emoji не
 * содержит пар regional indicator), и «🇩🇪» в строках серверов рендерится буквами «DE» или пустыми
 * боками — с этим не справился фикс 0.8.4-b. Флаг рисуется PNG Twemoji (CC-BY 4.0,
 * jdecked/twemoji 15.1.0) из `resources/flags/<iso>.png` — 249 кодов ISO 3166-1 плюс `xk`/`eu`,
 * которые панели тоже ставят в имена серверов.
 */
object FlagImages {
    private val cache = ConcurrentHashMap<String, org.jetbrains.skia.Image?>()

    private fun load(iso: String): org.jetbrains.skia.Image? = runCatching {
        FlagImages::class.java.getResourceAsStream("/flags/$iso.png")?.use {
            org.jetbrains.skia.Image.makeFromEncoded(it.readBytes())
        }
    }.getOrNull()

    /** Декодированный флаг по ISO-коду в нижнем регистре («de»); null — файла нет. */
    fun image(iso: String): org.jetbrains.skia.Image? = cache.computeIfAbsent(iso) { load(it) }
}

/** Флаг страны картинкой; картинки нет — не рисует ничего. */
@Composable
fun FlagIso(iso: String?, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    val image = iso?.let(FlagImages::image) ?: return
    Image(bitmap = image.toComposeImageBitmap(), contentDescription = null, modifier = modifier.size(size))
}

/**
 * «Флаг + имя сервера» для карточки главного экрана и строк списка: страна с известным кодом —
 * PNG-флаг, прочий флаг-эмодзи (🌐) — текстом (не-страновые эмодзи Windows рендерит), затем имя
 * без флага. Имя передаётся лямбдой — у мест вызова разные стили и цвета.
 */
@Composable
fun ServerNameRow(s: ServerProfile, name: @Composable () -> Unit) {
    val flag = ServerLocation.flag(s)
    val iso = ServerLocation.isoCode(flag)
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            iso != null -> FlagIso(iso)
            flag.isNotEmpty() -> Text(flag)
        }
        if (iso != null || flag.isNotEmpty()) Spacer(Modifier.width(6.dp))
        name()
    }
}
