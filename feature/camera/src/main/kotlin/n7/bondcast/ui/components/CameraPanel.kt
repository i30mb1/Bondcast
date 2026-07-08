package n7.bondcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import n7.bondcast.stream.CameraOption

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
    Column(
        modifier = modifier
            .width(220.dp)
            .heightIn(max = 420.dp)
            .background(DiscordColors.background.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Камера",
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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cameras.forEach { cam ->
                val selected = cam == current
                Text(
                    text = cam.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color.White else DiscordColors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) DiscordColors.blurple else DiscordColors.elevated)
                        .clickable { onSelect(cam) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        Text(
            text = "Превью на экране",
            color = DiscordColors.textMuted,
            style = MaterialTheme.typography.labelMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Вкл", previewEnabled) { onPreviewEnabled(true) }
            ToggleChip("Выкл", !previewEnabled) { onPreviewEnabled(false) }
        }
        if (showHint) {
            Text(
                text = "Выкл — экран отдыхает, телефон холоднее. Зрители разницы не заметят 😉",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (cameraControlsAvailable && stabilizationSupported) {
            Text(
                text = "Стабилизация",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Вкл", stabilizationEnabled) { onStabilizationEnabled(true) }
                ToggleChip("Выкл", !stabilizationEnabled) { onStabilizationEnabled(false) }
            }
            if (stabilizationEnabled && !stabilizationActive) {
                Text(
                    text = "Не влезла в текущий режим съёмки (разрешение/fps).",
                    color = DiscordColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (showHint && stabilizationEnabled) {
                Text(
                    text = "Гасит тряску, но чуть подрезает края кадра.",
                    color = DiscordColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (cameraControlsAvailable) {
            Text(
                text = "Заморозить экспозицию/ЦТ",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Вкл", aeAwbLocked) { onAeAwbLocked(true) }
                ToggleChip("Выкл", !aeAwbLocked) { onAeAwbLocked(false) }
            }
            if (showHint && aeAwbLocked) {
                Text(
                    text = "Картинка не «дышит» при панораме — экспозиция и цвет зафиксированы.",
                    color = DiscordColors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (exposureSupported) {
                Text(
                    text = "Экспозиция",
                    color = DiscordColors.textMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToggleChip("−", false, enabled = exposureIndex > exposureRange.first) {
                        onExposureIndexChange((exposureIndex - 1).coerceIn(exposureRange))
                    }
                    Text(
                        text = formatEv(exposureIndex * exposureStepEv),
                        color = DiscordColors.textSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    ToggleChip("+", false, enabled = exposureIndex < exposureRange.last) {
                        onExposureIndexChange((exposureIndex + 1).coerceIn(exposureRange))
                    }
                }
            }

            if (llbAvailable) {
                Text(
                    text = "Ночной режим",
                    color = DiscordColors.textMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip("Вкл", llbEnabled) { onLlbEnabled(true) }
                    ToggleChip("Выкл", !llbEnabled) { onLlbEnabled(false) }
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

private fun formatEv(value: Float): String {
    if (value == 0f) return "0 EV"
    return (if (value > 0) "+" else "") + "%.1f EV".format(java.util.Locale.US, value)
}

@Composable
private fun RowScope.ToggleChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            !enabled -> DiscordColors.textMuted
            selected -> Color.White
            else -> DiscordColors.textSecondary
        },
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected && enabled -> DiscordColors.blurple
                    selected -> DiscordColors.blurple.copy(alpha = 0.4f)
                    else -> DiscordColors.elevated
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
    )
}
