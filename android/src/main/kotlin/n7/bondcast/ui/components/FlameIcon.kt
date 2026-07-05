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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors

@Composable
internal fun FlameIcon(
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
            val outline = Path().apply {
                moveTo(w * 0.5f, h * 1.0f)
                cubicTo(w * 0.10f, h * 0.80f, w * 0.26f, h * 0.42f, w * 0.44f, h * 0.16f)
                cubicTo(w * 0.50f, h * 0.32f, w * 0.44f, h * 0.10f, w * 0.54f, 0f)
                cubicTo(w * 0.74f, h * 0.22f, w * 0.92f, h * 0.56f, w * 0.5f, h * 1.0f)
                close()
            }
            drawPath(outline, color)
            val core = Path().apply {
                moveTo(w * 0.5f, h * 0.94f)
                cubicTo(w * 0.30f, h * 0.80f, w * 0.40f, h * 0.54f, w * 0.52f, h * 0.40f)
                cubicTo(w * 0.56f, h * 0.52f, w * 0.60f, h * 0.62f, w * 0.66f, h * 0.66f)
                cubicTo(w * 0.72f, h * 0.80f, w * 0.64f, h * 0.92f, w * 0.5f, h * 0.94f)
                close()
            }
            drawPath(core, lerp(color, Color.White, 0.45f))
        }
    }
}
