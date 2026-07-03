package n7.bondcast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.delay
import n7.bondcast.settings.StreamSettings
import n7.bondcast.stream.StreamController
import n7.bondcast.stream.StreamPhase

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
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            StatusLine(phase)
            if (settings != null) {
                Text(
                    text = "${settings.width}×${settings.height}@${settings.fps} • ${settings.videoBitrateKbps} kbps",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                Text(
                    text = "${settings.url} → live/${settings.streamName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        TextButton(
            onClick = onOpenSettings,
            enabled = phase is StreamPhase.Idle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
        ) {
            Text(text = "⚙", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        }

        val streaming = phase !is StreamPhase.Idle
        Button(
            onClick = { if (streaming) controller.stop() else controller.start() },
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
            Text(if (streaming) "Стоп" else "В эфир")
        }
    }
}

@Composable
private fun StatusLine(phase: StreamPhase) {
    val (color, label) = when (phase) {
        is StreamPhase.Idle -> Color.Gray to "Не в эфире"
        is StreamPhase.Connecting -> Color(0xFFFEE75C) to "Подключение…"
        is StreamPhase.Live -> Color(0xFF57F287) to "В ЭФИРЕ"
        is StreamPhase.Retrying ->
            Color(0xFFED4245) to "Реконнект #${phase.attempt}${phase.cause?.let { ": $it" } ?: ""}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall)
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
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
    )
}
