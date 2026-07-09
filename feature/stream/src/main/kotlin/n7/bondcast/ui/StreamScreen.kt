package n7.bondcast.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.PowerManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.NightModeIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import n7.bondcast.DiscordColors
import n7.bondcast.camerax.CameraControlBus
import n7.bondcast.camerax.CameraXPreviewBus
import n7.bondcast.camerax.setAeAwbLock
import n7.bondcast.camerax.setLowLightBoost
import n7.bondcast.obs.ObsController
import n7.bondcast.obs.ObsPhase
import n7.bondcast.settings.StreamSettings
import n7.bondcast.stream.HealthLevel
import n7.bondcast.stream.StreamController
import n7.bondcast.stream.StreamPhase
import n7.bondcast.stream.USB_CAMERA_ID
import n7.bondcast.thermal.ThermalMitigations
import n7.bondcast.thermal.ThermalMonitor
import n7.bondcast.thermal.ThermalState
import n7.bondcast.ui.components.AttentionLevel
import n7.bondcast.ui.components.CameraIcon
import n7.bondcast.ui.components.CameraPanel
import n7.bondcast.ui.components.FlameIcon
import n7.bondcast.ui.components.GearIcon
import n7.bondcast.ui.components.GoLiveButton
import n7.bondcast.ui.components.ObsIcon
import n7.bondcast.ui.components.ObsPanel
import n7.bondcast.ui.components.RailButton
import n7.bondcast.ui.components.RailFadeColumn
import n7.bondcast.ui.components.RailGroupDivider
import n7.bondcast.ui.components.StatsIcon
import n7.bondcast.ui.components.StatusDot
import n7.bondcast.ui.components.StickerBadge
import n7.bondcast.ui.components.ThermalPanel
import n7.bondcast.ui.components.healthColor
import n7.bondcast.ui.street.StreetChip
import n7.bondcast.ui.street.StreetPanelScaffold
import n7.bondcast.ui.street.StreetStatCard
import n7.bondcast.ui.street.streetLabel
import n7.bondcast.ui.street.upper
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
    obsController: ObsController,
) {
    val phase by controller.phase.collectAsState()
    val currentCamera by controller.currentCamera.collectAsState()
    val cameras by controller.cameras.collectAsState()
    val health by controller.health.collectAsState()
    val panels = remember { PanelManager() }

    val thermalFlow = remember(thermalMonitor) { thermalMonitor.states() }
    val thermalState by thermalFlow.collectAsState(initial = ThermalState.UNKNOWN)
    val previewEnabled by mitigations.previewEnabled.collectAsState()

    // управление CameraX (стабилизация/AE-AWB/LLB/зум) — недоступно для USB-камеры
    val camera by CameraControlBus.camera.collectAsState()
    val stabilizationWanted by CameraControlBus.stabilizationWanted.collectAsState()
    val stabilizationActive by CameraControlBus.stabilizationActive.collectAsState()
    val stabilizationSupported by CameraControlBus.stabilizationSupported.collectAsState()
    var aeAwbLocked by remember { mutableStateOf(false) }
    var llbEnabled by remember { mutableStateOf(false) }
    val cameraControlsAvailable = currentCamera?.id != USB_CAMERA_ID && camera != null
    val llbAvailable = camera?.cameraInfo?.isLowLightBoostSupported() == true
    LaunchedEffect(camera, aeAwbLocked) { camera?.let { setAeAwbLock(it, aeAwbLocked) } }
    LaunchedEffect(camera, llbEnabled) { camera?.let { setLowLightBoost(it, llbEnabled) } }

    // экспозиция: индекс сбрасывается на новой камере (после переключения/ребайнда), поэтому переприменяем при каждой смене camera
    var exposureIndex by remember { mutableStateOf(0) }
    val exposureState = camera?.cameraInfo?.exposureState
    val exposureSupported = exposureState?.isExposureCompensationSupported() == true
    val exposureRange = exposureState?.exposureCompensationRange?.let { it.lower..it.upper } ?: 0..0
    val exposureStepEv = exposureState?.exposureCompensationStep?.toFloat() ?: 0f
    LaunchedEffect(camera, exposureIndex) {
        camera?.cameraControl?.setExposureCompensationIndex(exposureIndex.coerceIn(exposureRange))
    }

    // «сцена тёмная — включить ночной режим?» — LiveData без доп. зависимости на lifecycle-livedata-ktx
    var nightModeIndicator by remember { mutableStateOf(NightModeIndicator.UNKNOWN) }
    DisposableEffect(camera) {
        val liveData = camera?.cameraInfo?.nightModeIndicator
        val observer = Observer<Int> { nightModeIndicator = it }
        liveData?.observeForever(observer)
        onDispose { liveData?.removeObserver(observer) }
    }
    val nightModeSuggested = nightModeIndicator == NightModeIndicator.RECOMMENDED && !llbEnabled

    val brightness by mitigations.screenBrightness.collectAsState()
    val bitrateCap by mitigations.bitrateCapFraction.collectAsState()
    val effectiveBitrate by controller.videoBitrateKbps.collectAsState()

    val obsConfigured = settings?.obsEnabled == true && settings.obsHost.isNotBlank()
    val obsPhase by obsController.phase.collectAsState()
    // соединение живёт, только пока окно открыто; isOpen ловит и ✕, и скрим, и вытеснение другим окном
    val obsOpen = panels.isOpen(PANEL_OBS)
    LaunchedEffect(obsOpen) { obsController.setPanelVisible(obsOpen) }
    DisposableEffect(obsController) {
        onDispose { obsController.setPanelVisible(false) }
    }

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

    LaunchedEffect(settings) {
        val current = settings ?: return@LaunchedEffect
        controller.engine.prepare(current)
    }

    val statsAttention = when (health?.overall) {
        HealthLevel.BAD -> AttentionLevel.BAD
        HealthLevel.WARN -> AttentionLevel.WARN
        else -> AttentionLevel.NONE
    }
    val thermalAttention = when {
        thermalState.status >= PowerManager.THERMAL_STATUS_SEVERE || thermalState.heat >= 0.95f -> AttentionLevel.BAD
        thermalState.status >= PowerManager.THERMAL_STATUS_MODERATE || thermalState.heat >= 0.65f -> AttentionLevel.WARN
        else -> AttentionLevel.NONE
    }
    val obsAttention = when (obsPhase) {
        ObsPhase.AuthFailed -> AttentionLevel.BAD
        is ObsPhase.Retrying -> AttentionLevel.WARN
        else -> AttentionLevel.NONE
    }
    val live = phase is StreamPhase.Live
    val flameFlicker = when (thermalAttention) {
        AttentionLevel.BAD -> 1f
        AttentionLevel.WARN -> 0.6f
        else -> 0.28f
    }
    val statsAgitation = when (statsAttention) {
        AttentionLevel.BAD -> 1f
        AttentionLevel.WARN -> 0.55f
        else -> 0.25f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (currentCamera?.id == USB_CAMERA_ID && previewEnabled) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = UvcPreviewBus.set(holder.surface)

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = UvcPreviewBus.set(holder.surface)

                            override fun surfaceDestroyed(holder: SurfaceHolder) = UvcPreviewBus.set(null)
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (currentCamera?.id != USB_CAMERA_ID && previewEnabled) {
            val surfaceRequest by CameraXPreviewBus.request.collectAsState()
            DisposableEffect(Unit) {
                CameraXPreviewBus.setWantPreview(true)
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose {
                    CameraXPreviewBus.setWantPreview(false)
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            surfaceRequest?.let { request ->
                CameraXViewfinder(
                    surfaceRequest = request,
                    modifier = Modifier.fillMaxSize(),
                    // встроенные жесты вьюфайндера (camera-compose 1.7) — учитывают sensor-to-buffer
                    // трансформ (crop/поворот/зеркало), в отличие от нашей прежней ручной обвязки
                    isTapToFocusEnabled = true,
                    isPinchToZoomEnabled = true,
                )
            }
        }

        if (!previewEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        }

        // скрим лежит ПОД рейлом и панелями: тап мимо закрывает окно,
        // а иконки справа остаются кликабельными для переключения панелей
        if (panels.anyOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { panels.closeAll() },
            )
        }

        StickerBadge(
            phase = phase,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        )

        val streaming = phase !is StreamPhase.Idle
        GoLiveButton(
            streaming = streaming,
            onClick = { if (streaming) controller.stop() else controller.start() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 20.dp),
        )

        RailFadeColumn(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 4.dp, end = 16.dp, bottom = 60.dp)
                .fillMaxHeight(),
        ) {
            var index = 0
            if (cameras.size >= 2) {
                val camActive = panels.isOpen(PANEL_CAMERAS)
                RailButton(
                    active = camActive,
                    onClick = { panels.toggle(PANEL_CAMERAS) },
                    appearDelay = index++ * 45L,
                ) {
                    CameraIcon(
                        color = glyphColor(camActive),
                        showBadge = nightModeSuggested,
                        flipKey = currentCamera?.id,
                    )
                }
            }
            val statsActive = panels.isOpen(PANEL_STATS)
            RailButton(
                active = statsActive,
                onClick = { panels.toggle(PANEL_STATS) },
                attention = statsAttention,
                appearDelay = index++ * 45L,
            ) {
                StatsIcon(color = glyphColor(statsActive), live = live, agitation = statsAgitation)
            }
            RailGroupDivider()
            val thermalActive = panels.isOpen(PANEL_THERMAL)
            RailButton(
                active = thermalActive,
                onClick = { panels.toggle(PANEL_THERMAL) },
                attention = thermalAttention,
                appearDelay = index++ * 45L,
            ) {
                FlameIcon(color = glyphColor(thermalActive), flicker = flameFlicker)
            }
            if (obsConfigured) {
                val obsActive = panels.isOpen(PANEL_OBS)
                RailButton(
                    active = obsActive,
                    onClick = { panels.toggle(PANEL_OBS) },
                    attention = obsAttention,
                    appearDelay = index++ * 45L,
                ) {
                    ObsIcon(color = glyphColor(obsActive))
                }
            }
            RailButton(
                active = false,
                onClick = onOpenSettings,
                enabled = phase is StreamPhase.Idle,
                appearDelay = index++ * 45L,
            ) {
                GearIcon(color = if (phase is StreamPhase.Idle) DiscordColors.accent else DiscordColors.textMuted)
            }
        }

        PanelSlot(panels.isOpen(PANEL_STATS)) {
            StatsPanel(
                controller = controller,
                settings = settings,
                phase = phase,
                bitrateCap = bitrateCap,
                mitigations = mitigations,
                onUpdateSettings = onUpdateSettings,
                onHelp = { panels.toggle(PANEL_STATS_HELP) },
                onClose = { panels.close(PANEL_STATS) },
            )
        }

        PanelSlot(panels.isOpen(PANEL_THERMAL)) {
            ThermalPanel(
                state = thermalState,
                effectiveBitrateKbps = if (effectiveBitrate > 0) effectiveBitrate else settings?.videoBitrateKbps ?: 0,
                brightness = brightness,
                onBrightness = { mitigations.setScreenBrightness(it) },
                bitrateCapFraction = bitrateCap,
                showHints = settings?.hintsEnabled != false,
                onOpenCameras = { panels.open(PANEL_CAMERAS) },
                onOpenStats = { panels.open(PANEL_STATS) },
                onClose = { panels.close(PANEL_THERMAL) },
            )
        }

        PanelSlot(panels.isOpen(PANEL_CAMERAS)) {
            CameraPanel(
                cameras = cameras,
                current = currentCamera,
                onSelect = {
                    controller.selectCamera(it)
                },
                previewEnabled = previewEnabled,
                onPreviewEnabled = { mitigations.setPreviewEnabled(it) },
                showHint = settings?.hintsEnabled != false,
                onClose = { panels.close(PANEL_CAMERAS) },
                cameraControlsAvailable = cameraControlsAvailable,
                stabilizationSupported = stabilizationSupported,
                stabilizationEnabled = stabilizationWanted,
                stabilizationActive = stabilizationActive,
                onStabilizationEnabled = { CameraControlBus.setStabilizationWanted(it) },
                aeAwbLocked = aeAwbLocked,
                onAeAwbLocked = { aeAwbLocked = it },
                exposureSupported = exposureSupported,
                exposureIndex = exposureIndex,
                exposureRange = exposureRange,
                exposureStepEv = exposureStepEv,
                onExposureIndexChange = { exposureIndex = it },
                llbAvailable = llbAvailable,
                llbEnabled = llbEnabled,
                onLlbEnabled = { llbEnabled = it },
                nightModeSuggested = nightModeSuggested,
            )
        }

        PanelSlot(panels.isOpen(PANEL_OBS)) {
            val obsScenes by obsController.scenes.collectAsState()
            val obsCurrentScene by obsController.currentScene.collectAsState()
            val obsStreamStatus by obsController.streamStatus.collectAsState()
            val obsRecordStatus by obsController.recordStatus.collectAsState()
            val obsStats by obsController.stats.collectAsState()
            ObsPanel(
                phase = obsPhase,
                scenes = obsScenes,
                currentScene = obsCurrentScene,
                streamStatus = obsStreamStatus,
                recordStatus = obsRecordStatus,
                stats = obsStats,
                onSelectScene = { obsController.selectScene(it) },
                onToggleStream = { obsController.toggleStream() },
                onToggleRecord = { obsController.toggleRecord() },
                showHints = settings?.hintsEnabled != false,
                onClose = { panels.close(PANEL_OBS) },
            )
        }

        PanelSlot(panels.isOpen(PANEL_STATS_HELP)) {
            InfoPanel(
                title = "Параметры потока",
                text = STATS_HELP,
                onClose = { panels.close(PANEL_STATS_HELP) },
            )
        }
    }
}

