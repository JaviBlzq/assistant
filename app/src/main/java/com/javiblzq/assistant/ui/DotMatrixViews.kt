package com.javiblzq.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.javiblzq.assistant.data.HrZoneDistribution
import com.javiblzq.assistant.data.SemyraWeekSummary
import com.javiblzq.assistant.net.Tls12SocketFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Renders 9x9 pure monochrome dot-matrix weather glyphs matching Tecnica's aesthetic.
 */
class DotMatrixWeatherGlyphView(context: Context) : View(context) {
    private var wmoCode: Int = 0
    private val paintOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val paintOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1E1E.toInt() }

    fun setWeatherCode(code: Int) {
        wmoCode = code
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val matrix = glyphForCode(wmoCode)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cell = size / 9f
        val r = cell * 0.34f
        val ox = (width - size) / 2f
        val oy = (height - size) / 2f
        for (row in 0 until 9) {
            val line = matrix[row]
            for (col in 0 until 9) {
                val cx = ox + col * cell + cell / 2f
                val cy = oy + row * cell + cell / 2f
                canvas.drawCircle(cx, cy, r, if (line[col] == '1') paintOn else paintOff)
            }
        }
    }

    private fun glyphForCode(code: Int): Array<String> = when {
        code == 0 -> arrayOf(
            "000010000",
            "010010010",
            "000111000",
            "001111100",
            "111111111",
            "001111100",
            "000111000",
            "010010010",
            "000010000"
        )
        code in 1..2 -> arrayOf(
            "000100000",
            "010101000",
            "001110000",
            "011111100",
            "000111110",
            "001111111",
            "011111111",
            "000000000",
            "000000000"
        )
        code in listOf(51, 53, 55, 61, 63, 65, 80, 81, 82) -> arrayOf(
            "001111000",
            "011111100",
            "111111110",
            "111111111",
            "000000000",
            "010101010",
            "101010100",
            "010101010",
            "000000000"
        )
        code >= 95 -> arrayOf(
            "001111000",
            "011111100",
            "111111111",
            "000011000",
            "000110000",
            "001111000",
            "000110000",
            "001000000",
            "000000000"
        )
        else -> arrayOf(
            "000000000",
            "001111000",
            "011111100",
            "111111110",
            "111111111",
            "111111111",
            "000000000",
            "000000000",
            "000000000"
        )
    }
}

/**
 * Renders Semyra's Monday-Sunday 7-day training strip:
 * Filled circle = Running, Filled square = Strength, Hollow circle = Other.
 */
class WeekStripView(context: Context) : View(context) {
    private var summary: SemyraWeekSummary? = null

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.BORDER
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val todayBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val fillWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val hollowWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val mutedDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.FG_MUTED
        textAlign = Paint.Align.CENTER
        typeface = BrutalistTheme.fontCompact()
        textSize = 24f
    }
    private val todayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = BrutalistTheme.fontDisplay()
        textSize = 24f
    }

    fun setSummary(week: SemyraWeekSummary?) {
        summary = week
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val days = summary?.days ?: listOf("L", "M", "X", "J", "V", "S", "D").map {
            com.javiblzq.assistant.data.WeekDayCell("", it, false, false, false, false)
        }
        val gap = 6f
        val cellW = (w - gap * 6f) / 7f
        val labelSize = (h * 0.30f).coerceIn(14f, 26f)
        textPaint.textSize = labelSize
        todayTextPaint.textSize = labelSize

        for (i in 0 until 7) {
            val cell = days[i]
            val left = i * (cellW + gap)
            val rect = RectF(left + 1f, 1f, left + cellW - 1f, h - 1f)
            canvas.drawRect(rect, if (cell.isToday) todayBorderPaint else borderPaint)

            val cx = rect.centerX()
            canvas.drawText(
                cell.dayShort,
                cx,
                rect.top + labelSize + 6f,
                if (cell.isToday) todayTextPaint else textPaint
            )

            val cy = rect.bottom - h * 0.28f
            val r = min(cellW, h) * 0.14f
            when {
                cell.hasRun && cell.hasStrength -> {
                    canvas.drawCircle(cx - r * 1.15f, cy, r * 0.85f, fillWhite)
                    canvas.drawRect(cx + r * 0.2f, cy - r * 0.85f, cx + r * 1.9f, cy + r * 0.85f, fillWhite)
                }
                cell.hasRun -> canvas.drawCircle(cx, cy, r, fillWhite)
                cell.hasStrength -> canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fillWhite)
                cell.hasOther -> canvas.drawCircle(cx, cy, r, hollowWhite)
                else -> canvas.drawCircle(cx, cy, 3.5f, mutedDot)
            }
        }
    }
}

/**
 * Renders Strava 5-zone HR distribution as monochrome brutalist dot-matrix progress bars.
 */
class HrZoneBarsView(context: Context) : View(context) {
    private var zones: List<HrZoneDistribution> = emptyList()

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = BrutalistTheme.fontMono()
        textSize = 20f
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.FG_MUTED
        typeface = BrutalistTheme.fontMono()
        textAlign = Paint.Align.RIGHT
        textSize = 20f
    }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
    }
    private val barFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    fun setZones(list: List<HrZoneDistribution>) {
        zones = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (zones.isEmpty() || width <= 0 || height <= 0) return
        val rowH = height.toFloat() / zones.size
        val fontSize = (rowH * 0.56f).coerceIn(13f, 22f)
        labelPaint.textSize = fontSize
        pctPaint.textSize = fontSize

        val labelW = width * 0.25f
        val pctW = width * 0.18f
        val barLeft = labelW + 8f
        val barRight = width - pctW - 8f
        val barWidth = (barRight - barLeft).coerceAtLeast(20f)

        zones.forEachIndexed { idx, z ->
            val top = idx * rowH
            val midY = top + rowH * 0.68f
            canvas.drawText(z.id, 0f, midY, labelPaint)
            canvas.drawText("${z.percentage.toInt()}%", width.toFloat(), midY, pctPaint)

            val bTop = top + rowH * 0.24f
            val bBot = top + rowH * 0.76f
            canvas.drawRect(barLeft, bTop, barRight, bBot, barBg)
            val fillW = (barWidth * (z.percentage / 100.0).toFloat()).coerceIn(0f, barWidth)
            if (fillW > 0f) {
                canvas.drawRect(barLeft, bTop, barLeft + fillW, bBot, barFg)
            }
        }
    }
}

private fun View.dpF(value: Float): Float {
    val metrics = context?.resources?.displayMetrics
    return if (metrics != null && metrics.density > 0f) {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)
    } else {
        value
    }
}

/**
 * Replicates the Apple Weather 24-hour Celsius curve in monochrome dot-matrix style:
 * top 5x5 glyph strip, dotted grid, Catmull-Rom smooth curve, dot-matrix fill, and H/L markers.
 */
class AppleWeatherCurveView(context: Context) : View(context) {
    private var tempsC: List<Double> = emptyList()
    private var weatherCodes: List<Int> = emptyList()
    private var hourLabels: List<String> = emptyList()

