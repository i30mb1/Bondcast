package n7.bondcast.ui.street

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import n7.bondcast.DiscordColors
import kotlin.random.Random

public val StreetShape: RoundedCornerShape = RoundedCornerShape(4.dp)
public val PanelShape: RoundedCornerShape = RoundedCornerShape(8.dp)

@Composable
public fun StreetChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text.upper(),
        style = streetLabel,
        color = when {
            !enabled -> DiscordColors.textMuted
            selected -> Color.White
            else -> DiscordColors.textSecondary
        },
        textAlign = TextAlign.Center,
        modifier = modifier
            .pressBounce(interaction)
            .clip(StreetShape)
            .background(
                when {
                    selected && enabled -> DiscordColors.accent
                    selected -> DiscordColors.accent.copy(alpha = 0.4f)
                    else -> DiscordColors.plate
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 9.dp, horizontal = 10.dp),
    )
}

@Composable
public fun StreetStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    sub: String? = null,
    labelColor: Color = DiscordColors.accent,
    info: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    // если подсказке некуда встать снизу — она уедет вправо, и указывать должна не на «i»,
    // а на строку значения (оно и меняется) — для этого запоминаем обе точки в координатах окна
    var iconCenterY by remember { mutableFloatStateOf(0f) }
    var valueCenterY by remember { mutableFloatStateOf(0f) }
    Column(
        modifier = modifier
            .clip(StreetShape)
            .background(DiscordColors.plate)
            .then(
                if (info != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { expanded = !expanded },
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label.upper(), style = streetLabel, color = labelColor, modifier = Modifier.weight(1f))
            if (info != null) {
                Box(
                    modifier = Modifier.onGloballyPositioned {
                        iconCenterY = it.positionInRoot().y + it.size.height / 2f
                    },
                ) {
                    InfoIcon(color = labelColor, modifier = Modifier.size(InfoIconSize))
                    if (expanded) {
                        StreetTooltip(
                            text = info,
                            onDismissRequest = { expanded = false },
                            valueAnchorOffsetPx = valueCenterY - iconCenterY,
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = if (info != null) {
                Modifier.onGloballyPositioned {
                    valueCenterY = it.positionInRoot().y + it.size.height / 2f
                }
            } else {
                Modifier
            },
        ) {
            Text(text = value, style = streetValue, color = DiscordColors.textPrimary)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = streetUnit,
                    color = DiscordColors.textMuted,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        if (sub != null) {
            Text(text = sub, style = streetUnit, color = DiscordColors.textMuted)
        }
    }
}

/** Заголовок секции панели (акцентный текст) с опциональной подсказкой по тапу. */
@Composable
public fun StreetSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    info: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.then(
            if (info != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { expanded = !expanded },
                )
            } else {
                Modifier
            },
        ),
    ) {
        Text(text = text.upper(), color = DiscordColors.accent, style = streetLabel)
        if (info != null) {
            Spacer(Modifier.width(6.dp))
            Box {
                InfoIcon(color = DiscordColors.accent, modifier = Modifier.size(InfoIconSize))
                if (expanded) {
                    StreetTooltip(text = info, onDismissRequest = { expanded = false })
                }
            }
        }
    }
}

public val InfoIconSize: Dp = 14.dp

/** Значок «i»: кольцо + точка + ножка, в стиле остальных street-иконок. */
@Composable
public fun InfoIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val c = center
        val r = size.minDimension / 2f
        val sw = size.minDimension * 0.14f
        drawCircle(color = color, radius = r - sw / 2f, style = Stroke(width = sw))
        drawCircle(color = color, radius = size.minDimension * 0.09f, center = Offset(c.x, c.y - r * 0.42f))
        drawLine(
            color = color,
            start = Offset(c.x, c.y - r * 0.02f),
            end = Offset(c.x, c.y + r * 0.55f),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
    }
}

/** С какой стороны подсказки вырезан треугольник-указатель. */
private enum class TooltipArrowSide { TOP, START }

/** [arrowCenterPx] — позиция вершины треугольника вдоль стороны [side], в пикселях от начала координат. */
private class TooltipShape(
    private val cornerRadius: Dp,
    private val arrowWidth: Dp,
    private val arrowHeight: Dp,
    private val arrowCenterPx: Float,
    private val side: TooltipArrowSide,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { cornerRadius.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val aw = with(density) { arrowWidth.toPx() }
        val ah = with(density) { arrowHeight.toPx() }
        val w = size.width
        val h = size.height
        val path = when (side) {
            TooltipArrowSide.TOP -> {
                val cx = arrowCenterPx.coerceIn(r + aw / 2f, w - r - aw / 2f)
                Path().apply {
                    moveTo(cx - aw / 2f, ah)
                    lineTo(cx, 0f)
                    lineTo(cx + aw / 2f, ah)
                    lineTo(w - r, ah)
                    arcTo(Rect(w - 2 * r, ah, w, ah + 2 * r), -90f, 90f, false)
                    lineTo(w, h - r)
                    arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
                    lineTo(r, h)
                    arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
                    lineTo(0f, ah + r)
                    arcTo(Rect(0f, ah, 2 * r, ah + 2 * r), 180f, 90f, false)
                    close()
                }
            }

            TooltipArrowSide.START -> {
                val cy = arrowCenterPx.coerceIn(r + aw / 2f, h - r - aw / 2f)
                Path().apply {
                    moveTo(ah, cy + aw / 2f)
                    lineTo(0f, cy)
                    lineTo(ah, cy - aw / 2f)
                    lineTo(ah, r)
                    arcTo(Rect(ah, 0f, ah + 2 * r, 2 * r), 180f, 90f, false)
                    lineTo(w - r, 0f)
                    arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
                    lineTo(w, h - r)
                    arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
                    lineTo(ah + r, h)
                    arcTo(Rect(ah, h - 2 * r, ah + 2 * r, h), 90f, 90f, false)
                    lineTo(ah, cy + aw / 2f)
                    close()
                }
            }
        }
        return Outline.Generic(path)
    }
}

