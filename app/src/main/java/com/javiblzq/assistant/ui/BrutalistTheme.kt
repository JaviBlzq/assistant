package com.javiblzq.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object BrutalistTheme {
    const val BG_BLACK = Color.BLACK
    const val BG_PANEL = 0xFF060606.toInt()
    const val BG_ELEVATED = 0xFF101010.toInt()
    const val FG_WHITE = Color.WHITE
    const val FG_MUTED = 0xFF8C8C8C.toInt()
    const val FG_DIM = 0xFF555555.toInt()
    const val BORDER = 0xFF2B2B2B.toInt()
    const val BORDER_BRIGHT = 0xFFFFFFFF.toInt()
    const val TRAFFIC_RED_HIGH = 0xFFFF2E2E.toInt()
    const val TRAFFIC_RED_MED = 0xFFB82020.toInt()
    const val TRAFFIC_RED_DARK = 0xFF4A1212.toInt()
    const val ACCENT_RED = TRAFFIC_RED_HIGH
    const val ACCENT_AMBER = 0xFFFFB020.toInt()

    private val cleanBold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val cleanMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val cleanRegular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private var fontDoto: Typeface? = null

    fun ensureLoaded(context: Context) {
        if (fontDoto == null) {
            fontDoto = loadAssetFont(context, "fonts/Doto-Bold.ttf")
        }
    }

    private fun loadAssetFont(context: Context, path: String): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, path)
        } catch (_: Exception) {
            cleanBold
        }
    }

    fun fontDisplay(): Typeface = cleanBold
    fun fontCompact(): Typeface = cleanRegular
    fun fontMono(): Typeface = cleanMedium
    fun fontScan(): Typeface = cleanBold
    fun fontDots(): Typeface = fontDoto ?: cleanBold

    fun dp(context: Context, value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).toInt()
    }

    fun generateQrBitmap(content: String, sizePx: Int = 360): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (_: Exception) {
            null
        }
    }
}
