package n7.bondcast.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.feature.chat.impl.R
import n7.bondcast.ui.street.StreetPanelScaffold
import n7.bondcast.ui.street.streetBody

/** Глазок — глиф «зрители», используется и в плашке статуса эфира, и как часть панели. */
@Composable
public fun ViewersIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.14f)
        val eye = Path().apply {
            moveTo(w * 0.04f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 0.02f, w * 0.96f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 0.98f, w * 0.04f, h * 0.5f)
            close()
        }
        drawPath(eye, color, style = stroke)
        drawCircle(color = color, radius = w * 0.16f, center = Offset(w / 2f, h / 2f))
    }
}

/** Прокручиваемый список ников из чата (тап по плашке статуса эфира). Twitch не отдаёт зрителей видео — только чата. */
@Composable
public fun ViewersPanel(
    total: Int,
    names: List<String>,
    onClose: () -> Unit,
) {
    StreetPanelScaffold(title = stringResource(R.string.viewers_panel_title), onClose = onClose) {
        Text(
            text = pluralizeViewers(total),
            color = DiscordColors.accent,
            style = streetBody,
        )
        if (names.isEmpty()) {
            Text(
                text = stringResource(R.string.viewers_panel_empty_label),
                color = DiscordColors.textMuted,
                style = streetBody,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                names.forEach { name ->
                    Text(
                        text = name,
                        color = DiscordColors.textSecondary,
                        style = streetBody,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun pluralizeViewers(count: Int): String = pluralStringResource(R.plurals.viewers_panel_count, count, count)