/**
 * Ставит поповер под якорем (значком «i»), центрируя треугольник по X. Если снизу не хватает
 * места до края окна — переезжает вправо от якоря, а треугольник рисует слева и целит по вертикали
 * не в сам значок, а в [valueAnchorOffsetPx] — обычно это строка с меняющимся числом.
 */
private class AdaptiveTooltipPositionProvider(
    private val gapPx: Int,
    private val valueAnchorOffsetPx: Float,
    private val onPlacement: (side: TooltipArrowSide, arrowOffsetPx: Float) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val fitsBelow = anchorBounds.bottom + gapPx + popupContentSize.height <= windowSize.height
        return if (fitsBelow) {
            val anchorCenterX = (anchorBounds.left + anchorBounds.right) / 2
            val idealX = anchorCenterX - popupContentSize.width / 2
            val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
            val x = idealX.coerceIn(0, maxX)
            onPlacement(TooltipArrowSide.TOP, (anchorCenterX - x).toFloat())
            IntOffset(x = x, y = anchorBounds.bottom + gapPx)
        } else {
            val targetY = anchorBounds.top + valueAnchorOffsetPx.toInt()
            val idealY = targetY - popupContentSize.height / 2
            val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
            val y = idealY.coerceIn(0, maxY)
            onPlacement(TooltipArrowSide.START, (targetY - y).toFloat())
            IntOffset(x = anchorBounds.right + gapPx, y = y)
        }
    }
}

/**
 * Всплывающая подсказка поверх контента, с треугольником-указателем на якорь.
 * [valueAnchorOffsetPx] — на сколько пикселей ниже якоря лежит «интересное» значение
 * (строка с меняющимся числом); используется только когда подсказка уезжает вправо.
 */
@Composable
public fun StreetTooltip(
    text: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    valueAnchorOffsetPx: Float = 0f,
) {
    var arrowOffsetPx by remember { mutableFloatStateOf(0f) }
    var side by remember { mutableStateOf(TooltipArrowSide.TOP) }
    val arrowHeight = 7.dp
    val shape = remember(arrowOffsetPx, side) {
        TooltipShape(
            cornerRadius = 0.dp,
            arrowWidth = 14.dp,
            arrowHeight = arrowHeight,
            arrowCenterPx = arrowOffsetPx,
            side = side,
        )
    }
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val positionProvider = remember(gapPx, valueAnchorOffsetPx) {
        AdaptiveTooltipPositionProvider(gapPx, valueAnchorOffsetPx) { newSide, offset ->
            side = newSide
            arrowOffsetPx = offset
        }
    }
    // свежий наклон при каждом открытии — стикер, а не выверенная плашка
    val tiltDegrees = remember { Random.nextFloat() * 16f - 8f }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = text,
            style = streetUnit,
            color = Color.White,
            modifier = modifier
                .widthIn(max = 220.dp)
                .graphicsLayer { rotationZ = tiltDegrees }
                .background(DiscordColors.background, shape)
                .border(2.dp, Color.White, shape)
                .padding(
                    top = if (side == TooltipArrowSide.TOP) arrowHeight + 8.dp else 8.dp,
                    start = if (side == TooltipArrowSide.START) arrowHeight + 10.dp else 10.dp,
                    end = 10.dp,
                    bottom = 8.dp,
                ),
        )
    }
}

@Composable
public fun StreetPanelScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    info: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val infoInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .width(300.dp)
            .fillMaxHeight()
            .shadow(20.dp, PanelShape, clip = false)
            .clip(PanelShape)
            .background(DiscordColors.panel)
            .border(2.dp, DiscordColors.accent, PanelShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title.upper(),
                color = DiscordColors.textPrimary,
                style = streetTitle,
                modifier = Modifier.weight(1f),
            )
            if (info != null) {
                Box(
                    modifier = Modifier
                        .clip(StreetShape)
                        .clickable(interactionSource = infoInteraction, indication = null) { expanded = !expanded }
                        .padding(6.dp),
                ) {
                    InfoIcon(color = DiscordColors.accent, modifier = Modifier.size(InfoIconSize))
                    if (expanded) {
                        StreetTooltip(text = info, onDismissRequest = { expanded = false })
                    }
                }
                Spacer(Modifier.width(6.dp))
            }
            StreetCloseButton(onClose)
        }
        Column(
            // weight ограничивает высоту, иначе verticalScroll не прокручивает, а обрезает
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun StreetCloseButton(onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressBounce(interaction)
            .size(28.dp)
            .clip(StreetShape)
            .border(2.dp, DiscordColors.accent, StreetShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "✕", color = DiscordColors.textPrimary, style = streetLabel)
    }
}
