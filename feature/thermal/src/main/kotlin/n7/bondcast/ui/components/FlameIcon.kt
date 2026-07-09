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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// контур из app/src/main/res/drawable/fire.xml (viewport 255×255)
private const val OUTER_PATH = "M220.9,164.81C218.8,214.87 177.57,254.81 127,254.81C75.08,254.81 33,211.31 33,160.81C33,154.06 32.88,140.57 43,117.81C49.06,104.19 52.86,95.63 55,87.81C56.18,83.51 58.47,76.68 65,87.81C68.85,94.37 69,103.81 69,103.81C69,103.81 83.33,92.82 93,71.81C107.18,41.02 95.87,22.61 92,9.81C90.66,5.38 89.82,-2.57 99,0.81C108.35,4.26 133.08,21.57 146,39.81C164.45,65.85 171,90.81 171,90.81C171,90.81 176.91,83.48 179,75.81C181.37,67.15 181.4,58.57 189,67.81C196.23,76.6 206.96,93.11 213,108.81C223.97,137.32 220.9,164.81 220.9,164.81Z"

/** Пламя толстой линией; [flicker] 0..1 — как сильно и быстро колышется (по нагреву). */
@Composable
public fun FlameIcon(
    color: Color,
    flicker: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val path = remember { PathParser().parsePathString(OUTER_PATH).toPath() }
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
    Canvas(
        modifier = modifier
            .size(22.dp)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleY = 1f - amp * 0.5f + wave * amp
                scaleX = 1f + amp * 0.3f - wave * amp * 0.5f
                rotationZ = (wave - 0.5f) * flicker * 7f
            },
    ) {
        val s = size.minDimension / 255f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 24f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
