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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import n7.bondcast.DiscordColors
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
        title = "Температура",
        onClose = onClose,
        modifier = modifier,
        leading = leading,
        info = INFO_THERMAL,
    ) {
        StreetStatCard(
            label = "Статус",
            value = state.statusLabel,
            modifier = Modifier.fillMaxWidth(),
            labelColor = temperatureColor(state.heat),
            info = INFO_THERMAL_STATUS,
            // фраза на всю ширину карточки в 22sp давит сильнее, чем короткие числа соседних
            // карточек (нагрузка/батарея) — тот же принцип, что и остальная унификация шрифтов
            valueFontSize = 16.sp,
        )
        val headroom = state.headroom
        val battery = state.batteryTempC
        val loadCard: (@Composable RowScope.() -> Unit)? = headroom?.let {
            {
                StreetStatCard(
                    label = "Нагрузка",
                    value = (it * 100).roundToInt().toString(),
                    modifier = Modifier.weight(1f),
                    unit = "%",
                    labelColor = temperatureColor(it.coerceIn(0f, 1f)),
                    info = INFO_THERMAL_LOAD,
                )
            }
        }
        val batteryCard: (@Composable RowScope.() -> Unit)? = battery?.let {
            {
                StreetStatCard(
                    label = "Батарея",
                    value = "%.1f".format(it),
                    modifier = Modifier.weight(1f),
                    unit = "°C",
                    labelColor = DiscordColors.textSecondary,
                    info = INFO_THERMAL_BATTERY,
                )
            }
        }
        if (loadCard != null || batteryCard != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                loadCard?.invoke(this)
                batteryCard?.invoke(this)
            }
        }

        StreetSectionLabel("Яркость экрана")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrightnessChip("Авто", null, brightness, onBrightness)
            BrightnessChip("50%", 0.5f, brightness, onBrightness)
            BrightnessChip("25%", 0.25f, brightness, onBrightness)
            BrightnessChip("10%", 0.1f, brightness, onBrightness)
        }

        // те же пороги, что красят пламя: до MODERATE телефон справляется сам
        val needsCooling = state.status >= PowerManager.THERMAL_STATUS_MODERATE || state.heat >= 0.65f
        if (needsCooling) {
            StreetSectionLabel("Что сделать, чтобы остыть")
            AdviceRow("🎥 Вырубить превью — экран греет не хуже энкодера") { onOpenCameras() }
            AdviceRow("📉 Опустить «Макс битрейт» — энкодер скажет спасибо") { onOpenStats() }
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

private const val INFO_THERMAL =
    "Термометр телефона в реальном времени. Пока прохладно — стримь и ни о чём не думай 😎\n\n" +
        "Начнёт греться — цвет поедет к красному, а ниже появятся кнопки-советы, как остыть."
private const val INFO_THERMAL_STATUS =
    "Как сам телефон оценивает свой нагрев: от «норма» до «аварийный». " +
        "С «умеренного» система уже сама придушивает частоты — кадры могут поехать.\n\n" +
        "Что делать при нагреве:\n" +
        "• выключить превью экрана (панель камеры)\n" +
        "• опустить «Макс битрейт» (панель статистики)\n" +
        "• снизить яркость кнопками ниже\n" +
        "• снять чехол, увести от солнца"
private const val INFO_THERMAL_LOAD =
    "Тепловой запас: насколько телефон близко к троттлингу. " +
        "0% — холоден и бодр, 100% — уже у предела и режет производительность.\n\n" +
        "Что делать, если растёт:\n" +
        "• убрать превью и лишнюю яркость\n" +
        "• снизить битрейт или разрешение/fps\n" +
        "• дать телефону продышаться (тень, обдув)"
private const val INFO_THERMAL_BATTERY =
    "Температура батареи — самый честный градусник корпуса. " +
        "До ~40 °C норма, к 45 °C уже горячо, выше — телефон начнёт спасаться сам.\n\n" +
        "Что делать, если высоко:\n" +
        "• отключить зарядку на ходу (пауэрбанк греет)\n" +
        "• убрать превью и сбавить битрейт\n" +
        "• проветрить корпус, снять чехол"
