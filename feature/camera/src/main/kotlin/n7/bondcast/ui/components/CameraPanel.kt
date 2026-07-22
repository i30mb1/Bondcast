package n7.bondcast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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

        PanelLabel("Превью на экране", info = INFO_PREVIEW)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetChip("Вкл", previewEnabled, Modifier.weight(1f)) { onPreviewEnabled(true) }
            StreetChip("Выкл", !previewEnabled, Modifier.weight(1f)) { onPreviewEnabled(false) }
        }

        if (cameraControlsAvailable && stabilizationSupported) {
            PanelLabel("Стабилизация", info = INFO_STABILIZATION)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreetChip("Вкл", stabilizationEnabled, Modifier.weight(1f)) { onStabilizationEnabled(true) }
                StreetChip("Выкл", !stabilizationEnabled, Modifier.weight(1f)) { onStabilizationEnabled(false) }
            }
            if (stabilizationEnabled && !stabilizationActive) {
                PanelHint("Не влезла в текущий режим съёмки (разрешение/fps).")
            }
        }

        if (cameraControlsAvailable) {
            PanelLabel("Заморозить экспозицию/ЦТ", info = INFO_AE_AWB_LOCK)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StreetChip("Вкл", aeAwbLocked, Modifier.weight(1f)) { onAeAwbLocked(true) }
                StreetChip("Выкл", !aeAwbLocked, Modifier.weight(1f)) { onAeAwbLocked(false) }
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

private fun formatEv(value: Float): String {
    if (value == 0f) return "0 EV"
    return (if (value > 0) "+" else "") + "%.1f EV".format(java.util.Locale.US, value)
}

private const val INFO_PREVIEW =
    "Картинка с камеры прямо на экране телефона. Зрителю всё равно — эфир идёт и без неё.\n\n" +
        "Когда выключать:\n" +
        "• телефон греется (экран жарит не хуже энкодера)\n" +
        "• бережёшь батарею в долгом стриме"
private const val INFO_STABILIZATION =
    "Программно гасит тряску и дрожь рук — картинка плавнее на ходу.\n\n" +
        "О чём помнить:\n" +
        "• чуть подрезает края кадра\n" +
        "• влезает не в любой режим (разрешение/fps)\n" +
        "• на статичном штативе не нужна"
private const val INFO_AE_AWB_LOCK =
    "Фиксирует экспозицию и баланс белого — картинка перестаёт «дышать» яркостью и цветом при панораме.\n\n" +
        "Когда включать:\n" +
        "• ведёшь камерой по сцене с разным светом\n" +
        "• снимаешь экран/монитор (не мерцает)\n\n" +
        "Выключи, если сам свет в сцене меняется."