    private val glyphOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val glyphOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF262626.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(1f)
        pathEffect = DashPathEffect(floatArrayOf(dpF(1.5f), dpF(4f)), 0f)
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.BORDER
        style = Paint.Style.STROKE
        strokeWidth = dpF(1f)
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.FG_MUTED
        typeface = BrutalistTheme.fontMono()
    }
    private val emptyLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.FG_DIM
        textAlign = Paint.Align.CENTER
        typeface = BrutalistTheme.fontMono()
    }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(2.2f)
    }
    private val markerTextHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = dpF(3f)
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
        typeface = BrutalistTheme.fontMono()
    }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = BrutalistTheme.fontMono()
    }

    private val curvePath = Path()
    private val fillPath = Path()
    private val gridLinePath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setHourlyData(
        tempsC: List<Double>,
        weatherCodes: List<Int>,
        hourLabels: List<String> = emptyList()
    ) {
        this.tempsC = tempsC.toList()
        this.weatherCodes = weatherCodes.toList()
        this.hourLabels = hourLabels.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val mono = BrutalistTheme.fontMono()
        axisLabelPaint.typeface = mono
        emptyLabelPaint.typeface = mono
        markerTextPaint.typeface = mono
        markerTextHaloPaint.typeface = mono

        val axisTextSize = (h * 0.075f).coerceIn(dpF(9f), dpF(11.5f))
        val markerTextSize = (h * 0.085f).coerceIn(dpF(10f), dpF(12.5f))
        axisLabelPaint.textSize = axisTextSize
        emptyLabelPaint.textSize = axisTextSize
        markerTextPaint.textSize = markerTextSize
        markerTextHaloPaint.textSize = markerTextSize

        val rightAxisW = max(dpF(26f), axisLabelPaint.measureText("-20°") + dpF(6f))
        val plotLeft = dpF(6f)
        val plotRight = (w - rightAxisW).coerceAtLeast(plotLeft + dpF(40f))
        val glyphSize = (h * 0.125f).coerceIn(dpF(10f), dpF(16f))
        val glyphTop = dpF(4f)
        val plotTop = glyphTop + glyphSize + dpF(6f)
        val plotBottom = (h - axisTextSize - dpF(6f)).coerceAtLeast(plotTop + dpF(28f))

        if (tempsC.isEmpty()) {
            drawPlaceholder(canvas, h, plotLeft, plotRight, plotTop, plotBottom, axisTextSize)
            return
        }

        val n = tempsC.size
        val cleanTemps = DoubleArray(n) { idx ->
            val v = tempsC[idx]
            if (v.isFinite()) v else 0.0
        }

        var minIdx = 0
        var maxIdx = 0
        var minTemp = cleanTemps[0]
        var maxTemp = cleanTemps[0]
        for (i in 1 until n) {
            if (cleanTemps[i] < minTemp) {
                minTemp = cleanTemps[i]
                minIdx = i
            }
            if (cleanTemps[i] > maxTemp) {
                maxTemp = cleanTemps[i]
                maxIdx = i
            }
        }

        drawWeatherStrip(canvas, n, plotLeft, plotRight, glyphTop, glyphSize)

        val ticks = computeCelsiusTicks(minTemp, maxTemp)
        val displayMin = min(minTemp, ticks.first().toDouble())
        val displayMax = max(maxTemp, ticks.last().toDouble())
        val tempSpan = (displayMax - displayMin).coerceAtLeast(2.0)

        val markerR = dpF(4.2f)
        val topHeadroom = markerR + markerTextSize + dpF(8f)
        val bottomHeadroom = markerR + markerTextSize + dpF(7f)
        val curveTop = (plotTop + topHeadroom).coerceAtMost((plotTop + plotBottom) * 0.45f)
        val curveBottom = (plotBottom - bottomHeadroom).coerceAtLeast((plotTop + plotBottom) * 0.55f)
        val curveSpan = (curveBottom - curveTop).coerceAtLeast(dpF(12f))

        fun yForTemp(t: Double): Float {
            val norm = ((t - displayMin) / tempSpan).toFloat().coerceIn(0f, 1f)
            return curveBottom - norm * curveSpan
        }

        // Horizontal dotted grid lines + right-side °C labels
        axisLabelPaint.textAlign = Paint.Align.LEFT
        for (tick in ticks) {
            val ty = yForTemp(tick.toDouble())
            drawDottedLine(canvas, plotLeft, ty, plotRight, ty)
            canvas.drawText("${tick}°", plotRight + dpF(5f), ty + axisTextSize * 0.35f, axisLabelPaint)
        }

        // Map data points to view coordinates
        val px = FloatArray(n)
        val py = FloatArray(n)
        if (n == 1) {
            px[0] = (plotLeft + plotRight) * 0.5f
            py[0] = yForTemp(cleanTemps[0])
        } else {
            val plotW = plotRight - plotLeft
            for (i in 0 until n) {
                px[i] = plotLeft + (i.toFloat() / (n - 1)) * plotW
                py[i] = yForTemp(cleanTemps[i])
            }
        }

        // Vertical dotted grid lines at 00, 06, 12, 18 + bottom labels
        drawVerticalHourGrid(canvas, h, plotLeft, plotRight, plotTop, plotBottom, px, axisTextSize)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, baselinePaint)

        // Build smooth Catmull-Rom -> cubic Bezier curve & closed fill path
        buildCurveAndFillPaths(px, py, plotLeft, plotRight, plotBottom, curveTop, curveBottom)

        var minPy = py[0]
        for (i in 1 until n) {
            if (py[i] < minPy) minPy = py[i]
        }

        // Clipped monochrome dot-matrix fill with vertical alpha gradient
        drawDotMatrixFill(canvas, plotLeft, plotRight, plotTop, plotBottom, minPy)

        // Stroked white curve on top of fill
        canvas.drawPath(curvePath, curvePaint)

        // High & Low markers
        drawExtremumMarker(
            canvas = canvas,
            x = px[maxIdx],
            y = py[maxIdx],
            label = "H ${maxTemp.toInt()}°",
            preferAbove = true,
            markerR = markerR,
            plotLeft = plotLeft,
            plotRight = plotRight,
            plotTop = plotTop,
            plotBottom = plotBottom
        )
        drawExtremumMarker(
            canvas = canvas,
            x = px[minIdx],
            y = py[minIdx],
            label = "L ${minTemp.toInt()}°",
            preferAbove = false,
            markerR = markerR,
            plotLeft = plotLeft,
            plotRight = plotRight,
            plotTop = plotTop,
            plotBottom = plotBottom,
            drawCircle = minIdx != maxIdx
        )
    }

    private fun drawPlaceholder(
        canvas: Canvas,
        h: Float,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        axisTextSize: Float
    ) {
        val midY = (plotTop + plotBottom) * 0.5f
        drawDottedLine(canvas, plotLeft, midY, plotRight, midY)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, baselinePaint)

        axisLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("--°", plotRight + dpF(5f), midY + axisTextSize * 0.35f, axisLabelPaint)

        val defaultLabels = listOf("00", "06", "12", "18")
        val fracs = floatArrayOf(0.0f, 0.25f, 0.50f, 0.75f)
        for (i in defaultLabels.indices) {
            val vx = plotLeft + fracs[i] * (plotRight - plotLeft)
            drawDottedLine(canvas, vx, plotTop, vx, plotBottom)
            drawClampedBottomLabel(canvas, defaultLabels[i], vx, h - dpF(3f), plotLeft, plotRight)
        }

        canvas.drawText(
            "--°C // SIN DATOS 24H",
            (plotLeft + plotRight) * 0.5f,
            midY - dpF(6f),
            emptyLabelPaint
        )
    }

    private fun drawWeatherStrip(
        canvas: Canvas,
        dataCount: Int,
        plotLeft: Float,
        plotRight: Float,
        glyphTop: Float,
        glyphSize: Float
    ) {
        val glyphCount = when {
            dataCount >= 12 -> 10
            dataCount >= 8 -> dataCount
            else -> 8
        }
        val colW = (plotRight - plotLeft) / glyphCount
        val cell = glyphSize / 5f
        val dotR = cell * 0.34f
        for (k in 0 until glyphCount) {
            val sampleIdx = if (dataCount <= 1 || glyphCount <= 1) {
                0
            } else {
                ((k.toFloat() / (glyphCount - 1)) * (dataCount - 1)).roundToInt().coerceIn(0, dataCount - 1)
            }
            val code = weatherCodes.getOrElse(sampleIdx) { 0 }
            val matrix = glyph5x5ForCode(code)
            val ox = plotLeft + k * colW + (colW - glyphSize) * 0.5f
            for (row in 0 until 5) {
                val line = matrix[row]
                for (col in 0 until 5) {
                    val cx = ox + col * cell + cell * 0.5f
                    val cy = glyphTop + row * cell + cell * 0.5f
                    canvas.drawCircle(cx, cy, dotR, if (line[col] == '1') glyphOnPaint else glyphOffPaint)
                }
            }
        }
    }

    private fun computeCelsiusTicks(minTemp: Double, maxTemp: Double): List<Int> {
        val lo = floor(minTemp).toInt()
        val hi = ceil(maxTemp).toInt()
        return when {
            hi - lo <= 1 -> listOf(lo - 2, lo, lo + 2)
            hi - lo == 2 -> listOf(lo, lo + 1, hi)
            else -> listOf(lo, ((lo + hi) / 2.0).roundToInt(), hi)
        }
    }

    private fun drawVerticalHourGrid(
        canvas: Canvas,
        h: Float,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        px: FloatArray,
        axisTextSize: Float
    ) {
        val targetHours = setOf(0, 6, 12, 18)
        val matched = mutableListOf<Pair<Float, String>>()
        val limit = min(px.size, hourLabels.size)
        for (i in 0 until limit) {
            val hr = parseHourInt(hourLabels[i]) ?: continue
            if (hr in targetHours) {
                val label = String.format(java.util.Locale.US, "%02d", hr)
                if (matched.none { it.second == label }) {
                    matched.add(px[i] to label)
                }
            }
        }

        val columns = if (matched.size >= 2) {
            matched
        } else if (px.size == 24) {
            listOf(
                px[0] to "00",
                px[6] to "06",
                px[12] to "12",
                px[18] to "18"
            )
        } else {
            val w = plotRight - plotLeft
            listOf(
                plotLeft to "00",
                (plotLeft + w * 0.25f) to "06",
                (plotLeft + w * 0.50f) to "12",
                (plotLeft + w * 0.75f) to "18"
            )
        }

        val labelY = h - dpF(3f).coerceAtMost(axisTextSize * 0.35f)
        for ((vx, label) in columns) {
            drawDottedLine(canvas, vx, plotTop, vx, plotBottom)
            drawClampedBottomLabel(canvas, label, vx, labelY, plotLeft, plotRight)
        }
    }

    private fun drawClampedBottomLabel(
        canvas: Canvas,
        label: String,
        centerX: Float,
        baselineY: Float,
        plotLeft: Float,
        plotRight: Float
    ) {
        axisLabelPaint.textAlign = Paint.Align.CENTER
        val halfW = axisLabelPaint.measureText(label) * 0.5f
        val cx = centerX.coerceIn(plotLeft + halfW, plotRight - halfW)
        canvas.drawText(label, cx, baselineY, axisLabelPaint)
    }

    private fun parseHourInt(raw: String): Int? {
        val digits = raw.trim().takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        return digits.toIntOrNull()?.takeIf { it in 0..23 }
    }

    private fun buildCurveAndFillPaths(
        px: FloatArray,
        py: FloatArray,
        plotLeft: Float,
        plotRight: Float,
        plotBottom: Float,
        curveTop: Float,
        curveBottom: Float
    ) {
        curvePath.reset()
        fillPath.reset()
        val n = px.size
        if (n == 1) {
            curvePath.moveTo(plotLeft, py[0])
            curvePath.lineTo(plotRight, py[0])
            fillPath.moveTo(plotLeft, plotBottom)
            fillPath.lineTo(plotLeft, py[0])
            fillPath.lineTo(plotRight, py[0])
            fillPath.lineTo(plotRight, plotBottom)
            fillPath.close()
            return
        }

        curvePath.moveTo(px[0], py[0])
        fillPath.moveTo(px[0], plotBottom)
        fillPath.lineTo(px[0], py[0])

        for (i in 0 until n - 1) {
            val i0 = (i - 1).coerceAtLeast(0)
            val i3 = (i + 2).coerceAtMost(n - 1)
            val p0x = px[i0]
            val p0y = py[i0]
            val p1x = px[i]
            val p1y = py[i]
            val p2x = px[i + 1]
            val p2y = py[i + 1]
            val p3x = px[i3]
            val p3y = py[i3]

            val segMinX = min(p1x, p2x)
            val segMaxX = max(p1x, p2x)
            val segMinY = min(p1y, p2y)
            val segMaxY = max(p1y, p2y)

            val c1x = (p1x + (p2x - p0x) / 6f).coerceIn(segMinX, segMaxX)
            val c1y = (p1y + (p2y - p0y) / 6f).coerceIn(segMinY, segMaxY).coerceIn(curveTop, curveBottom)
            val c2x = (p2x - (p3x - p1x) / 6f).coerceIn(segMinX, segMaxX)
            val c2y = (p2y - (p3y - p1y) / 6f).coerceIn(segMinY, segMaxY).coerceIn(curveTop, curveBottom)

            curvePath.cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)
            fillPath.cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)
        }

        fillPath.lineTo(px[n - 1], plotBottom)
        fillPath.close()
    }

    private fun drawDotMatrixFill(
        canvas: Canvas,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        minPy: Float
    ) {
        val dotR = dpF(1.3f)
        val step = dpF(5.5f)
        val span = (plotBottom - minPy).coerceAtLeast(dpF(20f))
        val startRow = floor(((minPy - plotTop) / step).toDouble()).toInt().coerceAtLeast(0)
        val endRow = ceil(((plotBottom - plotTop) / step).toDouble()).toInt() + 1

        canvas.save()
        canvas.clipPath(fillPath)
        for (row in startRow..endRow) {
            val y = plotTop + row * step
            val t = ((y - minPy) / span).coerceIn(0f, 1f)
            dotFillPaint.alpha = (185f * (1f - t * 0.88f)).toInt().coerceIn(16, 200)
            val offsetX = if (row % 2 == 1) step * 0.5f else 0f
            var x = plotLeft - step + offsetX
            while (x <= plotRight + step) {
                canvas.drawCircle(x, y, dotR, dotFillPaint)
                x += step
            }
        }
        canvas.restore()
    }

    private fun drawExtremumMarker(
        canvas: Canvas,
        x: Float,
        y: Float,
        label: String,
        preferAbove: Boolean,
        markerR: Float,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        drawCircle: Boolean = true
    ) {
        if (drawCircle) {
            canvas.drawCircle(x, y, markerR, markerFillPaint)
            canvas.drawCircle(x, y, markerR, markerRingPaint)
        }
        val halfW = markerTextPaint.measureText(label) * 0.5f
        val tx = x.coerceIn(plotLeft + halfW + dpF(2f), plotRight - halfW - dpF(2f))
        val fm = markerTextPaint.fontMetrics
        val aboveBase = y - markerR - dpF(5f)
        val belowBase = y + markerR + (-fm.ascent) + dpF(3f)
        val placeAbove = if (preferAbove) {
            aboveBase + fm.ascent >= plotTop || belowBase + fm.descent > plotBottom
        } else {
            belowBase + fm.descent > plotBottom && aboveBase + fm.ascent >= plotTop
        }
        val ty = if (placeAbove) aboveBase else belowBase
        canvas.drawText(label, tx, ty, markerTextHaloPaint)
        canvas.drawText(label, tx, ty, markerTextPaint)
    }

    private fun drawDottedLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        gridLinePath.reset()
        gridLinePath.moveTo(x1, y1)
        gridLinePath.lineTo(x2, y2)
        canvas.drawPath(gridLinePath, gridPaint)
    }

    private fun glyph5x5ForCode(code: Int): Array<String> = when {
        code == 0 -> arrayOf(
            "01110",
            "11111",
            "11111",
            "11111",
            "01110"
        )
        code in 1..2 -> arrayOf(
            "11000",
            "11010",
            "01111",
            "11111",
            "00000"
        )
        code == 45 || code == 48 -> arrayOf(
            "11110",
            "00000",
            "01111",
            "00000",
            "11110"
        )
        code in listOf(51, 53, 55, 61, 63, 65, 66, 67, 80, 81, 82) -> arrayOf(
            "01110",
            "11111",
            "11111",
            "01010",
            "10101"
        )
        code in listOf(71, 73, 75, 77, 85, 86) -> arrayOf(
            "10101",
            "01110",
            "11011",
            "01110",
            "10101"
        )
        code >= 95 -> arrayOf(
            "01110",
            "11111",
            "00110",
            "01100",
            "00100"
        )
        else -> arrayOf(
            "00110",
            "01111",
            "11111",
            "11111",
            "00000"
        )
    }
}

