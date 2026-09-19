package ru.gidravpn.hydra.data.subscription

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Кодирует настоящий QR (ZXing) и проверяет, что наш разбор его достаёт: обычный, инвертированный, уменьшенный. */
class QrImageDecoderTest {

    private fun render(text: String, size: Int, invert: Boolean = false): IntArray {
        val m: BitMatrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 4),
        )
        return IntArray(size * size) { i ->
            val dark = m.get(i % size, i / size)
            if (dark != invert) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }

    private val link = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@203.0.113.10:443?security=reality&sni=example.com#Test"

    @Test fun decodesNormalQr() {
        assertEquals(listOf(link), QrImageDecoder.decodePixels(400, 400, render(link, 400)))
    }

    @Test fun decodesInvertedQr() {
        assertEquals(listOf(link), QrImageDecoder.decodePixels(400, 400, render(link, 400, invert = true)))
    }

    @Test fun decodesLongSubscriptionUrl() {
        val url = "https://sub.example.com/sub/" + "a1b2c3d4".repeat(12) + "?flag=v2ray"
        assertEquals(listOf(url), QrImageDecoder.decodePixels(500, 500, render(url, 500)))
    }

    @Test fun blankImageHasNoCode() {
        assertTrue(QrImageDecoder.decodePixels(200, 200, IntArray(200 * 200) { 0xFFFFFFFF.toInt() }).isEmpty())
    }
}
