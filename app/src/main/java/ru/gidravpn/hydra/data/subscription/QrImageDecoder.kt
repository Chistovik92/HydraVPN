package ru.gidravpn.hydra.data.subscription

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Распознаёт QR-коды на фотографии/скриншоте из галереи (0.6.18). Пробует
 * исходный размер, потом уменьшенные копии (огромные фото QR не любит) и
 * инвертированное изображение (светлый QR на тёмном фоне). Несколько кодов на
 * одном снимке возвращает все — сервер и подписка могут лежать рядом.
 */
object QrImageDecoder {

    private const val MAX_SIDE = 2400

    suspend fun decode(context: Context, uri: Uri): List<String> = withContext(Dispatchers.Default) {
        val bmp = load(context, uri) ?: return@withContext emptyList()
        try {
            for (scale in listOf(1.0, 0.5, 0.25)) {
                val b = if (scale == 1.0) bmp else Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(64),
                    (bmp.height * scale).toInt().coerceAtLeast(64),
                    true,
                )
                try {
                    val found = decodeBitmap(b)
                    if (found.isNotEmpty()) return@withContext found
                } finally {
                    if (b !== bmp) b.recycle()
                }
            }
            emptyList()
        } finally {
            bmp.recycle()
        }
    }

    private fun load(context: Context, uri: Uri): Bitmap? = runCatching {
        // Сначала размеры — не грузим 50-мегапиксельное фото целиком.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    private fun decodeBitmap(bmp: Bitmap): List<String> {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return decodePixels(bmp.width, bmp.height, pixels)
    }

    /** Чистый разбор массива ARGB-пикселей — отделён от Bitmap, чтобы проверяться JVM-тестом. */
    internal fun decodePixels(width: Int, height: Int, pixels: IntArray): List<String> {
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        return readAll(source, hints).ifEmpty { readAll(source.invert(), hints) }
    }

    private fun readAll(source: LuminanceSource, hints: Map<DecodeHintType, Any>): List<String> = try {
        QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(source)), hints)
            .map { it.text }.filter { it.isNotBlank() }.distinct()
    } catch (_: Exception) {
        emptyList()   // NotFoundException и прочее: на этом масштабе кода нет
    }
}
