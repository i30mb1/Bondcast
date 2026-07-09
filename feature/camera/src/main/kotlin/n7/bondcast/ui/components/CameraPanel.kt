package n7.bondcast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.stream.CameraOption
import n7.bondcast.ui.street.StreetChip
import n7.bondcast.ui.street.StreetPanelScaffold
import n7.bondcast.ui.street.streetLabel
import n7.bondcast.ui.street.upper

@Composable
public fun CameraPanel(
    cameras: List<CameraOption>,
    current: CameraOption?,
    onSelect: (CameraOption) -> Unit,
    previewEnabled: Boolean,
    onPreviewEnabled: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showHint: Boolean = true,
    // управление камерой (стаб/AE-AWB/LLB) доступно только для CameraX-источника, не для USB
    cameraControlsAvailable: Boolean = false,
    stabilizationSupported: Boolean = false,
    stabilizationEnabled: Boolean = false,
    stabilizationActive: Boolean = false,
    onStabilizationEnabled: (Boolean) -> Unit = {},
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
    StreetPanelScaffold(title = "Камера", onClose = onClose, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cameras.forEach { cam ->
                StreetChip(cam.label, cam == current, Modifier.fillMaxWidth()) { onSelect(cam) }
            }
        }

        PanelLabel("Превью на экране")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetChip("Вкл", previewEnabled, Modifier.weight(1f)) { onPreviewEnabled(true) }
            StreetChip("Выкл", !previewEnabled, Modifier.weight(1f)) { onPreviewEnabled(false) }
        }
        if (showHint) {
            PanelHint("Выкл — экран отдыхает, телефон холоднее. Зрители разницы не заметят 😉")
        }

        if (cameraControlsAvailable && stabilizationSupported) {
            PanelLabel("Стабилизация")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreetChip("Вкл", stabilizationEnabled, Modifier.weight(1f)) { onStabilizationEnabled(true) }
                StreetChip("Выкл", !stabilizationEnabled, Modifier.weight(1f)) { onStabilizationEnabled(false) }
            }
            if (stabilizationEnabled && !stabilizationActive) {
                PanelHint("Не влезла в текущий режим съёмки (разрешение/fps).")
            } else if (showHint && stabilizationEnabled) {
                PanelHint("Гасит тряску, но чуть подрезает края кадра.")
            }
        }

        if (cameraControlsAvailable) {
            PanelLabel("Заморозить экспозицию/ЦТ")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreetChip("Вкл", aeAwbLocked, Modifier.weight(1f)) { onAeAwbLocked(true) }
                StreetChip("Выкл", !aeAwbLocked, Modifier.weight(1f)) { onAeAwbLocked(false) }
            }
            if (showHint && aeAwbLocked) {
                PanelHint("Картинка не «дышит» при панораме — экспозиция и цвет зафиксированы.")
            }

            if (exposureSupported) {
                PanelLabel("Экспозиция")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StreetChip("−", false, Modifier.weight(1f), enabled = exposureIndex > exposureRange.first) {
                        onExposureIndexChange((exposureIndex - 1).coerceIn(exposureRange))
                    }
                    Text(
                        text = formatEv(exposureIndex * exposureStepEv),
                        color = DiscordColors.textSecondary,
                        style = streetLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    StreetChip("+", false, Modifier.weight(1f), enabled = exposureIndex < exposureRange.last) {
                        onExposureIndexChange((exposureIndex + 1).coerceIn(exposureRange))
                    }
                }
            }

            if (llbAvailable) {
                PanelLabel("Ночной режим")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreetChip("Вкл", llbEnabled, Modifier.weight(1f)) { onLlbEnabled(true) }
                    StreetChip("Выкл", !llbEnabled, Modifier.weight(1f)) { onLlbEnabled(false) }
                }
                if (nightModeSuggested) {
                    Text(
                        text = "Сцена тёмная — есть смысл включить.",
                        color = DiscordColors.yellow,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PanelLabel(text: String) {
    Text(text = text.upper(), color = DiscordColors.accent, style = streetLabel)
}

@Composable
internal fun PanelHint(text: String) {
    Text(
        text = text,
        color = DiscordColors.textMuted,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun formatEv(value: Float): String {
    if (value == 0f) return "0 EV"
    return (if (value > 0) "+" else "") + "%.1f EV".format(java.util.Locale.US, value)
}
