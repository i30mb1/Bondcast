package n7.bondcast.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import n7.bondcast.DiscordColors
import n7.bondcast.chat.ChatEvent
import n7.bondcast.chat.ChatRole
import n7.bondcast.chat.MessageFragment

/**
 * Постоянный полупрозрачный оверлей чата слева. Рисуется поверх превью как обычный
 * child корневого Box (НЕ через PanelManager), поэтому не гасит другие панели и не
 * трогает камеру. Новые сообщения — снизу (reverseLayout + Arrangement.Bottom, чтобы
 * при малом числе сообщений они жались к низу, а не к верху).
 *
 * Прокрутка к новому сообщению — МГНОВЕННАЯ ([LazyListState.scrollToItem], не animate-версия):
 * иначе она бы анимировала ту же позицию, что и [animateItem] у уже видимых сообщений (см. ниже),
 * и оба гонялись бы за одной и той же движущейся целью — отсюда «рваньё» на скролле. Сам плавный
 * эффект даёт [animateItem] (существующие сообщения плавно едут вверх на мгновенно применённый
 * сдвиг) + ручной въезд снизу у [ChatMessageRow] (у только что вставленного сообщения `animateItem`
 * не имеет «предыдущей» позиции, поэтому подсказать ему выезд не может — едет только текст-контент).
 */
@Composable
public fun ChatOverlay(
    messages: List<ChatEvent.Message>,
    showNicknames: Boolean,
    showBadges: Boolean,
    fontSizeSp: Int,
    opacityPercent: Int,
    fadeTopEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgAlpha = (opacityPercent.coerceIn(0, 100) / 100f)
    val listState = rememberLazyListState()
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.scrollToItem(0)
    }
    var viewportTopPx by remember { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    LazyColumn(
        state = listState,
        modifier = modifier
            .onGloballyPositioned {
                viewportTopPx = it.positionInRoot().y
                viewportHeightPx = it.size.height
            },
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items = messages.asReversed(), key = { it.id }) { message ->
            ChatMessageRow(
                message,
                showNicknames,
                showBadges,
                fontSizeSp,
                bgAlpha,
                fadeTopEnabled,
                viewportTopPx,
                viewportHeightPx,
                // placementSpec — плавный «отъезд вверх» уже показанных сообщений (безопасно
                // сочетать с scrollToItem() выше именно потому, что тот мгновенный — см. KDoc файла)
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(durationMillis = ENTER_FADE_MS, easing = FastOutSlowInEasing),
                    placementSpec = tween(durationMillis = PLACEMENT_MS, easing = FastOutSlowInEasing),
                    fadeOutSpec = tween(durationMillis = EXIT_FADE_MS, easing = FastOutSlowInEasing),
                ),
            )
        }
    }
}

