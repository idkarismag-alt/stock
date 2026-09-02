package com.kisreplay.android.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.kisreplay.android.data.Candle
import com.kisreplay.android.data.SimTrade
import kotlin.math.abs
import kotlin.math.max

private val PC_BG = Color(0xFFC7C7C7)
private val PC_GRID = Color(0xFFE5E5E5)
private val PC_AXIS = Color(0xFF777777)
private val PC_UP = Color(0xFFFF2020)
private val PC_DOWN = Color(0xFF1267D6)
private val PC_DOWN_FILL = Color(0xFF8FB5E1)
private val MA_COLORS = mapOf(
    5 to Color(0xFFFF00C8),
    10 to Color(0xFF2355FF),
    20 to Color(0xFF00871A),
    60 to Color(0xFFFF2020),
    120 to Color(0xFF168E8E),
    240 to Color.Black,
    300 to Color.Black,
)

@Composable
fun CandleChart(
    bars: List<Candle>,
    endIndex: Int = bars.size,
    displayBars: Int = 160,
    modifier: Modifier = Modifier,
    title: String = "",
    maPeriods: List<Int> = listOf(5, 10, 20, 60, 120),
    percentBasePrice: Long = 0L,
    trades: List<SimTrade> = emptyList(),
    dailyGainStarPct: Double = 0.0,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 180.dp)
            .background(PC_BG)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (bars.isEmpty()) {
                drawLabel("차트 데이터 없음", size.width / 2f - 40f, size.height / 2f, Color.DarkGray, 12f)
                return@Canvas
            }
            val end = endIndex.coerceIn(1, bars.size)
            val visibleCount = displayBars.coerceIn(10, 5000)
            val start = max(0, end - visibleCount)
            val visible = bars.subList(start, end)

            val plotLeft = 8f
            val axisWidth = 108f.coerceAtMost(size.width * 0.28f)
            val plotRight = (size.width - axisWidth).coerceAtLeast(plotLeft + 40f)
            val titleTop = 22f
            val priceTop = 28f
            val priceBottom = size.height * 0.73f
            val volumeTop = priceBottom + 12f
            val volumeBottom = size.height - 18f
            val slot = (plotRight - plotLeft) / visible.size.coerceAtLeast(1)
            val bodyWidth = (slot * 0.58f).coerceIn(1f, 8f)

            val maAll = maPeriods.associateWith { movingAverage(bars, it) }
            val maVisible = maAll.values.flatMap { it.subList(start, end) }.filterNotNull()
            var lo = visible.minOf { it.low }.toDouble()
            var hi = visible.maxOf { it.high }.toDouble()
            if (maVisible.isNotEmpty()) {
                lo = minOf(lo, maVisible.minOrNull() ?: lo)
                hi = maxOf(hi, maVisible.maxOrNull() ?: hi)
            }
            val spread = (hi - lo).coerceAtLeast(1.0)
            lo -= spread * 0.04
            hi += spread * 0.04
            val range = (hi - lo).coerceAtLeast(1.0)
            val volumeMax = visible.maxOf { it.volume }.coerceAtLeast(1L)

            fun y(price: Double): Float = (priceBottom - ((price - lo) / range * (priceBottom - priceTop))).toFloat()

            repeat(6) { n ->
                val gy = priceTop + (priceBottom - priceTop) * n / 5f
                drawLine(PC_GRID, Offset(plotLeft, gy), Offset(plotRight, gy), 1f)
            }
            drawLine(PC_AXIS, Offset(plotRight, priceTop), Offset(plotRight, volumeBottom), 1f)
            drawLine(PC_AXIS.copy(alpha = 0.7f), Offset(plotLeft, priceBottom + 6f), Offset(plotRight, priceBottom + 6f), 1f)

            if (title.isNotBlank()) drawLabel(title, plotLeft + 2f, titleTop - 9f, Color(0xFF333333), 10f, true)
            var legendX = minOf(plotRight - 200f, 150f).coerceAtLeast(80f)
            maPeriods.forEach { p ->
                drawLabel(p.toString(), legendX, 15f, MA_COLORS[p] ?: Color.Black, 9f, true)
                legendX += 28f
            }

            visible.forEachIndexed { i, c ->
                val x = plotLeft + slot * (i + 0.5f)
                val up = c.close >= c.open
                val color = if (up) PC_UP else PC_DOWN
                drawLine(color, Offset(x, y(c.high.toDouble())), Offset(x, y(c.low.toDouble())), 1.2f)
                val yo = y(c.open.toDouble())
                val yc = y(c.close.toDouble())
                if (abs(yo - yc) < 1.5f) {
                    drawLine(color, Offset(x - bodyWidth / 2, yo), Offset(x + bodyWidth / 2, yo), 1.2f)
                } else {
                    drawRect(
                        color = if (up) color else PC_DOWN_FILL,
                        topLeft = Offset(x - bodyWidth / 2, minOf(yo, yc)),
                        size = Size(bodyWidth, abs(yo - yc)),
                    )
                    if (!up) drawRect(
                        color,
                        Offset(x - bodyWidth / 2, minOf(yo, yc)),
                        Size(bodyWidth, abs(yo - yc)),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(0.9f)
                    )
                }
                val vy = volumeBottom - c.volume.toFloat() / volumeMax.toFloat() * (volumeBottom - volumeTop)
                drawRect(color, Offset(x - bodyWidth / 2, vy), Size(bodyWidth, volumeBottom - vy))

                if (dailyGainStarPct > 0.0 && start + i > 0) {
                    val prevClose = bars[start + i - 1].close
                    if (prevClose > 0 && (c.high.toDouble() / prevClose - 1.0) * 100.0 >= dailyGainStarPct) {
                        drawLabel("★", x - 5f, (y(c.low.toDouble()) + 12f).coerceAtMost(priceBottom - 1f), PC_UP, 10f, true)
                    }
                }
            }

            maPeriods.forEach { period ->
                val values = maAll[period].orEmpty()
                var prev: Offset? = null
                for (globalIndex in start until end) {
                    val value = values.getOrNull(globalIndex) ?: continue
                    val local = globalIndex - start
                    val point = Offset(plotLeft + slot * (local + 0.5f), y(value))
                    prev?.let { drawLine(MA_COLORS[period] ?: Color.Black, it, point, if (period >= 240) 2f else 1.2f) }
                    prev = point
                }
            }

            val byTime = visible.mapIndexed { i, c -> c.time.filter(Char::isDigit) to i }.toMap()
            trades.forEach { t ->
                val key = t.barTime.filter(Char::isDigit)
                val local = byTime[key] ?: return@forEach
                val c = visible[local]
                val x = plotLeft + slot * (local + 0.5f)
                val py = y(c.close.toDouble())
                if (t.side == "BUY") {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(x, py + 2f); lineTo(x - 6f, py + 12f); lineTo(x + 6f, py + 12f); close()
                    }
                    drawPath(path, PC_UP)
                    drawLabel("매수 ${t.quantity}", x - 18f, py + 24f, PC_UP, 8f, true)
                } else {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(x, py - 2f); lineTo(x - 6f, py - 12f); lineTo(x + 6f, py - 12f); close()
                    }
                    drawPath(path, PC_DOWN)
                    drawLabel("매도 ${t.quantity}", x - 18f, py - 16f, PC_DOWN, 8f, true)
                }
            }

            repeat(6) { n ->
                val value = hi - range * n / 5.0
                val gy = priceTop + (priceBottom - priceTop) * n / 5f
                val pct = if (percentBasePrice > 0) (value / percentBasePrice - 1.0) * 100.0 else null
                val color = when {
                    pct == null -> Color(0xFF555555)
                    pct > 0 -> PC_UP
                    pct < 0 -> PC_DOWN
                    else -> Color(0xFF555555)
                }
                val label = if (pct == null) "%,.0f".format(value) else "%,.0f  %+.1f%%".format(value, pct)
                drawLabel(label, plotRight + 5f, gy + 3f, color, 8f)
            }

            val last = visible.last()
            val lastY = y(last.close.toDouble())
            val lastColor = if (last.close >= last.open) PC_UP else PC_DOWN
            drawLine(
                lastColor,
                Offset(plotLeft, lastY),
                Offset(plotRight, lastY),
                1f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
            )
            val lastPct = if (percentBasePrice > 0) (last.close.toDouble() / percentBasePrice - 1.0) * 100.0 else null
            val lastText = if (lastPct == null) "%,d".format(last.close) else "%,d  %+.1f%%".format(last.close, lastPct)
            drawRect(lastColor, Offset(plotRight + 1f, lastY - 9f), Size(axisWidth - 3f, 18f))
            drawLabel(lastText, plotRight + 5f, lastY + 3f, Color.White, 8f, true)

            listOf(0, visible.lastIndex / 2, visible.lastIndex).distinct().forEach { i ->
                val c = visible[i]
                val digits = c.time.filter(Char::isDigit)
                val label = when {
                    digits.length >= 12 -> "${digits.substring(8,10)}:${digits.substring(10,12)}"
                    digits.length >= 8 -> "${digits.substring(4,6)}/${digits.substring(6,8)}"
                    else -> c.time.takeLast(5)
                }
                val x = plotLeft + slot * (i + 0.5f)
                drawLabel(label, (x - 18f).coerceIn(plotLeft, plotRight - 40f), size.height - 4f, Color(0xFF555555), 8f)
            }
        }
    }
}

private fun movingAverage(bars: List<Candle>, period: Int): List<Double?> {
    if (period <= 0) return List(bars.size) { null }
    val out = MutableList<Double?>(bars.size) { null }
    var sum = 0.0
    bars.indices.forEach { i ->
        sum += bars[i].close
        if (i >= period) sum -= bars[i - period].close
        if (i >= period - 1) out[i] = sum / period
    }
    return out
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    sizeSp: Float,
    bold: Boolean = false,
) {
    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.argb(
                (color.alpha * 255).toInt(),
                (color.red * 255).toInt(),
                (color.green * 255).toInt(),
                (color.blue * 255).toInt(),
            )
            textSize = sizeSp * density
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.MONOSPACE
        }
        canvas.nativeCanvas.drawText(text, x, y, paint)
    }
}
