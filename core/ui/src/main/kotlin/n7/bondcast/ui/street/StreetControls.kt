package n7.bondcast.ui.street

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors

public val StreetShape: RoundedCornerShape = RoundedCornerShape(4.dp)
public val PanelShape: RoundedCornerShape = RoundedCornerShape(8.dp)

@Composable
public fun StreetChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text.upper(),
        style = streetLabel,
        color = when {
            !enabled -> DiscordColors.textMuted
            selected -> Color.White
            else -> DiscordColors.textSecondary
        },
        textAlign = TextAlign.Center,
        modifier = modifier
            .pressBounce(interaction)
            .clip(StreetShape)
            .background(
                when {
                    selected && enabled -> DiscordColors.accent
                    selected -> DiscordColors.accent.copy(alpha = 0.4f)
                    else -> DiscordColors.plate
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 9.dp, horizontal = 10.dp),
    )
}

@Composable
public fun StreetStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    sub: String? = null,
    labelColor: Color = DiscordColors.accent,
) {
    Column(
        modifier = modifier
            .clip(StreetShape)
            .background(DiscordColors.plate)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(text = label.upper(), style = streetLabel, color = labelColor)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = streetValue, color = DiscordColors.textPrimary)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = streetUnit,
                    color = DiscordColors.textMuted,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        if (sub != null) {
            Text(text = sub, style = streetUnit, color = DiscordColors.textMuted)
        }
    }
}

@Composable
public fun StreetPanelScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .width(262.dp)
            .fillMaxHeight()
            .shadow(20.dp, PanelShape, clip = false)
            .clip(PanelShape)
            .background(DiscordColors.panel)
            .border(2.dp, DiscordColors.accent, PanelShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = title.upper(),
                color = DiscordColors.textPrimary,
                style = streetTitle,
                modifier = Modifier.weight(1f),
            )
            StreetCloseButton(onClose)
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun StreetCloseButton(onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressBounce(interaction)
            .size(28.dp)
            .clip(StreetShape)
            .border(2.dp, DiscordColors.accent, StreetShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "✕", color = DiscordColors.textPrimary, style = streetLabel)
    }
}