/**
 * Real OpenStreetMap (CartoDB Dark Matter) slippy-map view with brutalist monochrome filter,
 * local tile caching, and Web-Mercator-aligned car route + traffic congestion overlay.
 */
class RouteMiniMapView(context: Context) : View(context) {
    private var lats: DoubleArray = DoubleArray(0)
    private var lons: DoubleArray = DoubleArray(0)
    private var congestionLevels: IntArray = IntArray(0)
    private var originLabel: String = "CASA"
    private var destLabel: String = "TRABAJO"
    private var delayMin: Int = 0

    private val bgPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tileSrcRect = Rect()
    private val tileDstRect = RectF()
    private val gridPaint = Paint().apply {
        color = 0xFF141414.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(1f)
    }
    private val crosshairPaint = Paint().apply {
        color = 0xFF181818.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(1f)
    }
    private val cornerTickPaint = Paint().apply {
        color = 0xFF2B2B2B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.5f)
    }
    private val baseCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(5.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val normalSegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(2.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val moderateSegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.TRAFFIC_RED_MED
        style = Paint.Style.STROKE
        strokeWidth = dpF(3.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val heavyGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.TRAFFIC_RED_DARK
        style = Paint.Style.STROKE
        strokeWidth = dpF(6.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val heavyCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.TRAFFIC_RED_HIGH
        style = Paint.Style.STROKE
        strokeWidth = dpF(3.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerFillBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val markerFillWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val markerFillRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.TRAFFIC_RED_HIGH
        style = Paint.Style.FILL
    }
    private val originStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.8f)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD8000000.toInt()
        style = Paint.Style.FILL
    }
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2B2B2B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(1f)
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = BrutalistTheme.fontMono()
    }
    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = BrutalistTheme.fontMono()
    }
    private val attribTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF666666.toInt()
        typeface = BrutalistTheme.fontMono()
    }
    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.FG_DIM
        textAlign = Paint.Align.CENTER
        typeface = BrutalistTheme.fontMono()
    }

    private val basePath = Path()
    private val normalPath = Path()
    private val moderatePath = Path()
    private val heavyPath = Path()

    private val tileCacheDir: File? by lazy {
        try {
            val baseDir = (context.applicationContext ?: context).filesDir ?: return@lazy null
            File(baseDir, "osm_tiles").apply { if (!exists()) mkdirs() }
        } catch (_: Throwable) {
            null
        }
    }

    fun setRouteGeometry(
        lats: DoubleArray,
        lons: DoubleArray,
        congestionLevels: IntArray,
        originLabel: String = "CASA",
        destLabel: String = "TRABAJO",
        delayMin: Int = 0
    ) {
        this.lats = lats.copyOf()
        this.lons = lons.copyOf()
        this.congestionLevels = congestionLevels.copyOf()
        this.originLabel = originLabel
        this.destLabel = destLabel
        this.delayMin = delayMin
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val mono = BrutalistTheme.fontMono()
        labelTextPaint.typeface = mono
        tagTextPaint.typeface = mono
        attribTextPaint.typeface = mono
        emptyTextPaint.typeface = mono

        val labelSize = (h * 0.085f).coerceIn(dpF(8.5f), dpF(11f))
        labelTextPaint.textSize = labelSize
        tagTextPaint.textSize = labelSize
        attribTextPaint.textSize = (labelSize * 0.78f).coerceAtLeast(dpF(7.5f))
        emptyTextPaint.textSize = labelSize

        canvas.drawRect(0f, 0f, w, h, bgPaint)
        drawTacticalGrid(canvas, w, h)

        val count = min(lats.size, lons.size)
        if (count < 2) {
            drawCornerBrackets(canvas, w, h)
            drawTelemetryTag(canvas, w)
            canvas.drawText(
                "$originLabel -> $destLabel",
                w * 0.5f,
                h * 0.5f + labelSize * 0.35f,
                emptyTextPaint
            )
            return
        }

        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val lat = lats[i]
            val lon = lons[i]
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
        }

        val baseTileScale = dpF(1f).coerceIn(1f, 1.5f)
        val (zoom, scale) = chooseTileZoom(minLat, maxLat, minLon, maxLon, w, h, baseTileScale)

        val wxMin = lonToWorldX(minLon, zoom)
        val wxMax = lonToWorldX(maxLon, zoom)
        val wyMin = latToWorldY(maxLat, zoom)
        val wyMax = latToWorldY(minLat, zoom)
        val centerWX = (wxMin + wxMax) * 0.5
        val centerWY = (wyMin + wyMax) * 0.5

        // Draw OpenStreetMap / CartoDB Dark Matter slippy tiles under the route
        drawOsmTiles(canvas, w, h, zoom, centerWX, centerWY, scale)

        // Subtle tactical corner brackets & OSM attribution
        drawCornerBrackets(canvas, w, h)
        canvas.drawText("© OSM", dpF(6f), h - dpF(5f), attribTextPaint)

        val tagRect = drawTelemetryTag(canvas, w)

        // Project polyline with the EXACT same Web Mercator transform as the tiles
        val px = FloatArray(count)
        val py = FloatArray(count)
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        for (i in 0 until count) {
            val wx = lonToWorldX(lons[i], zoom)
            val wy = latToWorldY(lats[i], zoom)
            px[i] = halfW + ((wx - centerWX) * scale).toFloat()
            py[i] = halfH + ((wy - centerWY) * scale).toFloat()
        }

        // Base casing + layered congestion segments
        buildAndDrawRoutePaths(canvas, count, px, py)

        // Origin marker (hollow white square) & Destination marker (filled white/red target)
        val ox = px[0]
        val oy = py[0]
        val sqHalf = dpF(3.8f)
        canvas.drawRect(ox - sqHalf, oy - sqHalf, ox + sqHalf, oy + sqHalf, markerFillBlack)
        canvas.drawRect(ox - sqHalf, oy - sqHalf, ox + sqHalf, oy + sqHalf, originStrokePaint)

        val dx = px[count - 1]
        val dy = py[count - 1]
        canvas.drawCircle(dx, dy, dpF(5.5f), markerFillBlack)
        canvas.drawCircle(dx, dy, dpF(4.4f), markerFillWhite)
        canvas.drawCircle(dx, dy, dpF(2.2f), markerFillRed)

        val originRect = drawEndpointLabel(
            canvas = canvas,
            x = ox,
            y = oy,
            text = originLabel,
            preferAbove = oy >= dy,
            viewW = w,
            viewH = h,
            avoidA = tagRect,
            avoidB = null
        )
        drawEndpointLabel(
            canvas = canvas,
            x = dx,
            y = dy,
            text = destLabel,
            preferAbove = dy < oy,
            viewW = w,
            viewH = h,
            avoidA = tagRect,
            avoidB = originRect
        )
    }

    private fun drawOsmTiles(
        canvas: Canvas,
        w: Float,
        h: Float,
        zoom: Int,
        centerWX: Double,
        centerWY: Double,
        scale: Float
    ) {
        val halfWorldW = (w * 0.5f) / scale
        val halfWorldH = (h * 0.5f) / scale
        var minTX = floor((centerWX - halfWorldW) / TILE_SIZE).toInt()
        var maxTX = floor((centerWX + halfWorldW) / TILE_SIZE).toInt()
        var minTY = floor((centerWY - halfWorldH) / TILE_SIZE).toInt()
        var maxTY = floor((centerWY + halfWorldH) / TILE_SIZE).toInt()

        val centerTX = floor(centerWX / TILE_SIZE).toInt()
        val centerTY = floor(centerWY / TILE_SIZE).toInt()
        if (maxTX - minTX + 1 > 3) {
            minTX = centerTX - 1
            maxTX = centerTX + 1
        }
        if (maxTY - minTY + 1 > 3) {
            minTY = centerTY - 1
            maxTY = centerTY + 1
        }

        val numTiles = 1 shl zoom
        val halfW = w * 0.5f
        val halfH = h * 0.5f

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        for (ty in minTY..maxTY) {
            if (ty < 0 || ty >= numTiles) continue
            for (tx in minTX..maxTX) {
                val wrappedX = ((tx % numTiles) + numTiles) % numTiles
                val key = "v2_${zoom}_${wrappedX}_${ty}"
                val bmp = tileCache.get(key)
                if (bmp != null && !bmp.isRecycled) {
                    val worldLeft = tx * TILE_SIZE.toDouble()
                    val worldTop = ty * TILE_SIZE.toDouble()
                    val screenLeft = halfW + ((worldLeft - centerWX) * scale).toFloat()
                    val screenTop = halfH + ((worldTop - centerWY) * scale).toFloat()
                    val tilePx = TILE_SIZE * scale
                    tileSrcRect.set(0, 0, bmp.width, bmp.height)
                    // +0.6f overlap eliminates hairline seams from fractional scaling
                    tileDstRect.set(screenLeft, screenTop, screenLeft + tilePx + 0.6f, screenTop + tilePx + 0.6f)
                    canvas.drawBitmap(bmp, tileSrcRect, tileDstRect, tilePaint)
                } else {
                    requestTileAsync(zoom, wrappedX, ty, key)
                }
            }
        }
        canvas.restore()
    }

    private fun requestTileAsync(zoom: Int, tx: Int, ty: Int, key: String) {
        val lastFail = failedTileAtMs[key]
        if (lastFail != null && System.currentTimeMillis() - lastFail < TILE_RETRY_COOLDOWN_MS) {
            return
        }
        if (inFlightTiles.putIfAbsent(key, true) != null) return

        val dir = tileCacheDir
        tileExecutor.execute {
            try {
                var bmp: Bitmap? = null
                val diskFile = dir?.let { File(it, "$key.png") }
                if (diskFile != null && diskFile.exists() && diskFile.length() > 100L) {
                    bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                }

                if (bmp == null) {
                    // ESRI World Dark Gray Base (free, no API key, z/y/x ordering, crisp street network)
                    val esriUrl = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$zoom/$ty/$tx"
                    val esriBytes = downloadBytes(esriUrl)
                    if (esriBytes != null) {
                        val raw = BitmapFactory.decodeByteArray(esriBytes, 0, esriBytes.size)
                        if (raw != null) {
                            val normalized = normalizeEsriDarkToBrutalist(raw)
                            if (raw !== normalized) raw.recycle()
                            bmp = normalized
                            if (diskFile != null) {
                                val out = ByteArrayOutputStream()
                                normalized.compress(Bitmap.CompressFormat.PNG, 90, out)
                                writeBytesQuietly(diskFile, out.toByteArray())
                            }
                        }
                    }
                }

                if (bmp == null) {
                    // Fallback: official OpenStreetMap slippy tile inverted to dark brutalist monochrome
                    val osmUrl = "https://tile.openstreetmap.org/$zoom/$tx/$ty.png"
                    val osmBytes = downloadBytes(osmUrl)
                    if (osmBytes != null) {
                        val lightBmp = BitmapFactory.decodeByteArray(osmBytes, 0, osmBytes.size)
                        if (lightBmp != null) {
                            val darkBmp = invertOsmToDarkBrutalist(lightBmp)
                            if (lightBmp !== darkBmp) lightBmp.recycle()
                            bmp = darkBmp
                            if (diskFile != null) {
                                val out = ByteArrayOutputStream()
                                darkBmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                                writeBytesQuietly(diskFile, out.toByteArray())
                            }
                        }
                    }
                }

                if (bmp != null) {
                    tileCache.put(key, bmp)
                    failedTileAtMs.remove(key)
                    postInvalidate()
                } else {
                    failedTileAtMs[key] = System.currentTimeMillis()
                }
            } catch (_: Throwable) {
                failedTileAtMs[key] = System.currentTimeMillis()
            } finally {
                inFlightTiles.remove(key)
            }
        }
    }

    private fun downloadBytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                if (this is HttpsURLConnection) {
                    sslSocketFactory = Tls12SocketFactory.socketFactory()
                }
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", TILE_USER_AGENT)
                setRequestProperty("Accept", "image/png,image/jpeg,image/*;q=0.8")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
                val bytes = out.toByteArray()
                if (bytes.size > 100) bytes else null
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun normalizeEsriDarkToBrutalist(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // Maps ESRI Dark Gray Base (#46 land, #62 roads) -> pure #000000 land, #272727..#555555 streets
        val p = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0.84f, 1.64f, 0.32f, 0f, -205f,
                        0.84f, 1.64f, 0.32f, 0f, -205f,
                        0.84f, 1.64f, 0.32f, 0f, -205f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    private fun invertOsmToDarkBrutalist(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val invertPaint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -0.38f, -0.75f, -0.17f, 0f, 305f,
                        -0.38f, -0.75f, -0.17f, 0f, 305f,
                        -0.38f, -0.75f, -0.17f, 0f, 305f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        c.drawBitmap(src, 0f, 0f, invertPaint)
        return out
    }

    private fun writeBytesQuietly(file: File, data: ByteArray) {
        try {
            FileOutputStream(file).use { it.write(data) }
        } catch (_: Throwable) {
        }
    }

    private fun drawTacticalGrid(canvas: Canvas, w: Float, h: Float) {
        val gridStep = dpF(20f).coerceAtLeast(12f)
        var gx = gridStep
        while (gx < w) {
            canvas.drawLine(gx, 0f, gx, h, gridPaint)
            gx += gridStep
        }
        var gy = gridStep
        while (gy < h) {
            canvas.drawLine(0f, gy, w, gy, gridPaint)
            gy += gridStep
        }

        val cx = w * 0.5f
        val cy = h * 0.5f
        canvas.drawLine(cx, 0f, cx, h, crosshairPaint)
        canvas.drawLine(0f, cy, w, cy, crosshairPaint)
        val reticle = dpF(5f)
        canvas.drawLine(cx - reticle, cy, cx + reticle, cy, cornerTickPaint)
        canvas.drawLine(cx, cy - reticle, cx, cy + reticle, cornerTickPaint)
    }

    private fun drawCornerBrackets(canvas: Canvas, w: Float, h: Float) {
        val inset = dpF(3f)
        val tickLen = dpF(8f)
        // Top-left
        canvas.drawLine(inset, inset, inset + tickLen, inset, cornerTickPaint)
        canvas.drawLine(inset, inset, inset, inset + tickLen, cornerTickPaint)
        // Top-right
        canvas.drawLine(w - inset - tickLen, inset, w - inset, inset, cornerTickPaint)
        canvas.drawLine(w - inset, inset, w - inset, inset + tickLen, cornerTickPaint)
        // Bottom-left
        canvas.drawLine(inset, h - inset, inset + tickLen, h - inset, cornerTickPaint)
        canvas.drawLine(inset, h - inset - tickLen, inset, h - inset, cornerTickPaint)
        // Bottom-right
        canvas.drawLine(w - inset - tickLen, h - inset, w - inset, h - inset, cornerTickPaint)
        canvas.drawLine(w - inset, h - inset - tickLen, w - inset, h - inset, cornerTickPaint)
    }

    private fun buildAndDrawRoutePaths(canvas: Canvas, count: Int, px: FloatArray, py: FloatArray) {
        basePath.reset()
        normalPath.reset()
        moderatePath.reset()
        heavyPath.reset()

        basePath.moveTo(px[0], py[0])
        var lastNormal = -1
        var lastMod = -1
        var lastHeavy = -1

        for (i in 0 until count - 1) {
            basePath.lineTo(px[i + 1], py[i + 1])
            val level = if (i < congestionLevels.size) congestionLevels[i] else 0
            when (level) {
                2 -> {
                    if (lastHeavy != i) heavyPath.moveTo(px[i], py[i])
                    heavyPath.lineTo(px[i + 1], py[i + 1])
                    lastHeavy = i + 1
                }
                1 -> {
                    if (lastMod != i) moderatePath.moveTo(px[i], py[i])
                    moderatePath.lineTo(px[i + 1], py[i + 1])
                    lastMod = i + 1
                }
                else -> {
                    if (lastNormal != i) normalPath.moveTo(px[i], py[i])
                    normalPath.lineTo(px[i + 1], py[i + 1])
                    lastNormal = i + 1
                }
            }
        }

        canvas.drawPath(basePath, baseCasingPaint)
        if (!normalPath.isEmpty) canvas.drawPath(normalPath, normalSegPaint)
        if (!moderatePath.isEmpty) canvas.drawPath(moderatePath, moderateSegPaint)
        if (!heavyPath.isEmpty) {
            canvas.drawPath(heavyPath, heavyGlowPaint)
            canvas.drawPath(heavyPath, heavyCorePaint)
        }
    }

    private fun drawTelemetryTag(canvas: Canvas, w: Float): RectF {
        val text = if (delayMin > 0) "TRAFICO +${delayMin}M" else "TRAFICO FLUIDO"
        tagTextPaint.color = if (delayMin > 0) BrutalistTheme.TRAFFIC_RED_HIGH else BrutalistTheme.FG_MUTED
        tagTextPaint.textAlign = Paint.Align.LEFT

        val padX = dpF(5f)
        val padY = dpF(3f)
        val fm = tagTextPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val textW = tagTextPaint.measureText(text)
        val right = w - dpF(5f)
        val top = dpF(5f)
        val rect = RectF(right - textW - padX * 2f, top, right, top + textH + padY * 2f)

        canvas.drawRect(rect, labelBgPaint)
        labelBorderPaint.color = if (delayMin > 0) BrutalistTheme.TRAFFIC_RED_DARK else BrutalistTheme.BORDER
        canvas.drawRect(rect, labelBorderPaint)
        canvas.drawText(text, rect.left + padX, rect.top + padY - fm.ascent, tagTextPaint)
        return rect
    }

    private fun drawEndpointLabel(
        canvas: Canvas,
        x: Float,
        y: Float,
        text: String,
        preferAbove: Boolean,
        viewW: Float,
        viewH: Float,
        avoidA: RectF?,
        avoidB: RectF?
    ): RectF? {
        if (text.isBlank()) return null
        val padX = dpF(4f)
        val padY = dpF(2f)
        val offset = dpF(8f)
        val margin = dpF(4f)
        val fm = labelTextPaint.fontMetrics
        val boxW = labelTextPaint.measureText(text) + padX * 2f
        val boxH = (fm.descent - fm.ascent) + padY * 2f

        fun candidateAt(cx: Float, top: Float): RectF {
            val clampedL = (cx - boxW * 0.5f).coerceIn(margin, (viewW - margin - boxW).coerceAtLeast(margin))
            val clampedT = top.coerceIn(margin, (viewH - margin - boxH).coerceAtLeast(margin))
            return RectF(clampedL, clampedT, clampedL + boxW, clampedT + boxH)
        }

        val above = candidateAt(x, y - offset - boxH)
        val below = candidateAt(x, y + offset)
        val right = candidateAt(x + offset + boxW * 0.5f, y - boxH * 0.5f)
        val left = candidateAt(x - offset - boxW * 0.5f, y - boxH * 0.5f)
        val candidates = if (preferAbove) listOf(above, below, right, left) else listOf(below, above, right, left)

        val chosen = candidates.firstOrNull { r ->
            (avoidA == null || !RectF.intersects(r, avoidA)) &&
                (avoidB == null || !RectF.intersects(r, avoidB))
        } ?: candidates.first()

        canvas.drawRect(chosen, labelBgPaint)
        labelBorderPaint.color = BrutalistTheme.BORDER
        canvas.drawRect(chosen, labelBorderPaint)
        labelTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, chosen.left + padX, chosen.top + padY - fm.ascent, labelTextPaint)
        return chosen
    }

    companion object {
        private const val TILE_SIZE = 256f
        private const val MIN_ZOOM = 10
        private const val MAX_ZOOM = 16
        private const val TILE_USER_AGENT = "SemyraAssistantTablet/1.0"
        private const val TILE_RETRY_COOLDOWN_MS = 60_000L

        private val tileCache = object : LruCache<String, Bitmap>(6 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.byteCount / 1024).coerceAtLeast(1)
        }
        private val inFlightTiles = ConcurrentHashMap<String, Boolean>()
        private val failedTileAtMs = ConcurrentHashMap<String, Long>()
        private val tileExecutor: ExecutorService = Executors.newFixedThreadPool(3) { r ->
            Thread(r, "OsmTileFetcher").apply { isDaemon = true }
        }

        internal fun lonToWorldX(lon: Double, zoom: Int): Double {
            val worldSize = 256.0 * (1 shl zoom)
            return ((lon + 180.0) / 360.0) * worldSize
        }

        internal fun latToWorldY(lat: Double, zoom: Int): Double {
            val clamped = lat.coerceIn(-85.05112878, 85.05112878)
            val rad = Math.toRadians(clamped)
            val mercN = ln(tan(Math.PI / 4.0 + rad / 2.0))
            val worldSize = 256.0 * (1 shl zoom)
            return ((1.0 - mercN / Math.PI) / 2.0) * worldSize
        }

        internal fun chooseTileZoom(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
            viewW: Float,
            viewH: Float,
            baseScale: Float = 1f
        ): Pair<Int, Float> {
            val availW = (viewW * 0.72f).coerceAtLeast(32f)
            val availH = (viewH * 0.66f).coerceAtLeast(24f)
            for (z in MAX_ZOOM downTo MIN_ZOOM) {
                val spanX = (lonToWorldX(maxLon, z) - lonToWorldX(minLon, z)).coerceAtLeast(1.0)
                val spanY = (latToWorldY(minLat, z) - latToWorldY(maxLat, z)).coerceAtLeast(1.0)
                val fitScale = min(availW / spanX.toFloat(), availH / spanY.toFloat())
                if (fitScale >= 0.72f * baseScale) {
                    val clampedScale = fitScale.coerceIn(0.72f * baseScale, 1.85f * baseScale)
                    return z to clampedScale
                }
            }
            val spanX = (lonToWorldX(maxLon, MIN_ZOOM) - lonToWorldX(minLon, MIN_ZOOM)).coerceAtLeast(1.0)
            val spanY = (latToWorldY(minLat, MIN_ZOOM) - latToWorldY(maxLat, MIN_ZOOM)).coerceAtLeast(1.0)
            val fallbackScale = min(availW / spanX.toFloat(), availH / spanY.toFloat()).coerceAtLeast(0.05f)
            return MIN_ZOOM to fallbackScale
        }
    }
}

