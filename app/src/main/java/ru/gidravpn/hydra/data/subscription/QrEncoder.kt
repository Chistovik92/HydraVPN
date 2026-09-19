package ru.gidravpn.hydra.data.subscription

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Текст → QR-картинка для «Поделиться» (0.6.18+). Чёрное на белом с полями в
 * 4 модуля — так код читается любым сканером, в том числе с экрана в тёмной теме.
 */
object QrEncoder {

    /** null — текст слишком длинный для QR (около 2,3 КБ при уровне коррекции M). */
    fun encode(text: String, sizePx: Int = 768): Bitmap? {
        val matrix = try {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, sizePx, sizePx,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 4,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        } catch (_: WriterException) {
            return null
        }
        val pixels = IntArray(sizePx * sizePx) { i ->
            if (matrix.get(i % sizePx, i / sizePx)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}
