package com.kisreplay.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kisreplay.android.data.Candle
import kotlin.math.max

@Composable
fun CandleChart(
    bars: List<Candle>,
    endIndex: Int = bars.size,
    displayBars: Int = 160,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.surface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val up = Color(0xFFE53935)
    val down = Color(0xFF1E88E5)
    val text = MaterialTheme.colorScheme.onSurface
    Box(modifier = modifier.fillMaxWidth().height(240.dp).background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            if (bars.isEmpty()) return@Canvas
            val end = endIndex.coerceIn(1, bars.size)
            val start = max(0, end - displayBars.coerceAtLeast(10))
            val visible = bars.subList(start, end)
            val hi = visible.maxOf { it.high }.toFloat()
            val lo = visible.minOf { it.low }.toFloat()
            val range = (hi - lo).coerceAtLeast(1f)
            val topPad = 12f
            val bottomPad = 12f
            val h = size.height - topPad - bottomPad
            val step = size.width / visible.size.coerceAtLeast(1)
            repeat(5) { n ->
                val y = topPad + h * n / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            fun y(price: Long): Float = topPad + (hi - price.toFloat()) / range * h
            visible.forEachIndexed { i, c ->
                val x = step * (i + 0.5f)
                val color = if (c.close >= c.open) up else down
                drawLine(color, Offset(x, y(c.high)), Offset(x, y(c.low)), strokeWidth = 1.5f)
                val yo = y(c.open)
                val yc = y(c.close)
                val bodyTop = minOf(yo, yc)
                val bodyHeight = kotlin.math.abs(yo - yc).coerceAtLeast(2f)
                drawRect(color, topLeft = Offset(x - step * 0.28f, bodyTop), size = Size(step * 0.56f, bodyHeight))
            }
            drawLine(text.copy(alpha = 0.25f), Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 1f)
        }
    }
}