/**
 * Renders a monochrome 30-day market sparkline curve with subtle dot-matrix fill,
 * dotted mid-grid baseline, and MIN/MAX price labels in Brutalist style.
 */
class MarketSparklineView(context: Context) : View(context) {
    private var symbol: String = ""
    private var closes: List<Double> = emptyList()
    private var changePct1m: Double = 0.0

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF262626.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(1f)
        pathEffect = DashPathEffect(floatArrayOf(dpF(1.5f), dpF(4f)), 0f)
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.BORDER
        style = Paint.Style.STROKE
        strokeWidth = dpF(1f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val endDotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val endDotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.8f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.FG_MUTED
        typeface = BrutalistTheme.fontMono()
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.FG_DIM
        textAlign = Paint.Align.CENTER
        typeface = BrutalistTheme.fontMono()
    }

    private val curvePath = Path()
    private val fillPath = Path()
    private val gridLinePath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setSparklineData(symbol: String, closes: List<Double>, changePct1m: Double) {
        this.symbol = symbol
        this.closes = closes.filter { it.isFinite() && it > 0.0 }
        this.changePct1m = changePct1m
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val mono = BrutalistTheme.fontMono()
        labelPaint.typeface = mono
        emptyPaint.typeface = mono
        val labelSize = (h * 0.145f).coerceIn(dpF(8.5f), dpF(10.5f))
        labelPaint.textSize = labelSize
        emptyPaint.textSize = labelSize

        val plotLeft = dpF(3f)
        val plotRight = (w - dpF(3f)).coerceAtLeast(plotLeft + dpF(24f))
        val plotTop = dpF(2f)
        val plotBottom = (h - dpF(2f)).coerceAtLeast(plotTop + dpF(18f))
        val midY = (plotTop + plotBottom) * 0.5f

        drawDottedLine(canvas, plotLeft, midY, plotRight, midY)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, baselinePaint)

