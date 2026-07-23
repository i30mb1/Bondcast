package n7.bondcast.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private val StripeColorA = Color(0xFF1E1E1E)
private val StripeColorB = Color(0xFF191919)

/**
 * Тёмный фон с жёсткими диагональными полосами — заглушка вместо плоского чёрного экрана,
 * когда превью выключено (камера не пишется, но стрим/эфир живёт). Эквивалент CSS
 * `repeating-linear-gradient(135deg, #1e1e1e 0, #1e1e1e 14px, #191919 14px, #191919 28px)`:
 * жёсткая (не растушёванная) граница полос, угол — по CSS-конвенции (0° = вверх, по часовой).
 * Реализован как бесконечный Brush.linearGradient с TileMode.Repeated, растровых ресурсов не требует.
 */
public fun Modifier.diagonalStripesBackground(
    colorA: Color = StripeColorA,
    colorB: Color = StripeColorB,
    stripeWidth: Dp = 14.dp,
    angleDegrees: Float = 135f,
): Modifier = this.drawWithCache {
    val bandPx = stripeWidth.toPx()
    val periodPx = bandPx * 2f
    val radians = Math.toRadians(angleDegrees.toDouble())
    // CSS-конвенция угла: 0° — вверх, растёт по часовой стрелке
    val direction = Offset(sin(radians).toFloat(), -cos(radians).toFloat())
    val brush = Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to colorA,
            0.5f to colorA,
            0.5f to colorB,
            1.0f to colorB,
        ),
        start = Offset.Zero,
        end = direction * periodPx,
        tileMode = TileMode.Repeated,
    )
    onDrawBehind { drawRect(brush) }
}
