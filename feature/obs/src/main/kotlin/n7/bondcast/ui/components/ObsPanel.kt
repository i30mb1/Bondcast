package n7.bondcast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.feature.obs.R
import n7.bondcast.obs.ObsPhase
import n7.bondcast.obs.ObsRecordStatus
import n7.bondcast.obs.ObsStats
import n7.bondcast.obs.ObsStreamStatus
import n7.bondcast.ui.street.StreetChip
import n7.bondcast.ui.street.StreetPanelScaffold
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
        title = stringResource(R.string.obs_panel_title, phaseLabel(phase)),
        onClose = onClose,
        modifier = modifier,
        leading = leading,
        info = stringResource(R.string.obs_panel_info),
    ) {
        when (phase) {
            ObsPhase.AuthFailed -> Text(
                text = stringResource(R.string.obs_auth_failed_error),
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )

            is ObsPhase.Retrying -> Text(
                text = stringResource(
                    R.string.obs_retrying_error,
                    phase.attempt,
                    phase.cause?.let { ": $it" } ?: "",
                ),
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )

            else -> Unit
        }

        if (scenes.isNotEmpty()) {
            Text(text = stringResource(R.string.obs_scenes_label).upper(), color = DiscordColors.accent, style = streetLabel)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                scenes.forEach { scene ->
                    StreetChip(scene, scene == currentScene, Modifier.fillMaxWidth(), enabled = connected) {
                        onSelectScene(scene)
                    }
                }
            }
        }

        val streamStopButton = stringResource(R.string.obs_stream_stop_button)
        val streamStartButton = stringResource(R.string.obs_stream_start_button)
        val recordStopButton = stringResource(R.string.obs_record_stop_button)
        val recordStartButton = stringResource(R.string.obs_record_start_button)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetChip(
                text = if (streamStatus?.active == true) streamStopButton else streamStartButton,
                selected = streamStatus?.active == true,
                modifier = Modifier.weight(1f),
                enabled = connected,
                onClick = onToggleStream,
            )
            StreetChip(
                text = if (recordStatus?.active == true) recordStopButton else recordStartButton,
                selected = recordStatus?.active == true,
                modifier = Modifier.weight(1f),
                enabled = connected,
                onClick = onToggleRecord,
            )
        }

        if (connected) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                stats?.let {
                    InfoLine(
                        stringResource(
                            R.string.obs_stats_cpu_fps_label,
                            "%.1f".format(it.cpuUsage),
                            it.activeFps.roundToInt(),
                        ),
                    )
                    InfoLine(
                        stringResource(
                            R.string.obs_stats_dropped_frames_label,
                            it.renderSkippedFrames,
                            it.renderTotalFrames,
                            it.outputSkippedFrames,
                            it.outputTotalFrames,
                        ),
                    )
                }
                streamStatus?.takeIf { it.active }?.let {
                    val bitrateSuffix = it.kbps?.let { k -> stringResource(R.string.obs_stats_stream_bitrate_suffix, k) } ?: ""
                    InfoLine(stringResource(R.string.obs_stats_stream_label, it.timecode.substringBefore('.'), bitrateSuffix))
                }
                recordStatus?.takeIf { it.active }?.let {
                    InfoLine(stringResource(R.string.obs_stats_record_label, it.timecode.substringBefore('.')))
                }
            }
        }
    }
}

@Composable
private fun phaseLabel(phase: ObsPhase): String = when (phase) {
    ObsPhase.Disconnected -> stringResource(R.string.obs_phase_disconnected_label)
    ObsPhase.Connecting -> stringResource(R.string.obs_phase_connecting_label)
    ObsPhase.Connected -> stringResource(R.string.obs_phase_connected_label)
    ObsPhase.AuthFailed -> stringResource(R.string.obs_phase_auth_failed_label)
    is ObsPhase.Retrying -> stringResource(R.string.obs_phase_retrying_label, phase.attempt)
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        color = DiscordColors.textSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
}