        if (closes.size < 2) {
            canvas.drawText(
                "ESPERANDO COTIZACIÓN 30D...",
                (plotLeft + plotRight) * 0.5f,
                midY - dpF(4f),
                emptyPaint
            )
            return
        }

        val n = closes.size
        var minVal = closes[0]
        var maxVal = closes[0]
        for (i in 1 until n) {
            if (closes[i] < minVal) minVal = closes[i]
            if (closes[i] > maxVal) maxVal = closes[i]
        }
        val span = maxVal - minVal

        val curveTop = (plotTop + labelSize + dpF(4f)).coerceAtMost(midY - dpF(2f))
        val curveBottom = (plotBottom - labelSize - dpF(4f)).coerceAtLeast(midY + dpF(2f))
        val curveSpan = (curveBottom - curveTop).coerceAtLeast(dpF(4f))

        val px = FloatArray(n)
        val py = FloatArray(n)
        val plotW = plotRight - plotLeft
        for (i in 0 until n) {
            px[i] = plotLeft + (i.toFloat() / (n - 1)) * plotW
            val norm = if (span > 1e-6) ((closes[i] - minVal) / span).toFloat().coerceIn(0f, 1f) else 0.5f
            py[i] = curveBottom - norm * curveSpan
        }

        buildPaths(px, py, plotBottom, curveTop, curveBottom)

