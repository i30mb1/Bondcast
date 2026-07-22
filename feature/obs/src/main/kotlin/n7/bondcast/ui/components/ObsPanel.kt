package n7.bondcast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.obs.ObsPhase
import n7.bondcast.obs.ObsRecordStatus
import n7.bondcast.obs.ObsStats
import n7.bondcast.obs.ObsStreamStatus
import n7.bondcast.ui.street.StreetChip
import n7.bondcast.ui.street.StreetPanelScaffold
import n7.bondcast.ui.street.streetBody
import n7.bondcast.ui.street.streetLabel
import n7.bondcast.ui.street.upper
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
) {
    val connected = phase is ObsPhase.Connected
    val leading: @Composable () -> Unit = { StatusDot(obsPhaseColor(phase)) }
    StreetPanelScaffold(
        title = "OBS · ${phaseLabel(phase)}",
        onClose = onClose,
        modifier = modifier,
        leading = leading,
        info = "Пульт от OBS на компе — прямо из телефона 🎬\n\n" +
            "Что умеет:\n" +
            "• тапнуть сцену — она уйдёт в программу\n" +
            "• запустить/остановить эфир OBS\n" +
            "• включить/выключить запись\n\n" +
            "Не подключается? Проверь: OBS запущен, WebSocket включён, фаервол пускает.",
    ) {
        when (phase) {
            ObsPhase.AuthFailed -> Text(
                text = "OBS не принял пароль. Проверь его в настройках ⚙ и переоткрой панель.",
                color = DiscordColors.textMuted,
                style = streetBody,
            )

            is ObsPhase.Retrying -> Text(
                text = "Не достучались (#${phase.attempt})${phase.cause?.let { ": $it" } ?: ""}. " +
                    "OBS запущен? WebSocket включён? Фаервол пускает?",
                color = DiscordColors.textMuted,
                style = streetBody,
            )

            else -> Unit
        }

        if (scenes.isNotEmpty()) {
            Text(text = "Сцены".upper(), color = DiscordColors.accent, style = streetLabel)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                scenes.forEach { scene ->
                    StreetChip(scene, scene == currentScene, Modifier.fillMaxWidth(), enabled = connected) {
                        onSelectScene(scene)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetChip(
                text = if (streamStatus?.active == true) "⏹ Эфир OBS" else "▶ Эфир OBS",
                selected = streamStatus?.active == true,
                modifier = Modifier.weight(1f),
                enabled = connected,
                onClick = onToggleStream,
            )
            StreetChip(
                text = if (recordStatus?.active == true) "⏹ Запись" else "⏺ Запись",
                selected = recordStatus?.active == true,
                modifier = Modifier.weight(1f),
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
private fun InfoLine(text: String) {
    Text(
        text = text,
        color = DiscordColors.textSecondary,
        style = streetBody,
    )
}
