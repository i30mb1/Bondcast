package n7.bondcast.ui.street

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

public val BackIconSize: Dp = 22.dp

/** Стрелка «назад»: горизонтальная линия с шевроном слева, в стиле street-иконок. */
@Composable
public fun BackIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(w * 0.82f, h * 0.5f), Offset(w * 0.22f, h * 0.5f), stroke.width, stroke.cap)
        drawLine(color, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.46f, h * 0.28f), stroke.width, stroke.cap)
        drawLine(color, Offset(w * 0.22f, h * 0.5f), Offset(w * 0.46f, h * 0.72f), stroke.width, stroke.cap)
    }
}
