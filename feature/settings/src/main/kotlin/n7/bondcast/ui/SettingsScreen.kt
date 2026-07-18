package n7.bondcast.ui

import android.content.pm.ActivityInfo
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.qr.QrPayload
import n7.bondcast.settings.StreamSettings
import n7.bondcast.settings.VideoCodec
import n7.bondcast.ui.components.DiscordField
import n7.bondcast.ui.components.DiscordHint
import n7.bondcast.ui.components.DiscordSegmentedRow
import n7.bondcast.ui.components.DiscordStepperField
import n7.bondcast.ui.components.DiscordSwitchRow
import n7.bondcast.ui.components.DiscordTopBar
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

    // один IP на всё: SRT-сервер, srtla_rec и пульт OBS живут на этой машине
    var host by remember { mutableStateOf(initial.srtlaHost.ifBlank { initial.obsHost.ifBlank { initial.host } }) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var streamName by remember { mutableStateOf(initial.streamName) }
    var passphrase by remember { mutableStateOf(initial.passphrase) }
    var bitrate by remember { mutableStateOf(initial.videoBitrateKbps.toString()) }
    var latency by remember { mutableStateOf(initial.latencyMs.toString()) }
    var is1080p by remember { mutableStateOf(initial.width >= 1920) }
    var is60fps by remember { mutableStateOf(initial.fps >= 60) }
    var bonding by remember { mutableStateOf(initial.bondingEnabled) }
    var srtlaPort by remember { mutableStateOf(initial.srtlaPort.toString()) }
    var obsEnabled by remember { mutableStateOf(initial.obsEnabled) }
    var obsPort by remember { mutableStateOf(initial.obsPort.toString()) }
    var obsPassword by remember { mutableStateOf(initial.obsPassword) }
    var showScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val portInt = port.toIntOrNull()
    val bitrateInt = bitrate.toIntOrNull()
    val latencyInt = latency.toIntOrNull()
    val srtlaPortInt = srtlaPort.toIntOrNull()
    val obsPortInt = obsPort.toIntOrNull()
    val portValid = portInt != null && portInt in 1..65535
    val bitrateValid = bitrateInt != null && bitrateInt in 500..20_000
    val latencyValid = latencyInt != null && latencyInt in 20..8_000
    val srtlaPortValid = srtlaPortInt != null && srtlaPortInt in 1..65535
    // порт зависит от режима: бондинг шлёт на srtla_rec, иначе напрямую на SRT
    val activePortValid = if (bonding) srtlaPortValid else portValid
    // пульт выключен — его поля скрыты и не проверяются
    val obsPortValid = obsPortInt != null && obsPortInt in 1..65535
    val obsValid = !obsEnabled || obsPortValid
    val destinationValid = host.isNotBlank() && activePortValid
    val valid = streamName.isNotBlank() && bitrateValid && latencyValid && destinationValid && obsValid

    // рекомендации для H.265 по гайду belabox: сложность сцены решает не меньше разрешения —
    // статичная комната прощает низкий битрейт, улица с листвой и движением просит почти вдвое больше
    val (recIndoorKbps, recOutdoorKbps) = when {
        is1080p && is60fps -> 6_500 to 10_000
        is1080p -> 4_500 to 8_000
        is60fps -> 3_500 to 5_500
        else -> 2_500 to 4_000
    }

    if (showScanner) {
        QrScannerScreen(
            onResult = { payload ->
                when (payload) {
                    is QrPayload.ObsConnect -> {
                        host = payload.host
                        obsPort = payload.port.toString()
                        obsPassword = payload.password
                        obsEnabled = true
                    }
                    is QrPayload.ServerConfig -> {
                        (payload.host ?: payload.srtlaHost)?.let { host = it }
                        payload.port?.let { port = it.toString() }
                        payload.srtlaPort?.let { srtlaPort = it.toString() }
                        payload.streamName?.let { streamName = it }
                        payload.passphrase?.let { passphrase = it }
                        payload.bonding?.let { bonding = it }
                        payload.obsPort?.let { obsPort = it.toString(); obsEnabled = true }
                        payload.obsPassword?.let { obsPassword = it; obsEnabled = true }
                    }
                    is QrPayload.Unknown ->
                        Toast.makeText(context, "QR не распознан", Toast.LENGTH_SHORT).show()
                }
                showScanner = false
            },
            onBack = { showScanner = false },
        )
        return
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
                    Button(
                        onClick = { showScanner = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("📷 Сканировать QR")
                    }
                    RowDivider()
                    DiscordSwitchRow(
                        label = "Бондинг (объединить сети)",
                        checked = bonding,
                        onCheckedChange = { bonding = it },
                        info = "Собирает Wi-Fi и сотовую в одну пати: пакеты бегут по всем сетям сразу, " +
                            "и если одна прилегла отдохнуть — остальные тащат.\n\n" +
                            "Когда включать:\n" +
                            "• стрим на ходу, где сеть скачет\n" +
                            "• есть две-три сети сразу (Wi-Fi + SIM)\n\n" +
                            "Видео уходит на srtla_rec, а не напрямую на SRT-порт. Дома можно и не включать.",
                    )
                    RowDivider()
                    Row {
                        DiscordField(
                            label = "Хост сервера",
                            value = host,
                            onValueChange = { host = it },
                            // хост — это IP: Decimal даёт цифровую панель с точкой
                            keyboardType = KeyboardType.Decimal,
                            isError = host.isBlank(),
                            modifier = Modifier.weight(2f),
                            info = "Один IP на всё: SRT-сервер, srtla_rec для бондинга и пульт OBS " +
                                "живут на этой машине. Меняешь тут — меняется везде.\n\n" +
                                "Что вписать:\n" +
                                "• IP компа в локалке (ipconfig → IPv4)\n" +
                                "• или адрес облака (belabox, IRLToolkit)\n\n" +
                                "Порт справа зависит от бондинга: с ним — порт srtla_rec, без — SRT-порт сервера.",
                        )
                        DiscordField(
                            label = "Порт",
                            value = if (bonding) srtlaPort else port,
                            onValueChange = { if (bonding) srtlaPort = it else port = it },
                            keyboardType = KeyboardType.Number,
                            isError = !activePortValid,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    RowDivider()
                    Row {
                        DiscordField(
                            label = "Имя стрима",
                            value = streamName,
                            onValueChange = { streamName = it },
                            isError = streamName.isBlank(),
                            modifier = Modifier.weight(1f),
                            info = "Поток приедет на сервер как live/<имя>. Плеер потом ищет его по этому же имени.\n\n" +
                                "Что вписать:\n" +
                                "• латиницей, без пробелов\n" +
                                "• «phone» скромно, «super_mega_stream_3000» тоже примем\n\n" +
                                "Главное — чтобы совпадало с тем, что ждёт сервер/плеер.",
                        )
                        DiscordField(
                            label = "Passphrase",
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            modifier = Modifier.weight(1f),
                            info = "Секретный стук в дверь — AES-шифрование SRT.\n\n" +
                                "Как выбрать:\n" +
                                "• пусто — дверь нараспашку, для своего сервера ок\n" +
                                "• задал — сервер должен знать тот же секрет\n\n" +
                                "Не совпало с сервером — он сделает вид, что впервые тебя видит.",
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
                        info = "Подушка безопасности SRT: сколько у потерянного пакета есть времени, " +
                            "чтобы досдаться повторно. Больше — стабильнее, но зритель дальше от реальности.\n\n" +
                            "Что ставить:\n" +
                            "• 2000 — золотая середина, пара секунд задержки без артефактов\n" +
                            "• больше — для слабой/дальней сети\n" +
                            "• меньше 500 — только адреналиновым наркоманам",
                    )
                    RowDivider()
                    DiscordSwitchRow(
                        label = "Пульт OBS",
                        checked = obsEnabled,
                        onCheckedChange = { obsEnabled = it },
                        info = "Панель на стрим-экране, которая командует OBS на компе: сцены, эфир, запись.\n\n" +
                            "Как включить в OBS:\n" +
                            "• Сервис → Настройка сервера WebSocket\n" +
                            "• галочка «Включить сервер WebSocket»\n" +
                            "• порт и пароль — там же, кнопка «Показать сведения о подключении»\n\n" +
                            "Хост берётся общий — тот же IP, что сверху. Выключен — ни настроек, ни иконки.",
                    )
                    if (obsEnabled) {
                        RowDivider()
                        Row {
                            DiscordField(
                                label = "Порт OBS",
                                value = obsPort,
                                onValueChange = { obsPort = it },
                                keyboardType = KeyboardType.Number,
                                isError = !obsPortValid,
                                modifier = Modifier.weight(1f),
                            )
                            DiscordField(
                                label = "Пароль WebSocket",
                                value = obsPassword,
                                onValueChange = { obsPassword = it },
                                modifier = Modifier.weight(2f),
                                info = "Тот же пароль, что в OBS.\n\n" +
                                    "Где взять:\n" +
                                    "• Сервис → Настройка сервера WebSocket → «Пароль сервера»\n" +
                                    "• или кнопка «Показать сведения о подключении»\n\n" +
                                    "Галочка «Включить аутентификацию» снята — оставь пустым.",
                            )
                        }
                    }
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
                        info = "Сколько килобит в секунду тратим на красоту. H.265 умеет в магию сжатия, " +
                            "так что задирать не нужно: выше рекомендации картинка почти не хорошеет, " +
                            "зато телефон греется и роняет кадры.\n\n" +
                            "Как выбрать:\n" +
                            "• жми пресет ниже — мы уже посчитали\n" +
                            "• комната статичная — прощает низкий битрейт\n" +
                            "• улица с листвой и движением — самый прожорливый контент",
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
                                    srtlaHost = host.trim(),
                                    srtlaPort = srtlaPortInt ?: 5000,
                                    obsEnabled = obsEnabled,
                                    obsHost = host.trim(),
                                    obsPort = obsPortInt ?: 4455,
                                    obsPassword = obsPassword,
                                    // чат настраивается на стрим-экране; тут просто не теряем значения
                                    chatEnabled = initial.chatEnabled,
                                    chatChannel = initial.chatChannel,
                                    chatShowNicknames = initial.chatShowNicknames,
                                    chatShowBadges = initial.chatShowBadges,
                                    chatHideCommands = initial.chatHideCommands,
                                    chatFontSizeSp = initial.chatFontSizeSp,
                                    chatOpacityPercent = initial.chatOpacityPercent,
                                    chatMessageLimit = initial.chatMessageLimit,
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
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) DiscordColors.accent else DiscordColors.inputBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
