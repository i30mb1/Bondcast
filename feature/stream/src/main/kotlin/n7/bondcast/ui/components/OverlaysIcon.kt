package n7.bondcast.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Слои: три ромба стопкой — окно оверлеев отвечает не только за шаги, поэтому глиф общий.
 * Верхний слой залит, нижние — контуром: так видно, что это стопка, а не один значок.
 */
@Composable
internal fun OverlaysIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(22.dp)) {
        val half = size.minDimension * 0.34f
        val squash = 0.52f
        val step = size.minDimension * 0.21f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawLayer(center + Offset(0f, step), half, squash, color, filled = false)
        drawLayer(center, half, squash, color, filled = false)
        drawLayer(center + Offset(0f, -step), half, squash, color, filled = true)
    }
}

/** Один слой: ромб, сплюснутый по вертикали, — вид на прямоугольник в перспективе. */
private fun DrawScope.drawLayer(
    center: Offset,
    half: Float,
    squash: Float,
    color: Color,
    filled: Boolean,
) {
    val path = Path().apply {
        moveTo(center.x, center.y - half * squash)
        lineTo(center.x + half, center.y)
        lineTo(center.x, center.y + half * squash)
        lineTo(center.x - half, center.y)
        close()
    }
    if (filled) {
        drawPath(path, color)
    } else {
        drawPath(path, color, style = Stroke(width = size.minDimension * 0.09f))
    }
}
