package n7.bondcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
import n7.bondcast.obs.ObsPhase

/** Цвет статуса пульта OBS: красит и иконку в рельсе, и точку в панели. */
public fun obsPhaseColor(phase: ObsPhase): Color = when (phase) {
    ObsPhase.Connected -> DiscordColors.green
    ObsPhase.Connecting, is ObsPhase.Retrying -> DiscordColors.yellow
    ObsPhase.AuthFailed -> DiscordColors.danger
    ObsPhase.Disconnected -> DiscordColors.textMuted
}

/** Кнопка пульта OBS в правой рельсе: подпись «OBS» красится по фазе подключения. */
@Composable
public fun ObsIcon(
    phase: ObsPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(DiscordColors.background.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "OBS",
            color = obsPhaseColor(phase),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