        var minPy = py[0]
        for (i in 1 until n) {
            if (py[i] < minPy) minPy = py[i]
        }

        drawDotFill(canvas, plotLeft, plotRight, plotTop, plotBottom, minPy)
        canvas.drawPath(curvePath, curvePaint)

        val endR = dpF(2.8f)
        canvas.drawCircle(px[n - 1], py[n - 1], endR, endDotFillPaint)
        canvas.drawCircle(px[n - 1], py[n - 1], endR, endDotRingPaint)

        // Top-left MAX and bottom-right MIN price labels
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("MAX ${formatPrice(maxVal)}", plotLeft + dpF(2f), plotTop + labelSize, labelPaint)

        labelPaint.textAlign = Paint.Align.RIGHT
        val tag30d = if (symbol.isNotBlank()) "30D · ${formatPct(changePct1m)}" else "30D"
        canvas.drawText(tag30d, plotRight - dpF(2f), plotTop + labelSize, labelPaint)
        canvas.drawText("MIN ${formatPrice(minVal)}", plotRight - dpF(2f), plotBottom - dpF(3f), labelPaint)
    }

    private fun buildPaths(
        px: FloatArray,
        py: FloatArray,
        plotBottom: Float,
        curveTop: Float,
        curveBottom: Float
    ) {
        curvePath.reset()
        fillPath.reset()
        val n = px.size
        curvePath.moveTo(px[0], py[0])
        fillPath.moveTo(px[0], plotBottom)
        fillPath.lineTo(px[0], py[0])

        for (i in 0 until n - 1) {
            val i0 = (i - 1).coerceAtLeast(0)
            val i3 = (i + 2).coerceAtMost(n - 1)
            val segMinX = min(px[i], px[i + 1])
            val segMaxX = max(px[i], px[i + 1])
            val segMinY = min(py[i], py[i + 1])
            val segMaxY = max(py[i], py[i + 1])

            val c1x = (px[i] + (px[i + 1] - px[i0]) / 6f).coerceIn(segMinX, segMaxX)
            val c1y = (py[i] + (py[i + 1] - py[i0]) / 6f).coerceIn(segMinY, segMaxY).coerceIn(curveTop, curveBottom)
            val c2x = (px[i + 1] - (px[i3] - px[i]) / 6f).coerceIn(segMinX, segMaxX)
            val c2y = (py[i + 1] - (py[i3] - py[i]) / 6f).coerceIn(segMinY, segMaxY).coerceIn(curveTop, curveBottom)

            curvePath.cubicTo(c1x, c1y, c2x, c2y, px[i + 1], py[i + 1])
            fillPath.cubicTo(c1x, c1y, c2x, c2y, px[i + 1], py[i + 1])
        }

        fillPath.lineTo(px[n - 1], plotBottom)
        fillPath.close()
    }

    private fun drawDotFill(
        canvas: Canvas,
        plotLeft: Float,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        minPy: Float
    ) {
        val dotR = dpF(1.1f)
        val step = dpF(5f)
        val span = (plotBottom - minPy).coerceAtLeast(dpF(16f))
        val startRow = floor(((minPy - plotTop) / step).toDouble()).toInt().coerceAtLeast(0)
        val endRow = ceil(((plotBottom - plotTop) / step).toDouble()).toInt() + 1

        canvas.save()
        canvas.clipPath(fillPath)
        for (row in startRow..endRow) {
            val y = plotTop + row * step
            val t = ((y - minPy) / span).coerceIn(0f, 1f)
            dotFillPaint.alpha = (145f * (1f - t * 0.86f)).toInt().coerceIn(14, 155)
            val offsetX = if (row % 2 == 1) step * 0.5f else 0f
            var x = plotLeft - step + offsetX
            while (x <= plotRight + step) {
                canvas.drawCircle(x, y, dotR, dotFillPaint)
                x += step
            }
        }
        canvas.restore()
    }

    private fun drawDottedLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        gridLinePath.reset()
        gridLinePath.moveTo(x1, y1)
        gridLinePath.lineTo(x2, y2)
        canvas.drawPath(gridLinePath, gridPaint)
    }

    private fun formatPrice(v: Double): String = when {
        v >= 1000.0 -> String.format(java.util.Locale.US, "%.0f", v)
        v >= 100.0 -> String.format(java.util.Locale.US, "%.1f", v)
        else -> String.format(java.util.Locale.US, "%.2f", v)
    }

    private fun formatPct(v: Double): String {
        val sign = if (v >= 0.0) "+" else ""
        return String.format(java.util.Locale.US, "%s%.1f%%", sign, v)
    }
}

