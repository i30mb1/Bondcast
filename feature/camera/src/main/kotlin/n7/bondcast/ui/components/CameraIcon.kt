package n7.bondcast.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors

@Composable
public fun CameraIcon(
    color: Color,
    modifier: Modifier = Modifier,
    // например, «сцена тёмная — включить ночной режим?»
    showBadge: Boolean = false,
    // меняется при переключении камеры — иконка делает переворот
    flipKey: Any? = null,
) {
    val flip = remember { Animatable(0f) }
    LaunchedEffect(flipKey) {
        flip.snapTo(0f)
        flip.animateTo(1f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = flip.value * 360f
                    cameraDistance = 16f * density
                },
        ) {
            val w = size.width
            val h = size.height
            val sw = w * 0.11f
            val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.22f, h * 0.14f),
                size = Size(w * 0.30f, h * 0.20f),
                cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.05f, h * 0.30f),
                size = Size(w * 0.90f, h * 0.56f),
                cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
                style = stroke,
            )
            drawCircle(
                color = color,
                radius = w * 0.19f,
                center = Offset(w * 0.5f, h * 0.59f),
                style = stroke,
            )
            drawCircle(
                color = color,
                radius = w * 0.06f,
                center = Offset(w * 0.5f, h * 0.59f),
            )
            drawCircle(
                color = color,
                radius = w * 0.045f,
                center = Offset(w * 0.80f, h * 0.42f),
            )
        }
        if (showBadge) {
            val transition = rememberInfiniteTransition(label = "camBadge")
            val pulse by transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 720),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulse",
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .graphicsLayer {
                        alpha = pulse
                        scaleX = pulse
                        scaleY = pulse
                    }
                    .clip(CircleShape)
                    .background(DiscordColors.yellow),
            )
        }
    }
}
