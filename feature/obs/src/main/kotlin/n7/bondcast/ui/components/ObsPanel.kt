package n7.bondcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.obs.ObsPhase
import n7.bondcast.obs.ObsRecordStatus
import n7.bondcast.obs.ObsStats
import n7.bondcast.obs.ObsStreamStatus
import kotlin.math.roundToInt

/** Пульт OBS: сцены, эфир/запись и мини-статистика. Stateless — состояние приезжает сверху. */
@Composable
public fun ObsPanel(
    phase: ObsPhase,
    scenes: List<String>,
    currentScene: String?,
    streamStatus: ObsStreamStatus?,
    recordStatus: ObsRecordStatus?,
    stats: ObsStats?,
    onSelectScene: (String) -> Unit,
    onToggleStream: () -> Unit,
    onToggleRecord: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showHints: Boolean = true,
) {
    val connected = phase is ObsPhase.Connected
    Column(
        modifier = modifier
            .width(288.dp)
            .heightIn(max = 330.dp)
            .background(DiscordColors.background.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(obsPhaseColor(phase))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "OBS: ${phaseLabel(phase)}",
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
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        when (phase) {
            ObsPhase.AuthFailed -> Text(
                text = "OBS не принял пароль. Проверь его в настройках ⚙ " +
                    "и переоткрой панель.",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            is ObsPhase.Retrying -> Text(
                text = "Не достучались (#${phase.attempt})${phase.cause?.let { ": $it" } ?: ""}. " +
                    "OBS запущен? WebSocket включён? Фаервол пускает?",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Unit
        }

        if (scenes.isNotEmpty()) {
            Text(
                text = "Сцены",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                scenes.forEach { scene ->
                    SceneRow(
                        name = scene,
                        selected = scene == currentScene,
                        enabled = connected,
                        onClick = { onSelectScene(scene) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(
                text = if (streamStatus?.active == true) "⏹ Эфир OBS" else "▶ Эфир OBS",
                active = streamStatus?.active == true,
                enabled = connected,
                onClick = onToggleStream,
            )
            ToggleChip(
                text = if (recordStatus?.active == true) "⏹ Запись" else "⏺ Запись",
                active = recordStatus?.active == true,
                enabled = connected,
                onClick = onToggleRecord,
            )
        }

        if (connected) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                stats?.let {
                    InfoLine("CPU ${"%.1f".format(it.cpuUsage)}% · ${it.activeFps.roundToInt()} fps")
                    InfoLine(
                        "Пропуск кадров: рендер ${it.renderSkippedFrames}/${it.renderTotalFrames} · " +
                            "вывод ${it.outputSkippedFrames}/${it.outputTotalFrames}",
                    )
                }
                streamStatus?.takeIf { it.active }?.let {
                    InfoLine("Эфир ${it.timecode.substringBefore('.')}" + (it.kbps?.let { k -> " · ↑ $k kbps" } ?: ""))
                }
                recordStatus?.takeIf { it.active }?.let {
                    InfoLine("Запись ${it.timecode.substringBefore('.')}")
                }
            }
        }

        if (showHints && connected) {
            Text(
                text = "Пульт от OBS на компе: тапни сцену — и она в программе. " +
                    "Кнопки командуют эфиром и записью самого OBS 🎬",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun phaseLabel(phase: ObsPhase): String = when (phase) {
    ObsPhase.Disconnected -> "отключено"
    ObsPhase.Connecting -> "подключение…"
    ObsPhase.Connected -> "на связи"
    ObsPhase.AuthFailed -> "неверный пароль"
    is ObsPhase.Retrying -> "реконнект #${phase.attempt}"
}

@Composable
private fun SceneRow(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = name,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            !enabled -> DiscordColors.textMuted
            selected -> Color.White
            else -> DiscordColors.textPrimary
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DiscordColors.blurple else DiscordColors.elevated)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun RowScope.ToggleChip(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            !enabled -> DiscordColors.textMuted
            active -> Color.White
            else -> DiscordColors.textSecondary
        },
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    active && enabled -> DiscordColors.danger
                    else -> DiscordColors.elevated
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        color = DiscordColors.textSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
}
