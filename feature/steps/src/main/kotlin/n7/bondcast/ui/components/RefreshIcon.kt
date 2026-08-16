package n7.bondcast.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Стрелка по кругу — «обнулить».
 *
 * Дуга разомкнута сверху, в разрыв поставлен треугольник-остриё: так виден и круг, и направление.
 */
@Composable
public fun RefreshIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = size.minDimension * 0.14f
        val inset = stroke / 2f + size.minDimension * 0.10f
        val box = Size(size.width - inset * 2f, size.height - inset * 2f)
        drawArc(
            color = color,
            startAngle = ARC_START,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = box,
            style = Stroke(width = stroke),
        )
        // остриё в разрыве дуги: сама дуга кончается справа сверху, туда и смотрит стрелка
        val tipX = inset + box.width
        val tipY = inset + box.height / 2f
        val wing = size.minDimension * 0.20f
        drawPath(
            path = Path().apply {
                moveTo(tipX, tipY - wing * 1.15f)
                lineTo(tipX + wing * 0.85f, tipY + wing * 0.25f)
                lineTo(tipX - wing * 0.85f, tipY + wing * 0.25f)
                close()
            },
            color = color,
        )
    }
}

// разрыв справа: дуга идёт от «трёх часов» почти полный круг
private const val ARC_START = 10f
private const val ARC_SWEEP = 300f
