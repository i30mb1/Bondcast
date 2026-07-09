package n7.bondcast.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.stream.HealthLevel
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun healthColor(level: HealthLevel?): Color = when (level) {
    HealthLevel.OK -> DiscordColors.green
    HealthLevel.WARN -> DiscordColors.yellow
    HealthLevel.BAD -> DiscordColors.danger
    null -> DiscordColors.textMuted
}

@Composable
internal fun StatsIcon(
    color: Color,
    live: Boolean = false,
    agitation: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "stats")
    val period = (620 - agitation * 380).toInt().coerceAtLeast(180)
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = period, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bars",
    )
    val bases = listOf(0.45f, 0.75f, 1.0f)
    val phases = listOf(0f, 2.1f, 4.2f)
    val amp = 0.12f + agitation * 0.33f
    Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val barW = w * 0.2f
        val gap = (w - barW * 3) / 2f
        bases.forEachIndexed { i, base ->
            val osc = if (live) sin((phase + phases[i]).toDouble()).toFloat() * amp else 0f
            val frac = (base + osc).coerceIn(0.18f, 1f)
            val x = i * (barW + gap)
            val barH = h * frac
            drawRoundRect(
                color = color,
                topLeft = Offset(x, h - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW * 0.3f, barW * 0.3f),
            )
        }
    }
}
