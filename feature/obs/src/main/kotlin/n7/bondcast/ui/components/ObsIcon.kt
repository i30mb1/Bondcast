package n7.bondcast.ui.components

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import n7.bondcast.DiscordColors
import n7.bondcast.obs.ObsPhase

/** Цвет статуса пульта OBS: красит точку в панели. */
public fun obsPhaseColor(phase: ObsPhase): Color = when (phase) {
    ObsPhase.Connected -> DiscordColors.green
    ObsPhase.Connecting, is ObsPhase.Retrying -> DiscordColors.yellow
    ObsPhase.AuthFailed -> DiscordColors.danger
    ObsPhase.Disconnected -> DiscordColors.textMuted
}

private const val OBS_TEXT = "OBS"

/** Значок пульта OBS: центрируем по чернильным границам (Paint.getTextBounds), не по ascent/descent шрифта. */
@Composable
public fun ObsIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val paint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = with(density) { 13.sp.toPx() }
        }
    }
    val bounds = remember(paint) { Rect().also { paint.getTextBounds(OBS_TEXT, 0, OBS_TEXT.length, it) } }
    val widthDp = with(density) { bounds.width().toDp() }
    Canvas(modifier = modifier.size(width = widthDp, height = 22.dp)) {
        drawIntoCanvas { canvas ->
            paint.color = color.toArgb()
            canvas.nativeCanvas.drawText(
                OBS_TEXT,
                size.width / 2f - bounds.exactCenterX(),
                size.height / 2f - bounds.exactCenterY(),
                paint,
            )
        }
    }
}