@Composable
private fun ChatMessageRow(
    message: ChatEvent.Message,
    showNicknames: Boolean,
    showBadges: Boolean,
    fontSizeSp: Int,
    bgAlpha: Float,
    fadeTopEnabled: Boolean,
    viewportTopPx: Float,
    viewportHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    val nameColor = message.author.color?.let { Color(it) } ?: DiscordColors.accent
    val inlineContent = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        if (showBadges) {
            var anyImage = false
            message.author.badges.forEach { badge ->
                val url = badge.imageUrl
                if (url != null) {
                    val key = "badge_${badge.setId}_${badge.version}"
                    inlineContent[key] = badgeInline(url, badge.setId)
                    appendInlineContent(key, badge.setId)
                    append(" ")
                    anyImage = true
                }
            }
            // текстовый фолбэк, если картинок значков нет (не загрузились/не залогинен)
            if (!anyImage) {
                message.author.primaryRole?.let { role ->
                    withStyle(SpanStyle(color = DiscordColors.accent, fontWeight = FontWeight.Bold)) {
                        append("[${roleTag(role)}] ")
                    }
                }
            }
        }
        if (showNicknames) {
            withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) {
                append(message.author.displayName)
            }
            append(": ")
        }
        message.fragments.forEach { fragment ->
            when (fragment) {
                is MessageFragment.Emote -> {
                    val url = fragment.imageUrl
                    if (url != null) {
                        inlineContent[fragment.id] = emoteInline(url, fragment.name)
                        appendInlineContent(fragment.id, fragment.name)
                    } else {
                        append(fragment.name)
                    }
                }

                is MessageFragment.Mention ->
                    withStyle(SpanStyle(color = DiscordColors.accent, fontWeight = FontWeight.Bold)) {
                        append("@${fragment.name}")
                    }

                is MessageFragment.Text -> append(fragment.rawText)
            }
        }
    }
    var fadeAlpha by remember(message.id) { mutableFloatStateOf(1f) }
    // animateItem() умеет плавно сдвигать УЖЕ показанное сообщение (есть «была/стала» позиция),
    // но у новой строки такой пары нет — она просто рисуется на своём месте. Поэтому въезд снизу
    // делаем сами: стартуем со смещением вниз на фиксированную высоту и едем к нулю; нижний край
    // LazyColumn обрежет лишнее — снаружи это выглядит как «выезжает из-под низа».
    val density = LocalDensity.current
    val slideOffsetPx = remember(message.id) { Animatable(with(density) { ENTER_SLIDE_DP.dp.toPx() }) }
    LaunchedEffect(message.id) {
        slideOffsetPx.animateTo(0f, animationSpec = tween(durationMillis = ENTER_SLIDE_MS, easing = FastOutSlowInEasing))
    }
    Text(
        text = text,
        inlineContent = inlineContent,
        modifier = modifier
            .then(
                if (fadeTopEnabled) {
                    Modifier.onGloballyPositioned { coords ->
                        val centerY = coords.positionInRoot().y - viewportTopPx + coords.size.height / 2f
                        val halfHeight = viewportHeightPx / 2f
                        fadeAlpha = if (halfHeight <= 0f || centerY >= halfHeight) {
                            1f
                        } else {
                            (centerY / halfHeight).coerceIn(FADE_MIN_ALPHA, 1f)
                        }
                    }
                } else {
                    Modifier
                },
            )
            // альфа целиком на composited-результат — иначе картинки эмоутов/значков не гаснут вместе с фоном
            .graphicsLayer {
                alpha = if (fadeTopEnabled) fadeAlpha else 1f
                translationY = slideOffsetPx.value
            }
            .clip(RoundedCornerShape(6.dp))
            .background(DiscordColors.background.copy(alpha = bgAlpha))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = DiscordColors.textPrimary,
        style = TextStyle(
            fontSize = fontSizeSp.sp,
            // тень ради читаемости поверх яркого видео
            shadow = Shadow(color = Color.Black, offset = Offset(0f, 1f), blurRadius = 3f),
        ),
    )
}

// ниже неё сообщения не гаснут совсем — остаются читаемыми у самого верха оверлея
private const val FADE_MIN_ALPHA = 0.15f

private const val ENTER_FADE_MS = 300
private const val EXIT_FADE_MS = 250

// «отъезд вверх» уже показанных сообщений, когда новое отжимает их с места
private const val PLACEMENT_MS = 450

// въезд снизу у самого нового сообщения: с какой высоты стартует и сколько это едет
private const val ENTER_SLIDE_DP = 28
private const val ENTER_SLIDE_MS = 350

// эмоут как inline-картинка размером ~1.5 строки, тянется Coil'ом с CDN Twitch
private fun emoteInline(url: String, name: String): InlineTextContent = InlineTextContent(
    Placeholder(width = 1.6.em, height = 1.6.em, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
) {
    AsyncImage(model = url, contentDescription = name, modifier = Modifier.fillMaxSize())
}

// значок перед ником — маленькая квадратная картинка
private fun badgeInline(url: String, alt: String): InlineTextContent = InlineTextContent(
    Placeholder(width = 1.3.em, height = 1.3.em, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
) {
    AsyncImage(model = url, contentDescription = alt, modifier = Modifier.fillMaxSize())
}

private fun roleTag(role: ChatRole): String = when (role) {
    ChatRole.OWNER -> "BC"
    ChatRole.MODERATOR -> "MOD"
    ChatRole.STAFF -> "STAFF"
    ChatRole.FOUNDER -> "FND"
    ChatRole.VIP -> "VIP"
    ChatRole.OG -> "OG"
    ChatRole.SUBSCRIBER -> "SUB"
    ChatRole.VERIFIED -> "✓"
}
