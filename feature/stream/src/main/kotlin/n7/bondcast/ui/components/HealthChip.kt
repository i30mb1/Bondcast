package n7.bondcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import n7.bondcast.DiscordColors
import n7.bondcast.feature.stream.R
import n7.bondcast.stream.HealthLevel
import n7.bondcast.ui.street.SpringAppear
import n7.bondcast.ui.street.StreetShape
import n7.bondcast.ui.street.streetLabel
import n7.bondcast.ui.street.upper

@Composable
internal fun ConnectionHealthChip(
    overall: HealthLevel?,
    modifier: Modifier = Modifier,
) {
    if (overall == null) return
    val (dotColor, label) = when (overall) {
        HealthLevel.OK -> DiscordColors.green to stringResource(R.string.stream_health_ok)
        HealthLevel.WARN -> DiscordColors.yellow to stringResource(R.string.stream_health_warn)
        HealthLevel.BAD -> DiscordColors.danger to stringResource(R.string.stream_health_bad)
    }
    SpringAppear(modifier = modifier) {
        Row(
            modifier = Modifier
                .graphicsLayer { rotationZ = -1.5f }
                .shadow(10.dp, StreetShape, clip = false)
                .clip(StreetShape)
                .background(DiscordColors.sticker)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label.upper(),
                color = Color.Black,
                style = streetLabel.copy(fontSize = 11.sp),
            )
        }
    }
}
