package n7.bondcast.ui.components

import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import n7.bondcast.DiscordColors
import n7.bondcast.feature.thermal.R
import n7.bondcast.temperatureColor
import n7.bondcast.thermal.ThermalState
import n7.bondcast.ui.street.StreetChip
import n7.bondcast.ui.street.StreetPanelScaffold
import n7.bondcast.ui.street.StreetSectionLabel
import n7.bondcast.ui.street.StreetShape
import n7.bondcast.ui.street.StreetStatCard
import n7.bondcast.ui.street.pressBounce
import n7.bondcast.ui.street.streetBody
import kotlin.math.roundToInt

@Composable
public fun ThermalPanel(
    state: ThermalState,
    brightness: Float?,
    onBrightness: (Float?) -> Unit,
    onOpenCameras: () -> Unit,
    onOpenStats: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val leading: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(temperatureColor(state.heat)),
        )
    }
    StreetPanelScaffold(
        title = stringResource(R.string.thermal_panel_title),
        onClose = onClose,
        modifier = modifier,
        leading = leading,
        info = stringResource(R.string.thermal_info_main),
    ) {
        StreetStatCard(
            label = stringResource(R.string.thermal_status_label),
            value = thermalStatusText(state.status),
            modifier = Modifier.fillMaxWidth(),
            labelColor = temperatureColor(state.heat),
            info = stringResource(R.string.thermal_info_status),
            // фраза на всю ширину карточки в 22sp давит сильнее, чем короткие числа соседних
            // карточек (нагрузка/батарея) — тот же принцип, что и остальная унификация шрифтов
            valueFontSize = 16.sp,
        )
        val headroom = state.headroom
        val battery = state.batteryTempC
        val loadLabel = stringResource(R.string.thermal_load_label)
        val percentUnit = stringResource(R.string.thermal_unit_percent)
        val infoLoad = stringResource(R.string.thermal_info_load)
        val loadCard: (@Composable RowScope.() -> Unit)? = headroom?.let {
            {
                StreetStatCard(
                    label = loadLabel,
                    value = (it * 100).roundToInt().toString(),
                    modifier = Modifier.weight(1f),
                    unit = percentUnit,
                    labelColor = temperatureColor(it.coerceIn(0f, 1f)),
                    info = infoLoad,
                )
            }
        }
        val batteryLabel = stringResource(R.string.thermal_battery_label)
        val celsiusUnit = stringResource(R.string.thermal_unit_celsius)
        val infoBattery = stringResource(R.string.thermal_info_battery)
        val batteryCard: (@Composable RowScope.() -> Unit)? = battery?.let {
            {
                StreetStatCard(
                    label = batteryLabel,
                    value = "%.1f".format(it),
                    modifier = Modifier.weight(1f),
                    unit = celsiusUnit,
                    labelColor = DiscordColors.textSecondary,
                    info = infoBattery,
                )
            }
        }
        if (loadCard != null || batteryCard != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                loadCard?.invoke(this)
                batteryCard?.invoke(this)
            }
        }

        StreetSectionLabel(stringResource(R.string.thermal_brightness_label))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrightnessChip(stringResource(R.string.thermal_brightness_auto), null, brightness, onBrightness)
            BrightnessChip(stringResource(R.string.thermal_brightness_50), 0.5f, brightness, onBrightness)
            BrightnessChip(stringResource(R.string.thermal_brightness_25), 0.25f, brightness, onBrightness)
            BrightnessChip(stringResource(R.string.thermal_brightness_10), 0.1f, brightness, onBrightness)
        }

        // те же пороги, что красят пламя: до MODERATE телефон справляется сам
        val needsCooling = state.status >= PowerManager.THERMAL_STATUS_MODERATE || state.heat >= 0.65f
        if (needsCooling) {
            StreetSectionLabel(stringResource(R.string.thermal_cooldown_label))
            AdviceRow(stringResource(R.string.thermal_advice_preview)) { onOpenCameras() }
            AdviceRow(stringResource(R.string.thermal_advice_bitrate)) { onOpenStats() }
        }
    }
}

@Composable
private fun thermalStatusText(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> stringResource(R.string.thermal_status_none)
    PowerManager.THERMAL_STATUS_LIGHT -> stringResource(R.string.thermal_status_light)
    PowerManager.THERMAL_STATUS_MODERATE -> stringResource(R.string.thermal_status_moderate)
    PowerManager.THERMAL_STATUS_SEVERE -> stringResource(R.string.thermal_status_severe)
    PowerManager.THERMAL_STATUS_CRITICAL -> stringResource(R.string.thermal_status_critical)
    PowerManager.THERMAL_STATUS_EMERGENCY -> stringResource(R.string.thermal_status_emergency)
    PowerManager.THERMAL_STATUS_SHUTDOWN -> stringResource(R.string.thermal_status_shutdown)
    else -> stringResource(R.string.thermal_status_unknown)
}

/** Совет-ссылка: тап открывает окно, где живёт нужная настройка. */
@Composable
private fun AdviceRow(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        color = DiscordColors.textPrimary,
        style = streetBody,
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
private fun RowScope.BrightnessChip(
    label: String,
    value: Float?,
    selected: Float?,
    onSelect: (Float?) -> Unit,
) {
    StreetChip(label, selected == value, Modifier.weight(1f)) { onSelect(value) }
}
