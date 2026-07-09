package n7.bondcast.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import n7.bondcast.DiscordColors
import n7.bondcast.obs.ObsPhase
import n7.bondcast.ui.street.streetLabel

/** Цвет статуса пульта OBS: красит точку в панели. */
public fun obsPhaseColor(phase: ObsPhase): Color = when (phase) {
    ObsPhase.Connected -> DiscordColors.green
    ObsPhase.Connecting, is ObsPhase.Retrying -> DiscordColors.yellow
    ObsPhase.AuthFailed -> DiscordColors.danger
    ObsPhase.Disconnected -> DiscordColors.textMuted
}

/** Значок пульта OBS — узнаваемый жирный текст «OBS». */
@Composable
public fun ObsIcon(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "OBS",
        color = color,
        style = streetLabel.copy(fontSize = 13.sp),
        modifier = modifier,
    )
}