/**
 * Polar Sky-Plot Satellite Radar for overhead non-GEO EU (Galileo/ESA) and US (GPS/ISS/NOAA) satellites.
 * Uses clean sans-serif typography inside the plot (Doto is reserved for panel titles).
 */
class SatelliteRadarView(context: Context) : View(context) {
    private var snapshot: com.javiblzq.assistant.data.SatelliteSkySnapshot? = null
    private var focusIndex: Int = 0
    var onSatelliteSelected: ((com.javiblzq.assistant.data.SatelliteOverheadItem?) -> Unit)? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var sweepAngleDeg: Float = 38f

    private val bgFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF04070A.toInt()
        style = Paint.Style.FILL
    }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A4A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.4f)
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF282828.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(1f)
        pathEffect = DashPathEffect(floatArrayOf(dpF(2.5f), dpF(3.5f)), 0f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF222222.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(0.9f)
        pathEffect = DashPathEffect(floatArrayOf(dpF(2f), dpF(4f)), 0f)
    }
    private val sweepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5538BDF8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.3f)
    }
    private val sweepSectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1838BDF8.toInt()
        style = Paint.Style.FILL
    }
    private val cardinalNorthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = BrutalistTheme.fontMono()
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.FG_MUTED
        textAlign = Paint.Align.CENTER
        typeface = BrutalistTheme.fontMono()
    }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.FG_DIM
        textAlign = Paint.Align.LEFT
        typeface = BrutalistTheme.fontMono()
    }
    private val euFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38BDF8.toInt() // Cyan/Electric Blue for EU (Galileo / ESA)
        style = Paint.Style.FILL
    }
    private val usFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF59E0B.toInt() // Radar Amber for US (GPS / NOAA / NASA)
        style = Paint.Style.FILL
    }
    private val issFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BrutalistTheme.ACCENT_RED // High-contrast Red for ISS
        style = Paint.Style.FILL
    }
    private val hwLockRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22C55E.toInt() // Green hardware GNSS lock halo
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.5f)
    }
    private val focusReticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpF(1.4f)
    }
    private val satLabelHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = dpF(2.8f)
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.LEFT
        typeface = BrutalistTheme.fontMono()
    }
    private val satLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        typeface = BrutalistTheme.fontMono()
    }
    private val diamondPath = Path()
    private val gridPath = Path()
    private val sweepArcRect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setSkySnapshot(data: com.javiblzq.assistant.data.SatelliteSkySnapshot?) {
        val selectedId = getFocusedSatellite()?.id
        snapshot = data
        focusIndex = data?.satellites?.indexOfFirst { it.id == selectedId }?.coerceAtLeast(0) ?: 0
        val size = data?.satellites?.size ?: 0
        if (size == 0) {
            focusIndex = 0
        } else if (focusIndex >= size) {
            focusIndex = 0
        }
        sweepAngleDeg = (sweepAngleDeg + 7f) % 360f
        invalidate()
    }

    fun cycleFocusedSatellite(): com.javiblzq.assistant.data.SatelliteOverheadItem? {
        val sats = snapshot?.satellites.orEmpty()
        if (sats.isEmpty()) return null
        val maxCycle = min(sats.size, 8)
        focusIndex = (focusIndex + 1) % maxCycle
        sweepAngleDeg = sats[focusIndex].azimuthDeg
        invalidate()
        return sats[focusIndex]
    }

    fun getFocusedSatellite(): com.javiblzq.assistant.data.SatelliteOverheadItem? {
        val sats = snapshot?.satellites.orEmpty()
        if (sats.isEmpty()) return null
        return sats[focusIndex.coerceIn(0, sats.lastIndex)]
    }

    private fun radarRadius(): Float {
        val size = min(width.toFloat(), height.toFloat())
        val cardinalSize = (size * 0.068f).coerceIn(dpF(9.5f), dpF(12f))
        return (size * 0.5f - cardinalSize - dpF(5f)).coerceAtLeast(dpF(24f))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dx = event.x - width * 0.5f
                val dy = event.y - height * 0.5f
                if (dx * dx + dy * dy > radarRadius() * radarRadius()) return false
                touchDownX = event.x
                touchDownY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                if (kotlin.math.abs(event.x - touchDownX) > slop ||
                    kotlin.math.abs(event.y - touchDownY) > slop) return true
                val sats = snapshot?.satellites.orEmpty()
                val radius = radarRadius()
                val nearest = sats.indices.minByOrNull { index ->
                    val sat = sats[index]
                    val r = radius * (1f - sat.elevationDeg.coerceIn(0f, 90f) / 90f)
                    val az = Math.toRadians(sat.azimuthDeg.toDouble())
                    val dx = event.x - (width * 0.5f + r * kotlin.math.sin(az).toFloat())
                    val dy = event.y - (height * 0.5f - r * cos(az).toFloat())
                    dx * dx + dy * dy
                }
                if (nearest != null) focusIndex = nearest
                invalidate()
                onSatelliteSelected?.invoke(getFocusedSatellite())
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cardinalSize = (min(w, h) * 0.068f).coerceIn(dpF(9.5f), dpF(12f))
        val ringLabelSize = (min(w, h) * 0.052f).coerceIn(dpF(7.5f), dpF(9.5f))
        val satLabelSize = (min(w, h) * 0.058f).coerceIn(dpF(8.5f), dpF(10.5f))

        cardinalNorthPaint.textSize = cardinalSize
        cardinalPaint.textSize = cardinalSize
        ringLabelPaint.textSize = ringLabelSize
        satLabelPaint.textSize = satLabelSize
        satLabelHaloPaint.textSize = satLabelSize

        val radius = radarRadius()
        val cx = w * 0.5f
        val cy = h * 0.5f

        // 1. Radar background disc & subtle sweep sector
        canvas.drawCircle(cx, cy, radius, bgFillPaint)
        sweepArcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val startAngleCanvas = sweepAngleDeg - 90f - 28f
        canvas.drawArc(sweepArcRect, startAngleCanvas, 28f, true, sweepSectorPaint)

        // 2. Concentric elevation rings: 0° (outer), 30° (2/3 R), 60° (1/3 R), zenith crosshair
        canvas.drawCircle(cx, cy, radius, outerRingPaint)
        canvas.drawCircle(cx, cy, radius * (2f / 3f), innerRingPaint)
        canvas.drawCircle(cx, cy, radius * (1f / 3f), innerRingPaint)

        // Cardinal N-S / E-W dashed axes
        drawDashedSegment(canvas, cx, cy - radius, cx, cy + radius)
        drawDashedSegment(canvas, cx - radius, cy, cx + radius, cy)

        // Zenith crosshair
        val cross = dpF(4f)
        canvas.drawLine(cx - cross, cy, cx + cross, cy, outerRingPaint)
        canvas.drawLine(cx, cy - cross, cx, cy + cross, outerRingPaint)

        // Subtle radar sweep vector
        val sweepRad = Math.toRadians(sweepAngleDeg.toDouble())
        val sx = cx + radius * kotlin.math.sin(sweepRad).toFloat()
        val sy = cy - radius * cos(sweepRad).toFloat()
        canvas.drawLine(cx, cy, sx, sy, sweepLinePaint)

        // Elevation ring annotations (30°, 60°)
        canvas.drawText("30°", cx + dpF(3f), cy - radius * (2f / 3f) + ringLabelSize * 0.9f, ringLabelPaint)
        canvas.drawText("60°", cx + dpF(3f), cy - radius * (1f / 3f) + ringLabelSize * 0.9f, ringLabelPaint)

        // Cardinal labels N / E / S / O
        val fm = cardinalPaint.fontMetrics
        val halfAscent = (-fm.ascent) * 0.36f
        canvas.drawText("N", cx, cy - radius - dpF(4f), cardinalNorthPaint)
        canvas.drawText("S", cx, cy + radius + (-fm.ascent) + dpF(2f), cardinalPaint)
        canvas.drawText("E", cx + radius + cardinalSize * 0.65f, cy + halfAscent, cardinalPaint)
        canvas.drawText("O", cx - radius - cardinalSize * 0.65f, cy + halfAscent, cardinalPaint)

        // 3. Plot overhead satellites
        val sats = snapshot?.satellites.orEmpty()
        if (sats.isEmpty()) return

        val focusedSat = sats.getOrNull(focusIndex.coerceIn(0, sats.lastIndex))
        val placedLabels = mutableListOf<RectF>()
        val markerR = dpF(3.4f)

        // Draw lowest elevation first so zenith/focused satellites render cleanly on top
        for (sat in sats.asReversed()) {
            val r = radius * (1f - sat.elevationDeg.coerceIn(0f, 90f) / 90f)
            val azRad = Math.toRadians(sat.azimuthDeg.toDouble())
            val px = cx + r * kotlin.math.sin(azRad).toFloat()
            val py = cy - r * cos(azRad).toFloat()

            val isFocused = focusedSat != null && sat.id == focusedSat.id && sat.name == focusedSat.name
            val isEu = sat.agency == "EU"
            val isIss = sat.constellation == "ISS"

            if (sat.hardwareLocked) {
                canvas.drawCircle(px, py, markerR + dpF(2.5f), hwLockRingPaint)
            }
            if (isFocused) {
                canvas.drawCircle(px, py, markerR + dpF(4.2f), focusReticlePaint)
            }

            if (isIss) {
                canvas.drawCircle(px, py, markerR * 1.25f, issFillPaint)
                canvas.drawCircle(px, py, markerR * 2.1f, focusReticlePaint)
            } else if (isEu) {
                // Diamond marker for EU (Galileo / ESA)
                val d = markerR * 1.2f
                diamondPath.reset()
                diamondPath.moveTo(px, py - d)
                diamondPath.lineTo(px + d, py)
                diamondPath.lineTo(px, py + d)
                diamondPath.lineTo(px - d, py)
                diamondPath.close()
                canvas.drawPath(diamondPath, euFillPaint)
            } else {
                // Circle marker for US (GPS / NOAA / NASA)
                canvas.drawCircle(px, py, markerR, usFillPaint)
            }

            // Compact label with simple collision avoidance
            val labelText = sat.name
            val tw = satLabelPaint.measureText(labelText)
            val th = satLabelSize
            val lx = if (px > cx + radius * 0.45f) px - markerR - dpF(3f) - tw else px + markerR + dpF(3f)
            val ly = (py + th * 0.35f).coerceIn(cy - radius + th, cy + radius - dpF(2f))
            val box = RectF(lx - dpF(1f), ly - th, lx + tw + dpF(1f), ly + dpF(2f))

            val overlaps = placedLabels.any { RectF.intersects(it, box) }
            if (!overlaps || isFocused || isIss || sat.hardwareLocked) {
                placedLabels.add(box)
                satLabelPaint.color = when {
                    isIss -> BrutalistTheme.ACCENT_RED
                    isFocused -> Color.WHITE
                    isEu -> 0xFF7DD3FC.toInt()
                    else -> 0xFFFCD34D.toInt()
                }
                canvas.drawText(labelText, lx, ly, satLabelHaloPaint)
                canvas.drawText(labelText, lx, ly, satLabelPaint)
            }
        }
    }

    private fun drawDashedSegment(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        gridPath.reset()
        gridPath.moveTo(x1, y1)
        gridPath.lineTo(x2, y2)
        canvas.drawPath(gridPath, axisPaint)
    }
}
