package n7.bondcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import n7.bondcast.DiscordColors
import n7.bondcast.ui.street.BackIcon
import n7.bondcast.ui.street.BackIconSize
import n7.bondcast.ui.street.InfoIcon
import n7.bondcast.ui.street.InfoIconSize
import n7.bondcast.ui.street.StreetTooltip
import n7.bondcast.ui.street.streetLabel
import n7.bondcast.ui.street.streetTitle
import n7.bondcast.ui.street.upper

@Composable
public fun DiscordTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                BackIcon(color = DiscordColors.textPrimary, modifier = Modifier.size(BackIconSize))
            }
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title.upper(),
            color = DiscordColors.textPrimary,
            style = streetTitle.copy(fontSize = 22.sp),
        )
    }
}

@Composable
public fun SectionLabel(text: String) {
    Text(
        text = text.upper(),
        color = DiscordColors.accent,
        style = streetLabel.copy(fontSize = 12.sp),
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, top = 8.dp),
    )
}

@Composable
public fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DiscordColors.card)
            .border(1.dp, DiscordColors.divider, RoundedCornerShape(8.dp)),
        content = content,
    )
}

@Composable
public fun RowDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = DiscordColors.divider,
        modifier = Modifier.padding(start = 16.dp),
    )
}

private val InfoButtonTouchSize = 28.dp

/** Значок ⓘ, по тапу открывающий всплывающую подсказку с треугольником-указателем на себя. */
@Composable
public fun InfoButton(info: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(InfoButtonTouchSize)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = { expanded = !expanded }),
        contentAlignment = Alignment.Center,
    ) {
        InfoIcon(color = DiscordColors.textMuted, modifier = Modifier.size(InfoIconSize))
        if (expanded) {
            StreetTooltip(text = info, onDismissRequest = { expanded = false })
        }
    }
}

/**
 * Строка-подпись поля с опциональной ⓘ. Минимальная высота = высоте ⓘ,
 * чтобы соседние поля в Row не разъезжались по вертикали, когда ⓘ есть не у всех.
 */
@Composable
private fun FieldLabel(label: String, info: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        Text(
            text = label,
            color = DiscordColors.textMuted,
            style = MaterialTheme.typography.labelMedium,
        )
        if (info != null) {
            Spacer(Modifier.width(6.dp))
            InfoButton(info)
        }
    }
}

@Composable
public fun DiscordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    info: String? = null,
) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        FieldLabel(label, info)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DiscordColors.inputBackground, RoundedCornerShape(8.dp))
                .border(
                    width = 1.dp,
                    color = if (isError) MaterialTheme.colorScheme.error else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = DiscordColors.textPrimary),
                cursorBrush = SolidColor(DiscordColors.blurple),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
public fun DiscordRangeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Number,
    isError: Boolean = false,
    info: String? = null,
) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        FieldLabel(label, info)
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoundButton(min.toString()) { onValueChange(min.toString()) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(DiscordColors.inputBackground, RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = if (isError) MaterialTheme.colorScheme.error else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = DiscordColors.textPrimary),
                    cursorBrush = SolidColor(DiscordColors.blurple),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BoundButton(max.toString()) { onValueChange(max.toString()) }
        }
    }
}

/** Числовое поле с кнопками −/+ по краям (шаг [step], значение зажимается в [min]..[max]). */
@Composable
public fun DiscordStepperField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    step: Int = 100,
    isError: Boolean = false,
    info: String? = null,
) {
    fun nudge(delta: Int) {
        val current = value.toIntOrNull() ?: min
        onValueChange((current + delta).coerceIn(min, max).toString())
    }
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        FieldLabel(label, info)
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoundButton("−") { nudge(-step) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(DiscordColors.inputBackground, RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = if (isError) MaterialTheme.colorScheme.error else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = DiscordColors.textPrimary),
                    cursorBrush = SolidColor(DiscordColors.blurple),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BoundButton("+") { nudge(step) }
        }
    }
}

/** Приглушённая строка-подсказка под полем; с [onClick] становится тапабельной. */
@Composable
public fun DiscordHint(
    text: String,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(bottom = 12.dp)
    Text(
        text = text,
        color = if (onClick != null) DiscordColors.blurple else DiscordColors.textMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = if (onClick != null) {
            Modifier
                .clickable(onClick = onClick)
                .then(base)
        } else {
            base
        },
    )
}

@Composable
private fun BoundButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DiscordColors.elevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = DiscordColors.textSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
public fun DiscordSegmentedRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    info: String? = null,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            if (info != null) {
                Spacer(Modifier.width(6.dp))
                InfoButton(info)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DiscordColors.inputBackground, RoundedCornerShape(6.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = if (selected) DiscordColors.blurple else Color.Transparent,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .clickable { onSelect(index) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        color = if (selected) Color.White else DiscordColors.textSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
public fun DiscordSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    info: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = DiscordColors.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (info != null) {
            InfoButton(info)
            Spacer(Modifier.width(8.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = DiscordColors.blurple,
                checkedBorderColor = DiscordColors.blurple,
                uncheckedThumbColor = DiscordColors.textSecondary,
                uncheckedTrackColor = DiscordColors.inputBackground,
                uncheckedBorderColor = DiscordColors.divider,
            ),
        )
    }
}

@Composable
public fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, RoundedCornerShape(50)),
    )
}
