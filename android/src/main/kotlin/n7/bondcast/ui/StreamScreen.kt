package n7.bondcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.delay
import n7.bondcast.DiscordColors
import n7.bondcast.settings.StreamSettings
import n7.bondcast.stream.StreamController
import n7.bondcast.stream.StreamPhase
import n7.bondcast.ui.components.StatusDot
import n7.srtla.scheduler.RegState
import n7.srtla.scheduler.Transport

@Composable
internal fun StreamScreen(
    controller: StreamController,
    settings: StreamSettings?,
    onOpenSettings: () -> Unit,
) {
    val phase by controller.phase.collectAsState()
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(previewView, settings) {
        val view = previewView ?: return@LaunchedEffect
        val current = settings ?: return@LaunchedEffect
        controller.engine.prepare(current)
        controller.engine.bindPreview(view)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    keepScreenOn = true
                    enableZoomOnPinch = true
                    enableTapToFocus = true
                }
            },
            update = { view -> if (previewView !== view) previewView = view },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
                .background(DiscordColors.background.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            StatusLine(phase)
            if (settings != null) {
                Text(
                    text = "${settings.width}×${settings.height}@${settings.fps} • ${settings.videoBitrateKbps} kbps",
                    style = MaterialTheme.typography.bodySmall,
                    color = DiscordColors.textSecondary,
                )
                val destination = if (settings.bondingEnabled) {
                    "srtla://${settings.srtlaHost}:${settings.srtlaPort}"
                } else {
                    settings.url
                }
                Text(
                    text = "$destination → live/${settings.streamName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DiscordColors.textMuted,
                )
            }
            HudStats(controller)
            LinksPanel(controller)
        }

        Text(
            text = "⚙",
            color = DiscordColors.textPrimary,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
                .clip(CircleShape)
                .background(DiscordColors.background.copy(alpha = 0.72f))
                .clickable(enabled = phase is StreamPhase.Idle, onClick = onOpenSettings)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        val streaming = phase !is StreamPhase.Idle
        Button(
            onClick = { if (streaming) controller.stop() else controller.start() },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (streaming) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
        ) {
            Text(
                text = if (streaming) "Стоп" else "В эфир",
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HudStats(controller: StreamController) {
    val stats by controller.stats.collectAsState()
    val bitrate by controller.videoBitrateKbps.collectAsState()
    val liveStats = stats
    if (liveStats != null) {
        Text(
            text = "↑ ${liveStats.sendRateKbps} kbps · RTT ${liveStats.rttMs} мс · потери ${liveStats.pktLossTotal}",
            style = MaterialTheme.typography.bodySmall,
            color = DiscordColors.textPrimary,
        )
        if (bitrate > 0) {
            Text(
                text = "битрейт (ABR): $bitrate kbps",
                style = MaterialTheme.typography.bodySmall,
                color = DiscordColors.textSecondary,
            )
        }
    }
}

@Composable
private fun LinksPanel(controller: StreamController) {
    val links by controller.links.collectAsState()
    if (links.isEmpty()) return
    val totalKbps = links.sumOf { it.sendRateKbps }.coerceAtLeast(1)
    Spacer(Modifier.height(6.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(280.dp),
    ) {
        links.forEach { link ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when (link.reg) {
                        RegState.ACTIVE -> DiscordColors.green
                        RegState.WAIT_REG2, RegState.WAIT_REG3 -> DiscordColors.yellow
                        RegState.NONE -> DiscordColors.textMuted
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = link.transport.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = DiscordColors.textSecondary,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    text = formatRate(link.sendRateKbps),
                    style = MaterialTheme.typography.bodySmall,
                    color = DiscordColors.textPrimary,
                    modifier = Modifier.width(86.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DiscordColors.elevated),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((link.sendRateKbps.toFloat() / totalKbps).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(DiscordColors.blurple, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

private fun Transport.label(): String = when (this) {
    Transport.WIFI -> "WiFi"
    Transport.CELLULAR -> "Сотовая"
    Transport.ETHERNET -> "Ethernet"
    Transport.RELAY -> "Bondlink"
    Transport.UNKNOWN -> "Сеть"
}

private fun formatRate(kbps: Int): String =
    if (kbps >= 1000) "${kbps / 1000}.${kbps % 1000 / 100} Mbps" else "$kbps kbps"

@Composable
private fun StatusLine(phase: StreamPhase) {
    val (color, label) = when (phase) {
        is StreamPhase.Idle -> DiscordColors.textMuted to "Не в эфире"
        is StreamPhase.Connecting -> DiscordColors.yellow to "Подключение…"
        is StreamPhase.Live -> DiscordColors.green to "В ЭФИРЕ"
        is StreamPhase.Retrying ->
            DiscordColors.danger to "Реконнект #${phase.attempt}${phase.cause?.let { ": $it" } ?: ""}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = DiscordColors.textPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (phase is StreamPhase.Live) {
            Spacer(Modifier.width(12.dp))
            LiveTimer(phase.sinceEpochMs)
        }
    }
}

@Composable
private fun LiveTimer(sinceEpochMs: Long) {
    var elapsedSeconds by remember(sinceEpochMs) { mutableLongStateOf(0L) }
    LaunchedEffect(sinceEpochMs) {
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - sinceEpochMs) / 1000
            delay(1_000)
        }
    }
    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60
    fun Long.two(): String = toString().padStart(2, '0')
    Text(
        text = "${hours.two()}:${minutes.two()}:${seconds.two()}",
        color = DiscordColors.textPrimary,
        style = MaterialTheme.typography.titleSmall,
    )
}
