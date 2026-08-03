package n7.bondcast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.feature.camera.R
import n7.bondcast.stream.CameraOption
import n7.bondcast.ui.street.StreetChip
import n7.bondcast.ui.street.StreetPanelScaffold
import n7.bondcast.ui.street.StreetSectionLabel
import n7.bondcast.ui.street.streetBody
import n7.bondcast.ui.street.streetLabel

@Composable
public fun CameraPanel(
    cameras: List<CameraOption>,
    current: CameraOption?,
    onSelect: (CameraOption) -> Unit,
    previewEnabled: Boolean,
    onPreviewEnabled: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // управление камерой (стаб/AE-AWB/LLB) доступно только для CameraX-источника, не для USB
    cameraControlsAvailable: Boolean = false,
    stabilizationSupported: Boolean = false,
    stabilizationEnabled: Boolean = false,
    stabilizationActive: Boolean = false,
    onStabilizationEnabled: (Boolean) -> Unit = {},
    noiseReductionSupported: Boolean = false,
    noiseReductionEnabled: Boolean = false,
    onNoiseReductionEnabled: (Boolean) -> Unit = {},
    aeAwbLocked: Boolean = false,
    onAeAwbLocked: (Boolean) -> Unit = {},
    exposureSupported: Boolean = false,
    exposureIndex: Int = 0,
    exposureRange: IntRange = 0..0,
    exposureStepEv: Float = 0f,
    onExposureIndexChange: (Int) -> Unit = {},
    llbAvailable: Boolean = false,
    llbEnabled: Boolean = false,
    onLlbEnabled: (Boolean) -> Unit = {},
    nightModeSuggested: Boolean = false,
) {
    val onLabel = stringResource(R.string.camera_panel_on_button)
    val offLabel = stringResource(R.string.camera_panel_off_button)

    StreetPanelScaffold(title = stringResource(R.string.camera_panel_title), onClose = onClose, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cameras.forEach { cam ->
                StreetChip(cam.label, cam == current, Modifier.fillMaxWidth()) { onSelect(cam) }
            }
        }

        PanelLabel(stringResource(R.string.camera_panel_preview_label), info = stringResource(R.string.camera_panel_preview_info))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetChip(onLabel, previewEnabled, Modifier.weight(1f)) { onPreviewEnabled(true) }
            StreetChip(offLabel, !previewEnabled, Modifier.weight(1f)) { onPreviewEnabled(false) }
        }

        if (cameraControlsAvailable && stabilizationSupported) {
            PanelLabel(
                stringResource(R.string.camera_panel_stabilization_label),
                info = stringResource(R.string.camera_panel_stabilization_info),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreetChip(onLabel, stabilizationEnabled, Modifier.weight(1f)) { onStabilizationEnabled(true) }
                StreetChip(offLabel, !stabilizationEnabled, Modifier.weight(1f)) { onStabilizationEnabled(false) }
            }
            if (stabilizationEnabled && !stabilizationActive) {
                PanelHint(stringResource(R.string.camera_panel_stabilization_unsupported_hint))
            }
        }

        if (cameraControlsAvailable && noiseReductionSupported) {
            PanelLabel(
                stringResource(R.string.camera_panel_noise_reduction_label),
                info = stringResource(R.string.camera_panel_noise_reduction_info),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreetChip(onLabel, noiseReductionEnabled, Modifier.weight(1f)) { onNoiseReductionEnabled(true) }
                StreetChip(offLabel, !noiseReductionEnabled, Modifier.weight(1f)) { onNoiseReductionEnabled(false) }
            }
        }

        if (cameraControlsAvailable) {
            PanelLabel(
                stringResource(R.string.camera_panel_ae_awb_lock_label),
                info = stringResource(R.string.camera_panel_ae_awb_lock_info),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreetChip(onLabel, aeAwbLocked, Modifier.weight(1f)) { onAeAwbLocked(true) }
                StreetChip(offLabel, !aeAwbLocked, Modifier.weight(1f)) { onAeAwbLocked(false) }
            }

            if (exposureSupported) {
                PanelLabel(stringResource(R.string.camera_panel_exposure_label))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StreetChip(
                        stringResource(R.string.camera_panel_exposure_decrease_button),
                        false,
                        Modifier.weight(1f),
                        enabled = exposureIndex > exposureRange.first,
                    ) {
                        onExposureIndexChange((exposureIndex - 1).coerceIn(exposureRange))
                    }
                    Text(
                        text = formatEv(exposureIndex * exposureStepEv),
                        color = DiscordColors.textSecondary,
                        style = streetLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    StreetChip(
                        stringResource(R.string.camera_panel_exposure_increase_button),
                        false,
                        Modifier.weight(1f),
                        enabled = exposureIndex < exposureRange.last,
                    ) {
                        onExposureIndexChange((exposureIndex + 1).coerceIn(exposureRange))
                    }
                }
            }

            if (llbAvailable) {
                PanelLabel(stringResource(R.string.camera_panel_night_mode_label))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreetChip(onLabel, llbEnabled, Modifier.weight(1f)) { onLlbEnabled(true) }
                    StreetChip(offLabel, !llbEnabled, Modifier.weight(1f)) { onLlbEnabled(false) }
                }
                if (nightModeSuggested) {
                    Text(
                        text = stringResource(R.string.camera_panel_night_mode_suggestion_hint),
                        color = DiscordColors.yellow,
                        style = streetBody,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PanelLabel(text: String, info: String? = null) {
    StreetSectionLabel(text = text, info = info)
}

@Composable
internal fun PanelHint(text: String) {
    Text(
        text = text,
        color = DiscordColors.textMuted,
        style = streetBody,
    )
}

@Composable
private fun formatEv(value: Float): String {
    if (value == 0f) return stringResource(R.string.camera_panel_exposure_zero_ev)
    val formatted = (if (value > 0) "+" else "") + "%.1f".format(java.util.Locale.US, value)
    return stringResource(R.string.camera_panel_exposure_value_ev, formatted)
}
