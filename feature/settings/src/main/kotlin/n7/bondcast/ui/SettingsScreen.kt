package n7.bondcast.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.settings.StreamSettings
import n7.bondcast.settings.VideoCodec
import n7.bondcast.ui.components.DiscordField
import n7.bondcast.ui.components.DiscordHint
import n7.bondcast.ui.components.DiscordSegmentedRow
import n7.bondcast.ui.components.DiscordStepperField
import n7.bondcast.ui.components.DiscordSwitchRow
import n7.bondcast.ui.components.DiscordTopBar
import n7.bondcast.ui.components.InfoDialog
import n7.bondcast.ui.components.RowDivider
import n7.bondcast.ui.components.SectionLabel
import n7.bondcast.ui.components.SettingsCard

@Composable
public fun SettingsScreen(
    initial: StreamSettings,
    onSave: (StreamSettings) -> Unit,
    onBack: () -> Unit,
) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var streamName by remember { mutableStateOf(initial.streamName) }
    var passphrase by remember { mutableStateOf(initial.passphrase) }
    var bitrate by remember { mutableStateOf(initial.videoBitrateKbps.toString()) }
    var latency by remember { mutableStateOf(initial.latencyMs.toString()) }
    var is1080p by remember { mutableStateOf(initial.width >= 1920) }
    var is60fps by remember { mutableStateOf(initial.fps >= 60) }
    var bonding by remember { mutableStateOf(initial.bondingEnabled) }
    var hints by remember { mutableStateOf(initial.hintsEnabled) }
    var srtlaHost by remember { mutableStateOf(initial.srtlaHost) }
    var srtlaPort by remember { mutableStateOf(initial.srtlaPort.toString()) }
    var obsEnabled by remember { mutableStateOf(initial.obsEnabled) }
    var obsHost by remember { mutableStateOf(initial.obsHost) }
    var obsPort by remember { mutableStateOf(initial.obsPort.toString()) }
    var obsPassword by remember { mutableStateOf(initial.obsPassword) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }

    val portInt = port.toIntOrNull()
    val bitrateInt = bitrate.toIntOrNull()
    val latencyInt = latency.toIntOrNull()
    val srtlaPortInt = srtlaPort.toIntOrNull()
    val obsPortInt = obsPort.toIntOrNull()
    val portValid = portInt != null && portInt in 1..65535
    val bitrateValid = bitrateInt != null && bitrateInt in 500..20_000
    val latencyValid = latencyInt != null && latencyInt in 20..8_000
    val srtlaPortValid = srtlaPortInt != null && srtlaPortInt in 1..65535
    // пульт выключен — его поля скрыты и не проверяются
    val obsPortValid = obsPortInt != null && obsPortInt in 1..65535
    val obsValid = !obsEnabled || (obsHost.isNotBlank() && obsPortValid)
    val destinationValid = if (bonding) srtlaHost.isNotBlank() && srtlaPortValid else host.isNotBlank() && portValid
    val valid = streamName.isNotBlank() && bitrateValid && latencyValid && destinationValid && obsValid

    // рекомендации для H.265 по гайду belabox: сложность сцены решает не меньше разрешения —
    // статичная комната прощает низкий битрейт, улица с листвой и движением просит почти вдвое больше
    val (recIndoorKbps, recOutdoorKbps) = when {
        is1080p && is60fps -> 6_500 to 10_000
        is1080p -> 4_500 to 8_000
        is60fps -> 3_500 to 5_500
        else -> 2_500 to 4_000
    }

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
                SectionLabel("Сервер")
                SettingsCard {
                    DiscordSwitchRow(
                        label = "Бондинг (объединить сети)",
                        checked = bonding,
                        onCheckedChange = { bonding = it },
                        onInfo = {
                            info = "Бондинг (SRTLA)" to
                                "Собирает Wi-Fi и сотовую в одну пати: пакеты бегут по всем сетям сразу, " +
                                "и если одна прилегла отдохнуть — остальные тащат. " +
                                "Видео при этом уходит на srtla_rec, а не напрямую на SRT-порт. " +
                                "Идеально для стрима на ходу. Дома можно и не включать — но кто мы такие, чтобы запрещать."
                        },
                    )
                    RowDivider()
                    if (bonding) {
                        Row {
                            DiscordField(
                                label = "Хост srtla_rec",
                                value = srtlaHost,
                                onValueChange = { srtlaHost = it },
                                // хост — это IP: Decimal даёт цифровую панель с точкой
                                keyboardType = KeyboardType.Decimal,
                                isError = srtlaHost.isBlank(),
                                modifier = Modifier.weight(2f),
                            )
                            DiscordField(
                                label = "Порт",
                                value = srtlaPort,
                                onValueChange = { srtlaPort = it },
                                keyboardType = KeyboardType.Number,
                                isError = !srtlaPortValid,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Row {
                            DiscordField(
                                label = "Хост SRT-сервера",
                                value = host,
                                onValueChange = { host = it },
                                keyboardType = KeyboardType.Decimal,
                                isError = host.isBlank(),
                                modifier = Modifier.weight(2f),
                            )
                            DiscordField(
                                label = "Порт",
                                value = port,
                                onValueChange = { port = it },
                                keyboardType = KeyboardType.Number,
                                isError = !portValid,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    RowDivider()
                    Row {
                        DiscordField(
                            label = "Имя стрима",
                            value = streamName,
                            onValueChange = { streamName = it },
                            isError = streamName.isBlank(),
                            modifier = Modifier.weight(1f),
                            onInfo = {
                                info = "Имя стрима" to
                                    "Поток приедет на сервер как live/<имя>. " +
                                    "Назови как хочешь — «phone» скромно и со вкусом, " +
                                    "«super_mega_stream_3000» тоже примем без осуждения."
                            },
                        )
                        DiscordField(
                            label = "Passphrase",
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            modifier = Modifier.weight(1f),
                            onInfo = {
                                info = "Passphrase" to
                                    "Секретный стук в дверь (AES-шифрование SRT). " +
                                    "Пусто — дверь нараспашку, для своего сервера это ок. " +
                                    "Если задал — сервер должен знать тот же секрет, " +
                                    "иначе он сделает вид, что впервые тебя видит."
                            },
                        )
                    }
                    RowDivider()
                    DiscordStepperField(
                        label = "Latency, мс",
                        value = latency,
                        onValueChange = { latency = it },
                        min = 20,
                        max = 8_000,
                        step = 100,
                        isError = !latencyValid,
                        onInfo = {
                            info = "Latency" to
                                "Подушка безопасности SRT: сколько миллисекунд у потерянного пакета есть, " +
                                "чтобы досдаться повторно. Больше — стабильнее, но зритель дальше от реальности. " +
                                "2000 — золотая середина: пара секунд задержки, зато без артефактов. " +
                                "Меньше 500 ставят только адреналиновые наркоманы."
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
                    DiscordStepperField(
                        label = "Битрейт видео, kbps",
                        value = bitrate,
                        onValueChange = { bitrate = it },
                        min = 500,
                        max = 20_000,
                        step = 100,
                        isError = !bitrateValid,
                        onInfo = {
                            info = "Битрейт видео" to
                                "Сколько мегабит в секунду тратим на красоту. H.265 умеет в магию сжатия, " +
                                "так что задирать не нужно: выше рекомендации картинка почти не хорошеет, " +
                                "зато телефон превращается в грелку и начинает ронять кадры. " +
                                "Сцена решает: статичная комната прощает почти всё, а улица с листвой " +
                                "и движением — самый прожорливый контент. Жми пресет ниже — мы уже посчитали."
                        },
                    )
                    DiscordHint("💡 Пресеты под ${if (is1080p) "1080p" else "720p"}·${if (is60fps) "60" else "30"} fps — тапни, применю:")
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RecommendChip(
                            text = "🏠 Комната ≈ $recIndoorKbps",
                            selected = bitrateInt == recIndoorKbps,
                            onClick = { bitrate = recIndoorKbps.toString() },
                        )
                        RecommendChip(
                            text = "🌳 Улица ≈ $recOutdoorKbps",
                            selected = bitrateInt == recOutdoorKbps,
                            onClick = { bitrate = recOutdoorKbps.toString() },
                        )
                    }
                    RowDivider()
                    DiscordSwitchRow(
                        label = "Шуточные подсказки",
                        checked = hints,
                        onCheckedChange = { hints = it },
                        onInfo = {
                            info = "Шуточные подсказки" to
                                "Серые подписи-объяснялки в карточках стрим-экрана. " +
                                "Когда выучишь всё наизусть — выключай, и интерфейс станет " +
                                "серьёзным, как бухгалтер в понедельник 🤵"
                        },
                    )
                }

                SectionLabel("OBS")
                SettingsCard {
                    DiscordSwitchRow(
                        label = "Пульт OBS",
                        checked = obsEnabled,
                        onCheckedChange = { obsEnabled = it },
                        onInfo = {
                            info = "Пульт OBS" to
                                "Панель на стрим-экране, которая командует OBS на компе: " +
                                "сцены, эфир, запись.\n\n" +
                                "Где что искать: в OBS открой Сервис → Настройка сервера WebSocket, " +
                                "поставь галочку «Включить сервер WebSocket» — порт и пароль написаны " +
                                "прямо там (кнопка «Показать сведения о подключении»). " +
                                "Хост — это IP компа в локальной сети (ipconfig → IPv4).\n\n" +
                                "Выключен — ни настроек, ни иконки, ничто не мозолит глаза."
                        },
                    )
                    if (obsEnabled) {
                        RowDivider()
                        Row {
                            DiscordField(
                                label = "Хост OBS",
                                value = obsHost,
                                onValueChange = { obsHost = it },
                                keyboardType = KeyboardType.Decimal,
                                isError = obsHost.isBlank(),
                                modifier = Modifier.weight(2f),
                            )
                            DiscordField(
                                label = "Порт",
                                value = obsPort,
                                onValueChange = { obsPort = it },
                                keyboardType = KeyboardType.Number,
                                isError = !obsPortValid,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        RowDivider()
                        DiscordField(
                            label = "Пароль WebSocket",
                            value = obsPassword,
                            onValueChange = { obsPassword = it },
                            onInfo = {
                                info = "Пароль WebSocket" to
                                    "Тот же, что в OBS: Сервис → Настройка сервера WebSocket → «Пароль сервера» " +
                                    "(или кнопка «Показать сведения о подключении»). " +
                                    "Если галочка «Включить аутентификацию» снята — оставь пустым."
                            },
                        )
                    }
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
                                    port = portInt ?: initial.port,
                                    streamName = streamName.trim(),
                                    passphrase = passphrase,
                                    width = if (is1080p) 1920 else 1280,
                                    height = if (is1080p) 1080 else 720,
                                    fps = if (is60fps) 60 else 30,
                                    // сервер свой и всегда умеет H.265 — меню кодека не показываем
                                    videoCodec = VideoCodec.H265,
                                    videoBitrateKbps = requireNotNull(bitrateInt),
                                    // ABR и мин. битрейт живут в карточке статистики на стрим-экране
                                    abrEnabled = initial.abrEnabled,
                                    minVideoBitrateKbps = initial.minVideoBitrateKbps,
                                    latencyMs = requireNotNull(latencyInt),
                                    bondingEnabled = bonding,
                                    srtlaHost = srtlaHost.trim(),
                                    srtlaPort = srtlaPortInt ?: 5000,
                                    hintsEnabled = hints,
                                    obsEnabled = obsEnabled,
                                    obsHost = obsHost.trim(),
                                    obsPort = obsPortInt ?: 4455,
                                    obsPassword = obsPassword,
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

@Composable
private fun RecommendChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = if (selected) Color.White else DiscordColors.textSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) DiscordColors.blurple else DiscordColors.inputBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
