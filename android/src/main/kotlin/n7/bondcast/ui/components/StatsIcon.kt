package n7.bondcast.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.stream.HealthLevel

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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(DiscordColors.background.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val barW = w * 0.2f
            val gap = (w - barW * 3) / 2f
            val heights = listOf(0.45f, 0.75f, 1.0f)
            heights.forEachIndexed { i, frac ->
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
}
