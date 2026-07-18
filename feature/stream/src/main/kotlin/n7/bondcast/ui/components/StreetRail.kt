package n7.bondcast.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import n7.bondcast.DiscordColors
import n7.bondcast.stream.StreamPhase
import n7.bondcast.ui.street.SpringAppear
import n7.bondcast.ui.street.StreetShape
import n7.bondcast.ui.street.pressBounce
import n7.bondcast.ui.street.streetButton
import n7.bondcast.ui.street.streetLabel
import n7.bondcast.ui.street.upper

internal enum class AttentionLevel { NONE, WARN, BAD }

@Composable
internal fun RailButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    attention: AttentionLevel = AttentionLevel.NONE,
    appearDelay: Long = 0L,
    glyph: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    SpringAppear(delayMillis = appearDelay) {
        Box(contentAlignment = Alignment.Center) {
            if (attention != AttentionLevel.NONE) {
                AttentionGlow(attention, Modifier.matchParentSize())
            }
            Box(
                modifier = modifier
                    .pressBounce(interaction)
                    .shadow(
                        elevation = if (active) 10.dp else 0.dp,
                        shape = StreetShape,
                        clip = false,
                        ambientColor = DiscordColors.accent,
                        spotColor = DiscordColors.accent,
                    )
                    .size(48.dp)
                    .clip(StreetShape)
                    .background(if (active) DiscordColors.accent else DiscordColors.plate)
                    .border(2.dp, DiscordColors.accent, StreetShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                glyph()
            }
        }
    }
}

/** Пульсирующее красное свечение вокруг кнопки: WARN — медленно, BAD — быстро. */
@Composable
private fun AttentionGlow(level: AttentionLevel, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "attention")
    val duration = if (level == AttentionLevel.BAD) 650 else 1300
    val maxAlpha = if (level == AttentionLevel.BAD) 0.9f else 0.5f
    val glow by transition.animateFloat(
        initialValue = 0.1f,
        targetValue = maxAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = duration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )
    Box(
        modifier
            .blur(11.dp, BlurredEdgeTreatment.Unbounded)
            .background(DiscordColors.accent.copy(alpha = glow), StreetShape),
    )
}

/** Значок настроек: шестерёнка из толстых радиальных лучей + кольцо. */
@Composable
internal fun GearIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val c = center
        val r = size.minDimension / 2f
        val sw = size.minDimension * 0.14f
        repeat(8) { i ->
            rotate(degrees = 45f * i, pivot = c) {
                drawLine(
                    color = color,
                    start = Offset(c.x, c.y - r * 0.98f),
                    end = Offset(c.x, c.y - r * 0.60f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
            }
        }
        drawCircle(color = color, radius = r * 0.40f, center = c, style = Stroke(width = sw))
    }
}

@Composable
internal fun RailGroupDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(DiscordColors.groupDivider),
    )
}

/** Рейл кнопок: прокручиваемый, без затемнения у краёв, с увеличенным шагом. */
@Composable
internal fun RailFadeColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .width(48.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

@Composable
internal fun StickerBadge(
    phase: StreamPhase,
    modifier: Modifier = Modifier,
) {
    val (dotColor, label) = when (phase) {
        is StreamPhase.Idle -> DiscordColors.textMuted to "Не в эфире"

        is StreamPhase.Connecting -> DiscordColors.yellow to "Подключение…"

        is StreamPhase.Live -> DiscordColors.accent to "В эфире"

        is StreamPhase.Retrying ->
            DiscordColors.danger to "Реконнект #${phase.attempt}"
    }
    SpringAppear(modifier = modifier) {
        Row(
            modifier = Modifier
                .graphicsLayer { rotationZ = -1.5f }
                .shadow(12.dp, StreetShape, clip = false)
                .clip(StreetShape)
                .background(DiscordColors.sticker)
                .padding(horizontal = 12.dp, vertical = 8.dp).padding(end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label.upper(),
                color = Color.Black,
                style = streetLabel.copy(fontSize = 12.sp),
            )
            if (phase is StreamPhase.Live) {
                Spacer(Modifier.width(10.dp))
                LiveTimer(phase.sinceEpochMs)
            }
        }
    }
}

@Composable
private fun LiveTimer(sinceEpochMs: Long) {
    var elapsedSeconds by remember(sinceEpochMs) { mutableLongStateOf(0L) }
    LaunchedEffect(sinceEpochMs) {
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - sinceEpochMs) / 1000
            delay(1_000)
        }
    }
    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60
    fun Long.two(): String = toString().padStart(2, '0')
    Text(
        text = "${hours.two()}:${minutes.two()}:${seconds.two()}",
        color = Color.Black,
        style = streetLabel.copy(fontSize = 12.sp),
    )
}

@Composable
internal fun GoLiveButton(
    streaming: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    SpringAppear(modifier = modifier) {
        Box(
            modifier = Modifier
                .pressBounce(interaction)
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(10.dp),
                    clip = false,
                    ambientColor = DiscordColors.accent,
                    spotColor = DiscordColors.accent,
                )
                .clip(RoundedCornerShape(10.dp))
                .background(DiscordColors.accent)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 44.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (if (streaming) "Завершить" else "В эфир").upper(),
                color = Color.White,
                style = streetButton,
            )
        }
    }
}
