package ru.gidravpn.hydra.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Передать Telegram локальный SOCKS5 Hydra (0.7.15): ссылка `tg://socks?server=…&port=…` открывает в Telegram окно
 * «Включить прокси?». Прокси живёт, пока Hydra подключена. Возвращает false, если Telegram не установлен.
 */
fun openTelegramProxy(ctx: Context, port: Int): Boolean = try {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://socks?server=127.0.0.1&port=$port")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
