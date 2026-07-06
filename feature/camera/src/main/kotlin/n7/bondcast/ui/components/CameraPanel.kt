package n7.bondcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.stream.CameraOption

@Composable
public fun CameraPanel(
    cameras: List<CameraOption>,
    current: CameraOption?,
    onSelect: (CameraOption) -> Unit,
    previewEnabled: Boolean,
    onPreviewEnabled: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showHint: Boolean = true,
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .background(DiscordColors.background.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Камера",
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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cameras.forEach { cam ->
                val selected = cam == current
                Text(
                    text = cam.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color.White else DiscordColors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) DiscordColors.blurple else DiscordColors.elevated)
                        .clickable { onSelect(cam) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }

        Text(
            text = "Превью на экране",
            color = DiscordColors.textMuted,
            style = MaterialTheme.typography.labelMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewChip("Вкл", previewEnabled) { onPreviewEnabled(true) }
            PreviewChip("Выкл", !previewEnabled) { onPreviewEnabled(false) }
        }
        if (showHint) {
            Text(
                text = "Выкл — экран отдыхает, телефон холоднее. Зрители разницы не заметят 😉",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RowScope.PreviewChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Color.White else DiscordColors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DiscordColors.blurple else DiscordColors.elevated)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}
