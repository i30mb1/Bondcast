package n7.bondcast.ui

import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import n7.bondcast.DiscordColors
import n7.bondcast.feature.settings.R
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
    twitchLoggedIn: Boolean = false,
    onTwitchLogin: (() -> Unit)? = null,
    onTwitchLogout: (() -> Unit)? = null,
    onFetchTwitchStreamKey: (suspend () -> String?)? = null,
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
    var twitchDirect by remember { mutableStateOf(initial.twitchDirectEnabled) }
    var twitchStreamKey by remember { mutableStateOf(initial.twitchStreamKey) }
    var twitchIngestUrl by remember { mutableStateOf(initial.twitchIngestUrl) }
    var twitchFetching by remember { mutableStateOf(false) }
    var twitchFetchError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var showScanner by remember { mutableStateOf(false) }
    var showAdvanced by remember {
        mutableStateOf(
            initial.bondingEnabled || initial.obsEnabled || initial.passphrase.isNotBlank() ||
                initial.latencyMs != 2000 || initial.fps >= 60 ||
                initial.twitchIngestUrl != StreamSettings().twitchIngestUrl,
        )
    }
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
    // пульт выключен (или недоступен в режиме Twitch-напрямую) — его поля скрыты и не проверяются
    val obsPortValid = obsPortInt != null && obsPortInt in 1..65535
    val obsValid = twitchDirect || !obsEnabled || obsPortValid
    // Twitch напрямую — валиден просто по ключу трансляции, свой сервер требует хост+порт+имя
    val destinationValid = if (twitchDirect) twitchStreamKey.isNotBlank() else host.isNotBlank() && activePortValid
    val nameValid = twitchDirect || streamName.isNotBlank()
    val valid = nameValid && bitrateValid && latencyValid && destinationValid && obsValid

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
                        payload.obsPort?.let {
                            obsPort = it.toString()
                            obsEnabled = true
                        }
                        payload.obsPassword?.let {
                            obsPassword = it
                            obsEnabled = true
                        }
                    }

                    is QrPayload.Unknown ->
                        Toast.makeText(context, context.getString(R.string.settings_qr_unrecognized), Toast.LENGTH_SHORT).show()
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
            DiscordTopBar(title = stringResource(R.string.settings_title), onBack = onBack)

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionLabel(stringResource(R.string.settings_section_server))
                SettingsCard {
                    Button(
                        onClick = { showScanner = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.settings_scan_qr_button))
                    }
                    RowDivider()
                    DiscordSwitchRow(
                        label = stringResource(R.string.settings_twitch_direct_label),
                        checked = twitchDirect,
                        onCheckedChange = { twitchDirect = it },
                        info = stringResource(R.string.settings_twitch_direct_info),
                    )
                    if (twitchDirect) {
                        RowDivider()
                        DiscordField(
                            label = stringResource(R.string.settings_twitch_stream_key_label),
                            value = twitchStreamKey,
                            onValueChange = {
                                twitchStreamKey = it
                                twitchFetchError = null
                            },
                            isError = twitchStreamKey.isBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            info = stringResource(R.string.settings_twitch_stream_key_info),
                        )
                        if (onTwitchLogin != null && onTwitchLogout != null || onFetchTwitchStreamKey != null) {
                            val fetchErrorRelogin = stringResource(R.string.settings_twitch_fetch_error_relogin)
                            val fetchErrorLoginFirst = stringResource(R.string.settings_twitch_fetch_error_login_first)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (onTwitchLogin != null && onTwitchLogout != null) {
                                    TextButton(
                                        onClick = { if (twitchLoggedIn) onTwitchLogout() else onTwitchLogin() },
                                    ) {
                                        Text(
                                            stringResource(
                                                if (twitchLoggedIn) {
                                                    R.string.settings_twitch_logout_button
                                                } else {
                                                    R.string.settings_twitch_login_button
                                                },
                                            ),
                                        )
                                    }
                                }
                                if (onFetchTwitchStreamKey != null) {
                                    TextButton(
                                        enabled = !twitchFetching,
                                        onClick = {
                                            twitchFetchError = null
                                            twitchFetching = true
                                            coroutineScope.launch {
                                                val key = runCatching { onFetchTwitchStreamKey() }.getOrNull()
                                                twitchFetching = false
                                                if (key.isNullOrBlank()) {
                                                    twitchFetchError = if (twitchLoggedIn) {
                                                        fetchErrorRelogin
                                                    } else {
                                                        fetchErrorLoginFirst
                                                    }
                                                } else {
                                                    twitchStreamKey = key
                                                }
                                            }
                                        },
                                    ) {
                                        Text(
                                            stringResource(
                                                if (twitchFetching) {
                                                    R.string.settings_twitch_fetch_key_button_loading
                                                } else {
                                                    R.string.settings_twitch_fetch_key_button
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
                            twitchFetchError?.let {
                                Text(
                                    text = it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                    } else {
                        RowDivider()
                        Row {
                            DiscordField(
                                label = stringResource(R.string.settings_host_label),
                                value = host,
                                onValueChange = { host = it },
                                // хост — это IP: Decimal даёт цифровую панель с точкой
                                keyboardType = KeyboardType.Decimal,
                                isError = host.isBlank(),
                                modifier = Modifier.weight(2f),
                                info = stringResource(R.string.settings_host_info),
                            )
                            DiscordField(
                                label = stringResource(R.string.settings_port_label),
                                value = if (bonding) srtlaPort else port,
                                onValueChange = { if (bonding) srtlaPort = it else port = it },
                                keyboardType = KeyboardType.Number,
                                isError = !activePortValid,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        RowDivider()
                        DiscordField(
                            label = stringResource(R.string.settings_stream_name_label),
                            value = streamName,
                            onValueChange = { streamName = it },
                            isError = streamName.isBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            info = stringResource(R.string.settings_stream_name_info),
                        )
                    }
                }

                SectionLabel(stringResource(R.string.settings_section_video))
                SettingsCard {
                    DiscordSegmentedRow(
                        label = stringResource(R.string.settings_resolution_label),
                        options = listOf("720p", "1080p"),
                        selectedIndex = if (is1080p) 1 else 0,
                        onSelect = { is1080p = it == 1 },
                    )
                    RowDivider()
                    DiscordStepperField(
                        label = stringResource(R.string.settings_bitrate_label),
                        value = bitrate,
                        onValueChange = { bitrate = it },
                        min = 500,
                        max = 20_000,
                        step = 100,
                        isError = !bitrateValid,
                        info = stringResource(R.string.settings_bitrate_info),
                    )
                    DiscordHint(
                        stringResource(
                            R.string.settings_bitrate_presets_hint,
                            if (is1080p) "1080p" else "720p",
                            if (is60fps) "60" else "30",
                        ),
                    )
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RecommendChip(
                            text = stringResource(R.string.settings_preset_indoor, recIndoorKbps),
                            selected = bitrateInt == recIndoorKbps,
                            onClick = { bitrate = recIndoorKbps.toString() },
                        )
                        RecommendChip(
                            text = stringResource(R.string.settings_preset_outdoor, recOutdoorKbps),
                            selected = bitrateInt == recOutdoorKbps,
                            onClick = { bitrate = recOutdoorKbps.toString() },
                        )
                    }
                }

                AdvancedToggle(
                    expanded = showAdvanced,
                    onClick = { showAdvanced = !showAdvanced },
                )

                AnimatedVisibility(
                    visible = showAdvanced,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!twitchDirect) {
                            SectionLabel(stringResource(R.string.settings_section_connection))
                            SettingsCard {
                                DiscordSwitchRow(
                                    label = stringResource(R.string.settings_bonding_label),
                                    checked = bonding,
                                    onCheckedChange = { bonding = it },
                                    info = stringResource(R.string.settings_bonding_info),
                                )
                                RowDivider()
                                DiscordField(
                                    label = stringResource(R.string.settings_passphrase_label),
                                    value = passphrase,
                                    onValueChange = { passphrase = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    info = stringResource(R.string.settings_passphrase_info),
                                )
                                RowDivider()
                                DiscordStepperField(
                                    label = stringResource(R.string.settings_latency_label),
                                    value = latency,
                                    onValueChange = { latency = it },
                                    min = 20,
                                    max = 8_000,
                                    step = 100,
                                    isError = !latencyValid,
                                    info = stringResource(R.string.settings_latency_info),
                                )
                            }
                        } else {
                            SectionLabel(stringResource(R.string.settings_section_twitch_extra))
                            SettingsCard {
                                DiscordField(
                                    label = stringResource(R.string.settings_ingest_url_label),
                                    value = twitchIngestUrl,
                                    onValueChange = { twitchIngestUrl = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    info = stringResource(R.string.settings_ingest_url_info),
                                )
                            }
                        }

                        SectionLabel(stringResource(R.string.settings_section_video_extra))
                        SettingsCard {
                            DiscordSegmentedRow(
                                label = stringResource(R.string.settings_fps_label),
                                options = listOf("30 fps", "60 fps"),
                                selectedIndex = if (is60fps) 1 else 0,
                                onSelect = { is60fps = it == 1 },
                            )
                        }

                        // пульт OBS предполагает свою машину-сервер рядом — со стримом напрямую в Twitch не сочетается
                        if (!twitchDirect) {
                            SectionLabel(stringResource(R.string.settings_section_obs))
                            SettingsCard {
                                DiscordSwitchRow(
                                    label = stringResource(R.string.settings_obs_toggle_label),
                                    checked = obsEnabled,
                                    onCheckedChange = { obsEnabled = it },
                                    info = stringResource(R.string.settings_obs_info),
                                )
                                if (obsEnabled) {
                                    RowDivider()
                                    Row {
                                        DiscordField(
                                            label = stringResource(R.string.settings_obs_port_label),
                                            value = obsPort,
                                            onValueChange = { obsPort = it },
                                            keyboardType = KeyboardType.Number,
                                            isError = !obsPortValid,
                                            modifier = Modifier.weight(1f),
                                        )
                                        DiscordField(
                                            label = stringResource(R.string.settings_obs_password_label),
                                            value = obsPassword,
                                            onValueChange = { obsPassword = it },
                                            modifier = Modifier.weight(2f),
                                            info = stringResource(R.string.settings_obs_password_info),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_back_button))
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
                                    // бондинг и Twitch-напрямую взаимоисключающие — второе всегда побеждает
                                    bondingEnabled = bonding && !twitchDirect,
                                    srtlaHost = host.trim(),
                                    srtlaPort = srtlaPortInt ?: 5000,
                                    // пульт OBS не сочетается со стримом напрямую в Twitch — нет своей машины-сервера
                                    obsEnabled = obsEnabled && !twitchDirect,
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
                                    chatFadeTopEnabled = initial.chatFadeTopEnabled,
                                    chatAutoEraseEnabled = initial.chatAutoEraseEnabled,
                                    onboardingCompleted = initial.onboardingCompleted,
                                    twitchDirectEnabled = twitchDirect,
                                    twitchStreamKey = twitchStreamKey.trim(),
                                    twitchIngestUrl = twitchIngestUrl.trim().ifBlank { initial.twitchIngestUrl },
                                ),
                            )
                        },
                        enabled = valid,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.settings_save_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedToggle(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = stringResource(if (expanded) R.string.settings_advanced_hide else R.string.settings_advanced_show),
        color = DiscordColors.accent,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
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