private fun glyphColor(active: Boolean): Color = if (active) Color.White else DiscordColors.accent

private const val PANEL_STATS = "stats"
private const val PANEL_THERMAL = "thermal"
private const val PANEL_CAMERAS = "cameras"
private const val PANEL_STATS_HELP = "stats_help"
private const val PANEL_OBS = "obs"

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
private fun StatsPanel(
    controller: StreamController,
    settings: StreamSettings?,
    phase: StreamPhase,
    bitrateCap: Float?,
    mitigations: ThermalMitigations,
    onUpdateSettings: (StreamSettings) -> Unit,
    onHelp: () -> Unit,
    onClose: () -> Unit,
) {
    StreetPanelScaffold(title = "Статистика", onClose = onClose) {
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
        HudStats(controller, onHelp = onHelp)
        LinksPanel(controller)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Потолок битрейта".upper(),
            color = DiscordColors.accent,
            style = streetLabel,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CapChip("Макс", null, bitrateCap) { mitigations.setBitrateCapFraction(it) }
            CapChip("75%", 0.75f, bitrateCap) { mitigations.setBitrateCapFraction(it) }
            CapChip("50%", 0.5f, bitrateCap) { mitigations.setBitrateCapFraction(it) }
            CapChip("25%", 0.25f, bitrateCap) { mitigations.setBitrateCapFraction(it) }
        }
        if (settings?.hintsEnabled != false) {
            Text(
                text = "Ручник для энкодера: 50% — это половина максимума.\n" +
                    "Прижал — телефон остывает, картинка чуть мылит, зато эфир живёт 🧯",
                color = DiscordColors.textMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (settings != null) {
            // ABR применяется на старте сессии, поэтому в эфире переключение заперто
            val live = phase !is StreamPhase.Idle
            Text(
                text = "Адаптивный битрейт (ABR)".upper(),
                color = DiscordColors.accent,
                style = streetLabel,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StreetChip("Вкл", settings.abrEnabled, Modifier.weight(1f), enabled = !live) {
                    onUpdateSettings(settings.copy(abrEnabled = true))
                }
                StreetChip("Выкл", !settings.abrEnabled, Modifier.weight(1f), enabled = !live) {
                    onUpdateSettings(settings.copy(abrEnabled = false))
                }
            }
            if (settings.abrEnabled) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StreetChip("−", false, Modifier.weight(1f), enabled = !live) {
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
                    StreetChip("+", false, Modifier.weight(1f), enabled = !live) {
                        onUpdateSettings(
                            settings.copy(
                                minVideoBitrateKbps = (settings.minVideoBitrateKbps + 100)
                                    .coerceAtMost(settings.videoBitrateKbps),
                            ),
                        )
                    }
                }
            }
            // подпись про блокировку в эфире — функциональная, живёт и без подсказок
            if (live || settings.hintsEnabled) {
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

@Composable
private fun HudStats(controller: StreamController, onHelp: () -> Unit) {
    val stats by controller.stats.collectAsState()
    val health by controller.health.collectAsState()
    val bitrate by controller.videoBitrateKbps.collectAsState()
    // вне эфира данных нет — показываем полную раскладку с прочерками,
    // чтобы панель сразу выглядела так, какой будет в эфире
    val s = stats
    val h = health
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = {},
            onLongClick = onHelp,
        ),
    ) {
        StreetStatCard(
            label = "Исходящий битрейт",
            value = s?.sendRateKbps?.toString() ?: "—",
            unit = "kbps",
            sub = if (bitrate > 0) "цель $bitrate kbps" else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetStatCard("RTT", s?.rttMs?.toString() ?: "—", Modifier.weight(1f), unit = "мс", labelColor = healthColor(h?.rttLevel))
            StreetStatCard("Буфер", s?.sndBufferMs?.toString() ?: "—", Modifier.weight(1f), unit = "мс", labelColor = healthColor(h?.bufLevel))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetStatCard("Потери", h?.lossPerSec?.toString() ?: "—", Modifier.weight(1f), unit = "/с", labelColor = healthColor(h?.lossLevel))
            StreetStatCard("Дропы", h?.dropPerSec?.toString() ?: "—", Modifier.weight(1f), unit = "/с", labelColor = healthColor(h?.dropLevel))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetStatCard("Ретр", h?.retransPerSec?.toString() ?: "—", Modifier.weight(1f), unit = "/с", labelColor = healthColor(h?.retransLevel))
            StreetStatCard("Полоса", s?.bandwidthKbps?.toString() ?: "—", Modifier.weight(1f), unit = "kbps")
        }
        StreetStatCard(
            label = "Энкодер",
            value = s?.let { formatLag(it.encoderLagMs) } ?: "—",
            modifier = Modifier.fillMaxWidth(),
            labelColor = healthColor(h?.encoderLevel),
        )
    }
}

@Composable
private fun LinksPanel(controller: StreamController) {
    val links by controller.links.collectAsState()
    if (links.isEmpty()) return
    val totalKbps = links.sumOf { it.sendRateKbps }.coerceAtLeast(1)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.width(60.dp),
                )
                Text(
                    text = formatRate(link.sendRateKbps),
                    style = MaterialTheme.typography.bodySmall,
                    color = DiscordColors.textPrimary,
                    modifier = Modifier.width(72.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DiscordColors.plate),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((link.sendRateKbps.toFloat() / totalKbps).coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(DiscordColors.accent, RoundedCornerShape(2.dp)),
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

private fun formatRate(kbps: Int): String = if (kbps >= 1000) "${kbps / 1000}.${kbps % 1000 / 100} Mbps" else "$kbps kbps"

private fun formatLag(ms: Int): String = if (ms >= 1000) "+${ms / 1000}.${ms % 1000 / 100}с" else "${ms}мс"

@Composable
private fun RowScope.CapChip(
    label: String,
    value: Float?,
    selected: Float?,
    onSelect: (Float?) -> Unit,
) {
    StreetChip(label, selected == value, Modifier.weight(1f)) { onSelect(value) }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
