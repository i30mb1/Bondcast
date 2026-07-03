package n7.bondcast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import n7.bondcast.settings.StreamSettings

@Composable
internal fun SettingsScreen(
    initial: StreamSettings,
    onSave: (StreamSettings) -> Unit,
    onBack: () -> Unit,
) {
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var streamName by remember { mutableStateOf(initial.streamName) }
    var passphrase by remember { mutableStateOf(initial.passphrase) }
    var bitrate by remember { mutableStateOf(initial.videoBitrateKbps.toString()) }
    var latency by remember { mutableStateOf(initial.latencyMs.toString()) }
    var is1080p by remember { mutableStateOf(initial.width >= 1920) }
    var is60fps by remember { mutableStateOf(initial.fps >= 60) }

    val portInt = port.toIntOrNull()
    val bitrateInt = bitrate.toIntOrNull()
    val latencyInt = latency.toIntOrNull()
    val valid = host.isNotBlank() &&
        streamName.isNotBlank() &&
        portInt != null && portInt in 1..65535 &&
        bitrateInt != null && bitrateInt in 500..20_000 &&
        latencyInt != null && latencyInt in 20..8_000

    BackHandler(onBack = onBack)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Настройки стрима", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Хост SRT-сервера") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Порт") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = latency,
                    onValueChange = { latency = it },
                    label = { Text("Latency, мс") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = streamName,
                onValueChange = { streamName = it },
                label = { Text("Имя стрима (live/<имя>)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase (пусто — без шифрования)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = bitrate,
                onValueChange = { bitrate = it },
                label = { Text("Битрейт видео, kbps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !is1080p,
                    onClick = { is1080p = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("720p") }
                SegmentedButton(
                    selected = is1080p,
                    onClick = { is1080p = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("1080p") }
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !is60fps,
                    onClick = { is60fps = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("30 fps") }
                SegmentedButton(
                    selected = is60fps,
                    onClick = { is60fps = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("60 fps") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Назад")
                }
                Button(
                    onClick = {
                        onSave(
                            StreamSettings(
                                host = host.trim(),
                                port = requireNotNull(portInt),
                                streamName = streamName.trim(),
                                passphrase = passphrase,
                                width = if (is1080p) 1920 else 1280,
                                height = if (is1080p) 1080 else 720,
                                fps = if (is60fps) 60 else 30,
                                videoBitrateKbps = requireNotNull(bitrateInt),
                                latencyMs = requireNotNull(latencyInt),
                            ),
                        )
                    },
                    enabled = valid,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Сохранить")
                }
            }
        }
    }
}
