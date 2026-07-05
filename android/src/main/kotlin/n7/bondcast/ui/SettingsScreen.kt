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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import n7.bondcast.settings.StreamSettings
import n7.bondcast.ui.components.DiscordField
import n7.bondcast.ui.components.DiscordSegmentedRow
import n7.bondcast.ui.components.DiscordSwitchRow
import n7.bondcast.ui.components.DiscordTopBar
import n7.bondcast.ui.components.InfoDialog
import n7.bondcast.ui.components.RowDivider
import n7.bondcast.ui.components.SectionLabel
import n7.bondcast.ui.components.SettingsCard

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
    var abr by remember { mutableStateOf(initial.abrEnabled) }
    var minBitrate by remember { mutableStateOf(initial.minVideoBitrateKbps.toString()) }
    var latency by remember { mutableStateOf(initial.latencyMs.toString()) }
    var is1080p by remember { mutableStateOf(initial.width >= 1920) }
    var is60fps by remember { mutableStateOf(initial.fps >= 60) }
    var bonding by remember { mutableStateOf(initial.bondingEnabled) }
    var srtlaHost by remember { mutableStateOf(initial.srtlaHost) }
    var srtlaPort by remember { mutableStateOf(initial.srtlaPort.toString()) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }

    val portInt = port.toIntOrNull()
    val bitrateInt = bitrate.toIntOrNull()
    val latencyInt = latency.toIntOrNull()
    val srtlaPortInt = srtlaPort.toIntOrNull()
    val portValid = portInt != null && portInt in 1..65535
    val bitrateValid = bitrateInt != null && bitrateInt in 500..20_000
    val minBitrateInt = minBitrate.toIntOrNull()
    val minBitrateValid = !abr || (minBitrateInt != null && bitrateInt != null && minBitrateInt in 300..bitrateInt)
    val latencyValid = latencyInt != null && latencyInt in 20..8_000
    val srtlaPortValid = srtlaPortInt != null && srtlaPortInt in 1..65535
    val destinationValid = if (bonding) srtlaHost.isNotBlank() && srtlaPortValid else host.isNotBlank() && portValid
    val valid = streamName.isNotBlank() && bitrateValid &&
        minBitrateValid && latencyValid && destinationValid

    BackHandler(onBack = onBack)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            DiscordTopBar(title = "Настройки", onBack = onBack)

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionLabel("Назначение")
                SettingsCard {
                    DiscordSwitchRow(
                        label = "Бондинг (объединить сети)",
                        checked = bonding,
                        onCheckedChange = { bonding = it },
                        onInfo = {
                            info = "Бондинг (SRTLA)" to
                                "Объединяет сотовую + Wi-Fi (и доп. линки) в один SRT-поток через srtla_rec. " +
                                "При включении видео уходит на localhost и раздаётся по линкам — прямой адрес сервера не используется. " +
                                "Нужен запущенный srtla_rec перед вашим SRT-сервером."
                        },
                    )
                    RowDivider()
                    if (bonding) {
                        DiscordField(
                            label = "Хост srtla_rec",
                            value = srtlaHost,
                            onValueChange = { srtlaHost = it },
                            isError = srtlaHost.isBlank(),
                        )
                        RowDivider()
                        DiscordField(
                            label = "Порт srtla_rec",
                            value = srtlaPort,
                            onValueChange = { srtlaPort = it },
                            keyboardType = KeyboardType.Number,
                            isError = !srtlaPortValid,
                        )
                    } else {
                        DiscordField(
                            label = "Хост SRT-сервера",
                            value = host,
                            onValueChange = { host = it },
                            isError = host.isBlank(),
                        )
                        RowDivider()
                        DiscordField(
                            label = "Порт",
                            value = port,
                            onValueChange = { port = it },
                            keyboardType = KeyboardType.Number,
                            isError = !portValid,
                        )
                    }
                    RowDivider()
                    DiscordField(
                        label = "Имя стрима (live/<имя>)",
                        value = streamName,
                        onValueChange = { streamName = it },
                        isError = streamName.isBlank(),
                    )
                    RowDivider()
                    DiscordField(
                        label = "Passphrase",
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        onInfo = {
                            info = "Passphrase" to
                                "AES-шифрование SRT. Пусто — без шифрования; должно совпадать с сервером. " +
                                "streamid: #!::r=live/<имя>,m=publish."
                        },
                    )
                }

                SectionLabel("Видео")
                SettingsCard {
                    DiscordSegmentedRow(
                        label = "Разрешение",
                        options = listOf("720p", "1080p"),
                        selectedIndex = if (is1080p) 1 else 0,
                        onSelect = { is1080p = it == 1 },
                    )
                    RowDivider()
                    DiscordSegmentedRow(
                        label = "Частота кадров",
                        options = listOf("30 fps", "60 fps"),
                        selectedIndex = if (is60fps) 1 else 0,
                        onSelect = { is60fps = it == 1 },
                    )
                    RowDivider()
                    DiscordField(
                        label = "Битрейт видео, kbps",
                        value = bitrate,
                        onValueChange = { bitrate = it },
                        keyboardType = KeyboardType.Number,
                        isError = !bitrateValid,
                    )
                    RowDivider()
                    DiscordSwitchRow(
                        label = "Адаптивный битрейт (ABR)",
                        checked = abr,
                        onCheckedChange = { abr = it },
                        onInfo = {
                            info = "Адаптивный битрейт (ABR)" to
                                "Роняет качество под ёмкость сети и поднимает при запасе — эфир держится на слабом линке. " +
                                "Мин. битрейт — нижняя граница."
                        },
                    )
                    if (abr) {
                        RowDivider()
                        DiscordField(
                            label = "Мин. битрейт, kbps",
                            value = minBitrate,
                            onValueChange = { minBitrate = it },
                            keyboardType = KeyboardType.Number,
                            isError = !minBitrateValid,
                        )
                    }
                }

                SectionLabel("Соединение")
                SettingsCard {
                    DiscordField(
                        label = "Latency, мс",
                        value = latency,
                        onValueChange = { latency = it },
                        keyboardType = KeyboardType.Number,
                        isError = !latencyValid,
                        onInfo = {
                            info = "Latency, мс" to
                                "Буфер SRT для сглаживания потерь и джиттера. Больше — устойчивее, но выше задержка. " +
                                "Для бондинга и слабых сетей ставьте выше (напр. 2000–4000)."
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                                    abrEnabled = abr,
                                    minVideoBitrateKbps = minBitrateInt ?: 800,
                                    latencyMs = requireNotNull(latencyInt),
                                    bondingEnabled = bonding,
                                    srtlaHost = srtlaHost.trim(),
                                    srtlaPort = srtlaPortInt ?: 5000,
                                ),
                            )
                        },
                        enabled = valid,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Сохранить")
                    }
                }

                info?.let { (title, text) ->
                    InfoDialog(title, text) { info = null }
                }
            }
        }
    }
}
