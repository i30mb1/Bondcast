package n7.bondcast.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Речевой пузырёк — глиф кнопки чата в рейле (рисуется вручную, как GearIcon). */
@Composable
public fun ChatIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val bubbleBottom = h * 0.72f
        val corner = w * 0.22f
        val stroke = Stroke(width = w * 0.09f)

        // тело пузыря
        val body = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = w * 0.10f,
                    top = h * 0.14f,
                    right = w * 0.90f,
                    bottom = bubbleBottom,
                    radiusX = corner,
                    radiusY = corner,
                ),
            )
        }
        drawPath(body, color, style = stroke)

        // хвостик
        val tail = Path().apply {
            moveTo(w * 0.30f, bubbleBottom - stroke.width * 0.4f)
            lineTo(w * 0.24f, h * 0.90f)
            lineTo(w * 0.46f, bubbleBottom - stroke.width * 0.4f)
        }
        drawPath(tail, color, style = stroke)
    }
}
