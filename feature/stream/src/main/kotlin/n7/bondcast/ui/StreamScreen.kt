package n7.bondcast.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.delay
import n7.bondcast.DiscordColors
import n7.bondcast.settings.StreamSettings
import n7.bondcast.stream.HealthLevel
import n7.bondcast.stream.StreamController
import n7.bondcast.stream.StreamPhase
import n7.bondcast.stream.USB_CAMERA_ID
import n7.bondcast.thermal.ThermalMitigations
import n7.bondcast.thermal.ThermalMonitor
import n7.bondcast.thermal.ThermalState
import n7.bondcast.ui.components.CameraIcon
import n7.bondcast.ui.components.CameraPanel
import n7.bondcast.ui.components.FlameIcon
import n7.bondcast.ui.components.StatsIcon
import n7.bondcast.ui.components.StatusDot
import n7.bondcast.ui.components.ThermalPanel
import n7.bondcast.ui.components.healthColor
import n7.bondcast.uvc.UvcPreviewBus
import n7.srtla.scheduler.RegState
import n7.srtla.scheduler.Transport

@Composable
public fun StreamScreen(
    controller: StreamController,
    settings: StreamSettings?,
    onOpenSettings: () -> Unit,
    onUpdateSettings: (StreamSettings) -> Unit,
    thermalMonitor: ThermalMonitor,
    mitigations: ThermalMitigations,
) {
    val phase by controller.phase.collectAsState()
    val currentCamera by controller.currentCamera.collectAsState()
    val cameras by controller.cameras.collectAsState()
    val health by controller.health.collectAsState()
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    // карточка статистики — обычное окно менеджера, открыта с самого начала
    val overlays = remember { OverlayManager().apply { toggle(PANEL_STATS) } }

    val thermalFlow = remember(thermalMonitor) { thermalMonitor.states() }
    val thermalState by thermalFlow.collectAsState(initial = ThermalState.UNKNOWN)
    val previewEnabled by mitigations.previewEnabled.collectAsState()
    val brightness by mitigations.screenBrightness.collectAsState()
    val bitrateCap by mitigations.bitrateCapFraction.collectAsState()
    val effectiveBitrate by controller.videoBitrateKbps.collectAsState()

    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }
    LaunchedEffect(brightness, window) {
        val target = window ?: return@LaunchedEffect
        target.attributes = target.attributes.apply {
            screenBrightness = brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
    DisposableEffect(window) {
        onDispose {
            window?.let {
                it.attributes = it.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

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

        if (currentCamera?.id == USB_CAMERA_ID && previewEnabled) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) =
                                UvcPreviewBus.set(holder.surface)

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                                UvcPreviewBus.set(holder.surface)

                            override fun surfaceDestroyed(holder: SurfaceHolder) =
                                UvcPreviewBus.set(null)
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!previewEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        }

        // скрим лежит ПОД кнопками и панелями: тап мимо закрывает все окна,
        // а иконки справа остаются кликабельными для переключения панелей
        if (overlays.anyOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { overlays.closeAll() },
            )
        }


        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "⚙",
                color = DiscordColors.textPrimary,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(DiscordColors.background.copy(alpha = 0.72f))
                    .clickable(enabled = phase is StreamPhase.Idle, onClick = onOpenSettings)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (cameras.size >= 2) {
                CameraIcon(
                    onClick = { overlays.toggle(PANEL_CAMERAS) },
                )
            }
            StatsIcon(
                color = healthColor(health?.overall),
                onClick = { overlays.toggle(PANEL_STATS) },
            )
            FlameIcon(
                state = thermalState,
                onClick = { overlays.toggle(PANEL_THERMAL) },
            )
        }

        val streaming = phase !is StreamPhase.Idle
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            ) {
                Text(
                    text = if (streaming) "Стоп" else "В эфир",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        overlays.regionOf(PANEL_STATS)?.let { region ->
            OverlaySlot(region) {
                Column(
                    modifier = Modifier
                        // фиксированная ширина: иначе полоски линков (fillMaxWidth) раздувают карточку
                        .width(308.dp)
                        .heightIn(max = 330.dp)
                        .background(DiscordColors.background.copy(alpha = 0.72f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            StatusLine(phase)
                        }
                        Text(
                            text = "✕",
                            color = DiscordColors.textMuted,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { overlays.close(PANEL_STATS) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
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
                    HudStats(
                        controller,
                        onHelp = { overlays.toggle(PANEL_STATS_HELP) },
                    )
                    LinksPanel(controller)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Потолок битрейта",
                        color = DiscordColors.textMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CapChip("Макс", null, bitrateCap) { mitigations.setBitrateCapFraction(it) }
                        CapChip("75%", 0.75f, bitrateCap) { mitigations.setBitrateCapFraction(it) }
                        CapChip("50%", 0.5f, bitrateCap) { mitigations.setBitrateCapFraction(it) }
                        CapChip("25%", 0.25f, bitrateCap) { mitigations.setBitrateCapFraction(it) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Ручник для энкодера: 50% — это половина максимума.\n" +
                            "Прижал — телефон остывает, картинка чуть мылит, зато эфир живёт 🧯",
                        color = DiscordColors.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (settings != null) {
                        // ABR применяется на старте сессии, поэтому в эфире переключение заперто
                        val live = phase !is StreamPhase.Idle
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Адаптивный битрейт (ABR)",
                            color = DiscordColors.textMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            BoolChip("Вкл", settings.abrEnabled, enabled = !live) {
                                onUpdateSettings(settings.copy(abrEnabled = true))
                            }
                            BoolChip("Выкл", !settings.abrEnabled, enabled = !live) {
                                onUpdateSettings(settings.copy(abrEnabled = false))
                            }
                        }
                        if (settings.abrEnabled) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                BoolChip("−", false, enabled = !live) {
                                    onUpdateSettings(
                                        settings.copy(
                                            minVideoBitrateKbps = (settings.minVideoBitrateKbps - 100).coerceAtLeast(300),
                                        ),
                                    )
                                }
                                Text(
                                    text = "мин ${settings.minVideoBitrateKbps}",
                                    color = DiscordColors.textSecondary,
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(2f),
                                )
                                BoolChip("+", false, enabled = !live) {
                                    onUpdateSettings(
                                        settings.copy(
                                            minVideoBitrateKbps = (settings.minVideoBitrateKbps + 100)
                                                .coerceAtMost(settings.videoBitrateKbps),
                                        ),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (live) {
                                "В эфире не переключается — сначала «Стоп», потом эксперименты 🙅"
                            } else {
                                "Мягко приседает качеством вместо слайд-шоу."
                            },
                            color = DiscordColors.textMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        overlays.regionOf(PANEL_THERMAL)?.let { region ->
            OverlaySlot(region) {
                ThermalPanel(
                    state = thermalState,
                    effectiveBitrateKbps = if (effectiveBitrate > 0) effectiveBitrate else settings?.videoBitrateKbps ?: 0,
                    brightness = brightness,
                    onBrightness = { mitigations.setScreenBrightness(it) },
                    bitrateCapFraction = bitrateCap,
                    onOpenCameras = { overlays.open(PANEL_CAMERAS) },
                    onOpenStats = { overlays.open(PANEL_STATS) },
                    onClose = { overlays.close(PANEL_THERMAL) },
                )
            }
        }

        overlays.regionOf(PANEL_CAMERAS)?.let { region ->
            OverlaySlot(region) {
                CameraPanel(
                    cameras = cameras,
                    current = currentCamera,
                    onSelect = {
                        controller.selectCamera(it)
                        overlays.close(PANEL_CAMERAS)
                    },
                    previewEnabled = previewEnabled,
                    onPreviewEnabled = { mitigations.setPreviewEnabled(it) },
                    onClose = { overlays.close(PANEL_CAMERAS) },
                )
            }
        }

        overlays.regionOf(PANEL_STATS_HELP)?.let { region ->
            OverlaySlot(region) {
                InfoPanel(
                    title = "Параметры потока",
                    text = STATS_HELP,
                    onClose = { overlays.close(PANEL_STATS_HELP) },
                )
            }
        }
    }
}

private const val PANEL_STATS = "stats"
private const val PANEL_THERMAL = "thermal"
private const val PANEL_CAMERAS = "cameras"
private const val PANEL_STATS_HELP = "stats_help"

private const val STATS_HELP =
    "Цвет точки: зелёная — норма, жёлтая — внимание, красная — проблема. " +
        "Цвет иконки статистики = худшая из метрик (видно и со свёрнутой панелью).\n\n" +
        "RTT — задержка до сервера (туда-обратно). Растёт → сеть далёкая или перегружена. " +
        "Помогает: сервер ближе, выше latency, сменить сеть/точку.\n\n" +
        "Буфер отправки — сколько видео скопилось на отправку. Тянется к latency → канал не успевает, " +
        "дропы вот-вот. Помогает: ниже битрейт (или ABR), выше latency.\n\n" +
        "Потери/с — пакеты, потерянные в сети; SRT досылает их ретрансмитами. " +
        "Немного на сотовой — норма, много → слабый линк.\n\n" +
        "Ретрансмиты/с — повторные отправки из-за потерь. Высокие → сеть теряет, но SRT пока вытягивает.\n\n" +
        "Дропы/с — пакеты, которые SRT ВЫБРОСИЛ, не успев доставить. Любой дроп = фриз/артефакт у зрителя. " +
        "Срочно: ниже битрейт, выше latency, включить ABR.\n\n" +
        "Полоса (SRT) — оценка ёмкости канала. Держи битрейт заметно ниже неё (примерно 70%).\n\n" +
        "Энкодер — насколько кодирование видео отстаёт от реального времени. Растёт → телефон не успевает " +
        "(перегрев или битрейт не по силам), у зрителя рывки и растущая задержка, сеть тут НЕ виновата. " +
        "Помогает: ниже битрейт (потолок в термопанели 🔥), остудить телефон, выключить превью."

@Composable
private fun HudStats(controller: StreamController, onHelp: () -> Unit) {
    val stats by controller.stats.collectAsState()
    val health by controller.health.collectAsState()
    val bitrate by controller.videoBitrateKbps.collectAsState()
    // вне эфира данных нет — показываем полную раскладку с прочерками,
    // чтобы панель сразу выглядела так, какой будет в эфире
    val s = stats
    val h = health
    Spacer(Modifier.height(6.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = onHelp,
        ),
    ) {
        Text(
            text = "↑ ${s?.sendRateKbps ?: "—"} kbps" + if (bitrate > 0) " · цель $bitrate" else "",
            style = MaterialTheme.typography.bodySmall,
            color = DiscordColors.textPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SignalCell(h?.rttLevel, "RTT ${s?.rttMs ?: "—"}мс")
            SignalCell(h?.bufLevel, "Буфер ${s?.sndBufferMs ?: "—"}мс")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SignalCell(h?.lossLevel, "Потери ${h?.lossPerSec ?: "—"}/с")
            SignalCell(h?.retransLevel, "Ретр ${h?.retransPerSec ?: "—"}/с")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SignalCell(h?.dropLevel, "Дропы ${h?.dropPerSec ?: "—"}/с")
            SignalCell(null, "Полоса ${s?.bandwidthKbps ?: "—"}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SignalCell(h?.encoderLevel, "Энкодер ${s?.let { formatLag(it.encoderLagMs) } ?: "—"}")
        }
    }
}

@Composable
private fun SignalCell(level: HealthLevel?, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.width(132.dp),
    ) {
        StatusDot(healthColor(level))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = DiscordColors.textSecondary,
        )
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

private fun formatLag(ms: Int): String =
    if (ms >= 1000) "+${ms / 1000}.${ms % 1000 / 100}с" else "${ms}мс"

@Composable
private fun RowScope.BoolChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            !enabled -> DiscordColors.textMuted
            selected -> Color.White
            else -> DiscordColors.textSecondary
        },
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected && enabled -> DiscordColors.blurple
                    selected -> DiscordColors.blurple.copy(alpha = 0.4f)
                    else -> DiscordColors.elevated
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun RowScope.CapChip(
    label: String,
    value: Float?,
    selected: Float?,
    onSelect: (Float?) -> Unit,
) {
    val active = selected == value
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) Color.White else DiscordColors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) DiscordColors.blurple else DiscordColors.elevated)
            .clickable { onSelect(value) }
            .padding(vertical = 6.dp),
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

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
