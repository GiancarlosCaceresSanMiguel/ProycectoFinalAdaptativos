package com.energiacontexto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.energiacontexto.client.BatteryPoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

@Composable
fun BatteryChart(
    history: List<BatteryPoint>,
    predictedDrop: Double?,
    charging: Boolean,
    modifier: Modifier = Modifier,
) {
    if (history.size < 2) {
        Text("Sin datos todavía", style = MaterialTheme.typography.bodySmall, modifier = modifier)
        return
    }
    val blue = Color(0xFF2563EB)
    val orange = Color(0xFFEA580C)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Canvas(modifier = modifier.fillMaxWidth().height(190.dp)) {
        val w = size.width
        val h = size.height
        val last = history.last()
        val pred = if (!charging) predictedDrop else null
        val t0 = history.first().t
        val t1 = last.t + if (pred != null) 30 * 60_000L else 0L
        val vals = history.map { it.battery } + listOfNotNull(pred?.let { last.battery - it })
        var ymin = max(0.0, floor(vals.minOrNull()!! - 2))
        var ymax = min(100.0, ceil(vals.maxOrNull()!! + 2))
        if (ymax - ymin < 1) ymax = ymin + 1
        val L = 40f
        val R = 8f
        val T = 8f
        val B = 24f
        fun x(t: Long) = L + (t - t0).toFloat() / (t1 - t0).coerceAtLeast(1) * (w - L - R)
        fun y(v: Double) = T + (1 - (v - ymin) / (ymax - ymin)).toFloat() * (h - T - B)

        for (i in 0..4) {
            val v = ymin + (ymax - ymin) * i / 4
            val yy = y(v)
            drawLine(grid, Offset(L, yy), Offset(w - R, yy), strokeWidth = 1f)
        }
        var px = x(history.first().t)
        var py = y(history.first().battery)
        for (p in history.drop(1)) {
            val nx = x(p.t)
            val ny = y(p.battery)
            drawLine(blue, Offset(px, py), Offset(nx, ny), strokeWidth = 4f)
            px = nx
            py = ny
        }
        if (pred != null) {
            drawLine(
                orange,
                Offset(x(last.t), y(last.battery)),
                Offset(x(t1), y(last.battery - pred)),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            )
        }
    }
}
