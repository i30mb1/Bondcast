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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors

@Composable
internal fun CameraIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = DiscordColors.textPrimary,
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
            val stroke = Stroke(width = w * 0.09f)
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.30f, h * 0.18f),
                size = Size(w * 0.24f, h * 0.16f),
                cornerRadius = CornerRadius(w * 0.04f, w * 0.04f),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.06f, h * 0.30f),
                size = Size(w * 0.88f, h * 0.52f),
                cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
                style = stroke,
            )
            drawCircle(
                color = color,
                radius = w * 0.16f,
                center = Offset(w * 0.5f, h * 0.57f),
                style = stroke,
            )
        }
    }
}
