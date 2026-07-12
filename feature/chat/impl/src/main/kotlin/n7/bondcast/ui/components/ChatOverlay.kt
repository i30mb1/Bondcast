package n7.bondcast.ui.components

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
 * трогает камеру. Новые сообщения — снизу (reverseLayout), список авто-следует.
 */
@Composable
public fun ChatOverlay(
    messages: List<ChatEvent.Message>,
    showNicknames: Boolean,
    showBadges: Boolean,
    fontSizeSp: Int,
    opacityPercent: Int,
    modifier: Modifier = Modifier,
) {
    val bgAlpha = (opacityPercent.coerceIn(0, 100) / 100f)
    val listState = rememberLazyListState()
    // на новое сообщение прокручиваем к нему (item 0 при reverseLayout — самое свежее, внизу)
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(items = messages.asReversed(), key = { it.id }) { message ->
            ChatMessageRow(message, showNicknames, showBadges, fontSizeSp, bgAlpha)
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
    Text(
        text = text,
        inlineContent = inlineContent,
        modifier = Modifier
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
