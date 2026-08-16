package n7.bondcast.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/** Шажочки: два следа наискосок — тот же глиф, что запекается в кадр. */
@Composable
public fun StepsIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(22.dp)) {
        val length = size.minDimension * 0.62f
        val center = Offset(size.width / 2f, size.height / 2f)
        print(center + Offset(-length * 0.20f, length * 0.12f), length, -PRINT_TILT, color)
        print(center + Offset(length * 0.20f, -length * 0.12f), length, PRINT_TILT, color)
    }
}

/** Один след: подушка стопы плюс пятка. */
private fun DrawScope.print(center: Offset, length: Float, tilt: Float, color: Color) {
    rotate(degrees = tilt, pivot = center) {
        drawOval(
            color = color,
            topLeft = Offset(center.x - length * 0.20f, center.y - length * 0.50f),
            size = Size(length * 0.40f, length * 0.64f),
        )
        drawOval(
            color = color,
            topLeft = Offset(center.x - length * 0.14f, center.y + length * 0.24f),
            size = Size(length * 0.28f, length * 0.26f),
        )
    }
}

private const val PRINT_TILT = 14f
