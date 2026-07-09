package n7.bondcast.ui.components

import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.temperatureColor
import n7.bondcast.thermal.ThermalState
import n7.bondcast.ui.street.StreetChip
import n7.bondcast.ui.street.StreetPanelScaffold
import n7.bondcast.ui.street.StreetShape
import n7.bondcast.ui.street.pressBounce
import n7.bondcast.ui.street.streetLabel
import n7.bondcast.ui.street.upper
import kotlin.math.roundToInt

@Composable
public fun ThermalPanel(
    state: ThermalState,
    effectiveBitrateKbps: Int,
    brightness: Float?,
    onBrightness: (Float?) -> Unit,
    bitrateCapFraction: Float?,
    onOpenCameras: () -> Unit,
    onOpenStats: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showHints: Boolean = true,
) {
    val leading: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(temperatureColor(state.heat)),
        )
    }
    StreetPanelScaffold(title = "Температура", onClose = onClose, modifier = modifier, leading = leading) {
        InfoLine("Статус: ${state.statusLabel}")
        state.headroom?.let {
            InfoLine("Тепловая нагрузка: ${(it * 100).roundToInt()}%")
        }
        state.batteryTempC?.let {
            InfoLine("Батарея: ${"%.1f".format(it)}°C")
        }
        InfoLine(
            text = "Битрейт: $effectiveBitrateKbps kbps" +
                if (bitrateCapFraction != null) " (придавлен потолком)" else "",
        )

        Text(text = "Яркость экрана".upper(), color = DiscordColors.accent, style = streetLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrightnessChip("Авто", null, brightness, onBrightness)
            BrightnessChip("50%", 0.5f, brightness, onBrightness)
            BrightnessChip("25%", 0.25f, brightness, onBrightness)
            BrightnessChip("10%", 0.1f, brightness, onBrightness)
        }

        // те же пороги, что красят пламя: до MODERATE телефон справляется сам
        val needsCooling = state.status >= PowerManager.THERMAL_STATUS_MODERATE || state.heat >= 0.65f
        if (needsCooling) {
            Text(
                text = "Телефон намекает, что он не гриль. Что поможет:",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            AdviceRow("🎥 Вырубить превью — экран греет не хуже энкодера") { onOpenCameras() }
            AdviceRow("📉 Прижать потолок битрейта — энкодер скажет спасибо") { onOpenStats() }
        } else if (showHints) {
            Text(
                text = "Телефону хорошо. Стримь и ни о чём не думай 😎",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Совет-ссылка: тап открывает окно, где живёт нужная настройка. */
@Composable
private fun AdviceRow(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        color = DiscordColors.textPrimary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .pressBounce(interaction)
            .fillMaxWidth()
            .clip(StreetShape)
            .background(DiscordColors.plate)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
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

@Composable
private fun RowScope.BrightnessChip(
    label: String,
    value: Float?,
    selected: Float?,
    onSelect: (Float?) -> Unit,
) {
    StreetChip(label, selected == value, Modifier.weight(1f)) { onSelect(value) }
}
