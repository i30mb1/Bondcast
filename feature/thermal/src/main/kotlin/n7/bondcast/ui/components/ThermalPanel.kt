package n7.bondcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.temperatureColor
import n7.bondcast.thermal.ThermalState
import kotlin.math.roundToInt

@Composable
public fun ThermalPanel(
    state: ThermalState,
    effectiveBitrateKbps: Int,
    brightness: Float?,
    onBrightness: (Float?) -> Unit,
    previewEnabled: Boolean,
    onPreviewEnabled: (Boolean) -> Unit,
    bitrateCapFraction: Float?,
    onBitrateCap: (Float?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(288.dp)
            .background(DiscordColors.background.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(temperatureColor(state.heat)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Температура: ${state.statusLabel}",
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

        state.headroom?.let {
            InfoLine("Тепловая нагрузка: ${(it * 100).roundToInt()}%")
        }
        state.batteryTempC?.let {
            InfoLine("Батарея: ${"%.1f".format(it)}°C")
        }
        InfoLine(
            text = "Битрейт: $effectiveBitrateKbps kbps" +
                if (bitrateCapFraction != null) " (потолок)" else "",
        )

        LabeledChips("Яркость экрана") {
            BrightnessChip("Авто", null, brightness, onBrightness)
            BrightnessChip("50%", 0.5f, brightness, onBrightness)
            BrightnessChip("25%", 0.25f, brightness, onBrightness)
            BrightnessChip("10%", 0.1f, brightness, onBrightness)
        }

        LabeledChips("Превью камеры") {
            ThermalChip("Вкл", previewEnabled) { onPreviewEnabled(true) }
            ThermalChip("Выкл", !previewEnabled) { onPreviewEnabled(false) }
        }

        LabeledChips("Потолок битрейта") {
            CapChip("Макс", null, bitrateCapFraction, onBitrateCap)
            CapChip("75%", 0.75f, bitrateCapFraction, onBitrateCap)
            CapChip("50%", 0.5f, bitrateCapFraction, onBitrateCap)
            CapChip("25%", 0.25f, bitrateCapFraction, onBitrateCap)
        }
        Text(
            text = "Битрейт снижает нагрев радио; кодер ограничен разрешением и fps.",
            color = DiscordColors.textMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        color = DiscordColors.textSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LabeledChips(label: String, chips: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = DiscordColors.textMuted,
            style = MaterialTheme.typography.labelMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = chips)
    }
}

@Composable
private fun RowScope.BrightnessChip(
    label: String,
    value: Float?,
    selected: Float?,
    onSelect: (Float?) -> Unit,
) {
    ThermalChip(label, selected == value) { onSelect(value) }
}

@Composable
private fun RowScope.CapChip(
    label: String,
    value: Float?,
    selected: Float?,
    onSelect: (Float?) -> Unit,
) {
    ThermalChip(label, selected == value) { onSelect(value) }
}

@Composable
private fun RowScope.ThermalChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Color.White else DiscordColors.textSecondary,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DiscordColors.blurple else DiscordColors.elevated)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
