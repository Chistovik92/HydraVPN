package ru.gidravpn.hydra.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Передать Telegram локальный прокси Hydra (0.7.15): ссылка `tg://socks?…` (SOCKS5) или `tg://proxy?…` (MTProto, с 0.7.20 - для TG WS)
 * открывает в Telegram окно «Включить прокси?». Прокси живёт, пока Hydra подключена. Возвращает false, если Telegram не установлен.
 */
fun openTelegramProxy(ctx: Context, link: String): Boolean = try {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
