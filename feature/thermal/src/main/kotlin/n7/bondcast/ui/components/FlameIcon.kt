package n7.bondcast.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import n7.bondcast.feature.thermal.R

/** Пламя тонкой линией; [flicker] 0..1 — как сильно и быстро колышется (по нагреву). */
@Composable
public fun FlameIcon(
    color: Color,
    flicker: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "flame")
    val period = (760 - flicker * 470).toInt().coerceAtLeast(240)
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = period, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wave",
    )
    val amp = 0.05f + flicker * 0.18f
    Icon(
        painter = painterResource(R.drawable.flame),
        contentDescription = null,
        tint = color,
        modifier = modifier
            .size(22.dp)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleY = 1f - amp * 0.5f + wave * amp
                scaleX = 1f + amp * 0.3f - wave * amp * 0.5f
                rotationZ = (wave - 0.5f) * flicker * 7f
            },
    )
}
