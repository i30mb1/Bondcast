package n7.bondcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors

/**
 * Регион экрана под всплывающее окно. Окон на экране максимум два —
 * по одному на регион; третьему места нет, оно выселяет самое старое.
 * Трём окнам на ландшафте телефона тесно, между ними не остаётся воздуха.
 */
internal enum class OverlayRegion { START, END }

private data class OpenOverlay(val id: String, val region: OverlayRegion, val openedAt: Long)

/**
 * Оконный менеджер стрим-экрана.
 *
 * Правила: слоты раздаются по порядку открытия — первое окно занимает START,
 * второе END. Если оба региона заняты — закрывается самое старое окно и его
 * место достаётся новому. Повторный toggle закрывает окно, тап мимо — все сразу.
 *
 * Новое окошко в будущем = toggle(id) на кнопке + regionOf(id) в рендере.
 */
internal class OverlayManager {
    private val open = mutableStateListOf<OpenOverlay>()
    private var counter = 0L

    val anyOpen: Boolean get() = open.isNotEmpty()

    fun regionOf(id: String): OverlayRegion? = open.firstOrNull { it.id == id }?.region

    fun isOpen(id: String): Boolean = regionOf(id) != null

    fun toggle(id: String) {
        if (isOpen(id)) {
            close(id)
            return
        }
        val busy = open.map { it.region }.toSet()
        val region = OverlayRegion.entries.firstOrNull { it !in busy } ?: evictOldest()
        open.add(OpenOverlay(id, region, counter++))
    }

    fun close(id: String) {
        open.removeAll { it.id == id }
    }

    fun closeAll() {
        open.clear()
    }

    private fun evictOldest(): OverlayRegion {
        val oldest = open.minBy { it.openedAt }
        open.remove(oldest)
        return oldest.region
    }
}

/** Ставит содержимое окна в свой регион по вертикальному центру экрана. */
@Composable
internal fun BoxScope.OverlaySlot(region: OverlayRegion, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .align(
                when (region) {
                    OverlayRegion.START -> Alignment.CenterStart
                    OverlayRegion.END -> Alignment.CenterEnd
                },
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp)
            // регион END не должен накрывать правую рельсу иконок
            .padding(end = if (region == OverlayRegion.END) 64.dp else 0.dp),
    ) {
        content()
    }
}

/** Немодальная справочная карточка — живёт в регионе, а не поверх всего экрана. */
@Composable
internal fun InfoPanel(
    title: String,
    text: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 340.dp)
            .heightIn(max = 360.dp)
            .background(DiscordColors.card, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = DiscordColors.textPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "✕",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.width(0.dp))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = text,
                color = DiscordColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
