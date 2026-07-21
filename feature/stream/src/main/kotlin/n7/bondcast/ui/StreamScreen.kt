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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import n7.bondcast.chat.impl.ChatController
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
import n7.bondcast.ui.components.ChatIcon
import n7.bondcast.ui.components.ChatOverlay
import n7.bondcast.ui.components.ChatPanel
import n7.bondcast.ui.components.ConnectionHealthChip
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
import n7.bondcast.ui.street.StreetSectionLabel
import n7.bondcast.ui.street.StreetStatCard
import n7.bondcast.uvc.UvcPreviewBus
import n7.srtla.scheduler.RegState
import n7.srtla.scheduler.Transport
import kotlin.math.roundToInt

@Composable
public fun StreamScreen(
    controller: StreamController,
    settings: StreamSettings?,
    onOpenSettings: () -> Unit,
    onUpdateSettings: (StreamSettings) -> Unit,
    thermalMonitor: ThermalMonitor,
    mitigations: ThermalMitigations,
    obsController: ObsController,
    chatController: ChatController,
    twitchStatus: String,
    twitchLoggedIn: Boolean,
    onTwitchLogin: () -> Unit,
    onTwitchLogout: () -> Unit,
) {
    val phase by controller.phase.collectAsState()
    val currentCamera by controller.currentCamera.collectAsState()
    val cameras by controller.cameras.collectAsState()
    // health тикает 1Гц в эфире — читаем его только внутри derivedStateOf, чтобы корень экрана
    // рекомпозился на смену уровня внимания, а не на каждый тик статистики
    val healthState = controller.health.collectAsState()
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

    val obsConfigured = settings?.obsEnabled == true && settings.obsHost.isNotBlank()
    val obsPhase by obsController.phase.collectAsState()
    // соединение живёт, только пока окно открыто; isOpen ловит и ✕, и скрим, и вытеснение другим окном
    val obsOpen = panels.isOpen(PANEL_OBS)
    LaunchedEffect(obsOpen) { obsController.setPanelVisible(obsOpen) }
    DisposableEffect(obsController) {
        onDispose { obsController.setPanelVisible(false) }
    }

    // чат — оверлей слева (когда включён), настраивается меню справа (chatMenuOpen);
    // и то и другое живёт мимо PanelManager, поэтому не гасит панели и не трогает камеру
    val chatOn = settings?.chatEnabled == true
    var chatMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(chatOn) { chatController.setActive(chatOn) }
    DisposableEffect(chatController) { onDispose { chatController.setActive(false) } }
    val chatMessages by chatController.messages.collectAsState()

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

    val statsAttention by remember {
        derivedStateOf {
            when (healthState.value?.overall) {
                HealthLevel.BAD -> AttentionLevel.BAD
                HealthLevel.WARN -> AttentionLevel.WARN
                else -> AttentionLevel.NONE
            }
        }
    }
    val overallHealth by remember { derivedStateOf { healthState.value?.overall } }
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

        // чат рисуется ДО панелей — левые панели открываются поверх него
        if (chatOn) {
            ChatOverlay(
                messages = chatMessages,
                showNicknames = settings?.chatShowNicknames ?: true,
                showBadges = settings?.chatShowBadges ?: true,
                fontSizeSp = settings?.chatFontSizeSp ?: 14,
                opacityPercent = settings?.chatOpacityPercent ?: 80,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .width(400.dp)
                    .fillMaxHeight(),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StickerBadge(phase = phase)
            ConnectionHealthChip(overall = overallHealth)
        }

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
                .padding(top = 24.dp, end = 8.dp, bottom = 4.dp)
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
                active = chatMenuOpen,
                onClick = { chatMenuOpen = !chatMenuOpen },
                appearDelay = index++ * 45L,
            ) {
                ChatIcon(color = glyphColor(chatMenuOpen))
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
                bitrateCap = bitrateCap,
                onClose = { panels.close(PANEL_STATS) },
            )
        }

        PanelSlot(panels.isOpen(PANEL_THERMAL)) {
            ThermalPanel(
                state = thermalState,
                brightness = brightness,
                onBrightness = { mitigations.setScreenBrightness(it) },
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
                onClose = { panels.close(PANEL_OBS) },
            )
        }

        // меню чата выезжает справа (у рейла), настраивается вживую
        AnimatedVisibility(
            visible = chatMenuOpen && settings != null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            // верх/низ как у левых панелей (safeDrawing + 16), справа отступ под рейл
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 16.dp, end = 72.dp, bottom = 16.dp),
        ) {
            settings?.let { current ->
                ChatPanel(
                    settings = current,
                    onUpdate = onUpdateSettings,
                    twitchStatus = twitchStatus,
                    twitchLoggedIn = twitchLoggedIn,
                    onTwitchLogin = onTwitchLogin,
                    onTwitchLogout = onTwitchLogout,
                    onClose = { chatMenuOpen = false },
                )
            }
        }
    }
}

private fun glyphColor(active: Boolean): Color = if (active) Color.White else DiscordColors.accent

private const val PANEL_STATS = "stats"
private const val PANEL_THERMAL = "thermal"
private const val PANEL_CAMERAS = "cameras"
private const val PANEL_OBS = "obs"

private const val INFO_BITRATE =
    "Сколько всего улетает в сеть прямо сейчас: видео + звук + служебные данные, поэтому " +
        "в норме чуть выше цели из подписи снизу (это целевой видео-битрейт). " +
        "Держится у цели или чуть выше — всё ок, про то же говорит цвет карточки.\n\n" +
        "Если просел заметно ниже цели, ищи виновного ниже:\n" +
        "• RTT и Потери растут — тормозит сеть\n" +
        "• Энкодер и Буфер растут — не тянет телефон"
private const val INFO_LATENCY =
    "Буфер приёмника SRT: сколько времени есть на досыл потерянных пакетов.\n" +
        "Больше — устойчивее к потерям, но выше задержка эфира. На слабой сотовой поднимай.\n\n" +
        "Меняется прямо в эфире — стрим коротко переподключится с новым значением."
private const val INFO_RTT =
    "Пинг до сервера и обратно, как в игре.\n" +
        "До ~120 мс — комфортно, 120–300 — сеть напряжена, больше — далеко или забита.\n\n" +
        "Что делать:\n" +
        "• поднять «Задержку» — прямо в эфире\n" +
        "• сменить сеть или точку, где ловит"
private const val INFO_BUFFER =
    "Видео, которое телефон подготовил, но сеть ещё не подтвердила. Копится, когда канал не успевает.\n" +
        "Почти пустой — хорошо. Подбирается к «Задержке» — дропы вот-вот.\n\n" +
        "Что делать:\n" +
        "• сбавить битрейт или включить ABR\n" +
        "• поднять «Задержку» — прямо в эфире"
private const val INFO_LOSS =
    "Пакеты, потерянные где-то в сети; SRT замечает это и досылает их заново (см. Ретр рядом).\n" +
        "Немного на сотовой — обычное дело, лечится само. Много — сеть слабая или перегружена.\n\n" +
        "Что делать, если много:\n" +
        "• сбавить битрейт или включить ABR\n" +
        "• сменить сеть или точку, где ловит"
private const val INFO_DROP =
    "Пакеты, которые SRT выбросил, не успев довезти — досылать было поздно, зритель их не увидит.\n" +
        "Любой дроп = фриз или квадратики в эфире.\n\n" +
        "Срочно:\n" +
        "• опустить «Макс битрейт»\n" +
        "• поднять «Задержку» — прямо в эфире\n" +
        "• включить ABR"
private const val INFO_RETRANS =
    "Сколько раз SRT досылал пакеты повторно из-за потерь (см. Потери рядом). Это защита, а не беда.\n" +
        "Немного — нормальная работа SRT. Много и постоянно — сети не хватает места под битрейт.\n\n" +
        "Что делать, если постоянно:\n" +
        "• сбавить битрейт или включить ABR\n" +
        "• сменить сеть, где посвободнее"
private const val INFO_BANDWIDTH =
    "Прикидка SRT, сколько ещё способен пропустить канал сверх того, что ты уже шлёшь. " +
        "Это ориентир, а не здоровье связи — потому и без цвета.\n\n" +
        "Как читать:\n" +
        "• держи битрейт процентов на 30 ниже неё\n" +
        "• на мобильном и в бондинге цифра дрожит — не верь ей буквально"
private const val INFO_ENCODER =
    "Насколько кодирование отстаёт от реального времени — успевает ли телефон сжимать кадры с камеры.\n" +
        "Растёт → телефон не тянет (греется или битрейт не по зубам), у зрителя рывки. Сеть тут НЕ виновата.\n\n" +
        "Что делать:\n" +
        "• опустить «Макс битрейт»\n" +
        "• остудить телефон, выключить превью\n" +
        "• снизить разрешение или fps"
private const val INFO_MAX_BITRATE =
    "Верхняя граница качества: выше этого битрейта не поднимаемся. ABR и термозащита работают ПОД ним.\n\n" +
        "Меняется прямо в эфире (шаг 500 kbps), без «Стоп»:\n" +
        "• сеть уверенно держит — подними, картинка чётче\n" +
        "• стабильно не тянет — опусти"
private const val INFO_ABR =
    "Сам подруливает битрейт под сеть на лету: канал не тянет — снижает, отпустило — поднимает " +
        "обратно к «Макс битрейт». Мягко приседает качеством вместо стоп-кадра.\n\n" +
        "Как пользоваться:\n" +
        "• вкл/выкл и «мин» меняются прямо в эфире\n" +
        "• «мин» — нижняя граница, ниже неё не режет\n" +
        "• выключишь — держит ровно «Макс битрейт»"

@Composable
private fun StatsPanel(
    controller: StreamController,
    bitrateCap: Float?,
    onClose: () -> Unit,
) {
    StreetPanelScaffold(title = "Статистика", onClose = onClose) {
        HudStats(controller)
        LinksPanel(controller)
        Spacer(Modifier.height(2.dp))
        val liveMax by controller.maxBitrateKbps.collectAsState()
        StreetSectionLabel("Макс битрейт", info = INFO_MAX_BITRATE)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            StreetChip("−", false, Modifier.weight(1f)) { controller.setMaxBitrate(liveMax - 500) }
            Text(
                text = "$liveMax kbps",
                color = DiscordColors.textSecondary,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(2f),
            )
            StreetChip("+", false, Modifier.weight(1f)) { controller.setMaxBitrate(liveMax + 500) }
        }
        if (bitrateCap != null) {
            Text(
                text = "Термозащита режет до ${(liveMax * bitrateCap).roundToInt()} kbps",
                color = DiscordColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val liveLatency by controller.latencyMs.collectAsState()
        StreetSectionLabel("Задержка", info = INFO_LATENCY)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            StreetChip("−", false, Modifier.weight(1f)) { controller.setLatency(liveLatency - 250) }
            Text(
                text = "$liveLatency мс",
                color = DiscordColors.textSecondary,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(2f),
            )
            StreetChip("+", false, Modifier.weight(1f)) { controller.setLatency(liveLatency + 250) }
        }
        val abrOn by controller.abrEnabled.collectAsState()
        val liveMin by controller.minBitrateKbps.collectAsState()
        StreetSectionLabel("Адаптивный битрейт (ABR)", info = INFO_ABR)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StreetChip("Вкл", abrOn, Modifier.weight(1f)) { controller.setAbrEnabled(true) }
            StreetChip("Выкл", !abrOn, Modifier.weight(1f)) { controller.setAbrEnabled(false) }
        }
        if (abrOn) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                StreetChip("−", false, Modifier.weight(1f)) { controller.setMinBitrate(liveMin - 100) }
                Text(
                    text = "мин $liveMin",
                    color = DiscordColors.textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(2f),
                )
                StreetChip("+", false, Modifier.weight(1f)) { controller.setMinBitrate(liveMin + 100) }
            }
        }
    }
}

@Composable
private fun HudStats(controller: StreamController) {
    val stats by controller.stats.collectAsState()
    val health by controller.health.collectAsState()
    val bitrate by controller.videoBitrateKbps.collectAsState()
    // вне эфира данных нет — показываем полную раскладку с прочерками,
    // чтобы панель сразу выглядела так, какой будет в эфире
    val s = stats
    val h = health
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StreetStatCard(
            label = "Исходящий битрейт",
            value = s?.sendRateKbps?.toString() ?: "—",
            unit = "kbps",
            sub = if (bitrate > 0) "цель $bitrate kbps" else null,
            modifier = Modifier.fillMaxWidth(),
            labelColor = healthColor(h?.rateLevel),
            info = INFO_BITRATE,
        )
        // база сети рядом: RTT — задержка, Полоса — сколько ещё есть в запасе
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetStatCard(
                "RTT",
                s?.rttMs?.toString() ?: "—",
                Modifier.weight(1f),
                unit = "мс",
                labelColor = healthColor(h?.rttLevel),
                info = INFO_RTT,
            )
            StreetStatCard(
                "Полоса",
                s?.bandwidthKbps?.toString() ?: "—",
                Modifier.weight(1f),
                unit = "kbps",
                labelColor = DiscordColors.textSecondary,
                info = INFO_BANDWIDTH,
            )
        }
        // причина рядом со следствием: Потери → Ретр их же лечит
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetStatCard(
                "Потери",
                h?.lossPerSec?.toString() ?: "—",
                Modifier.weight(1f),
                unit = "/с",
                labelColor = healthColor(h?.lossLevel),
                info = INFO_LOSS,
            )
            StreetStatCard(
                "Ретр",
                h?.retransPerSec?.toString() ?: "—",
                Modifier.weight(1f),
                unit = "/с",
                labelColor = healthColor(h?.retransLevel),
                info = INFO_RETRANS,
            )
        }
        // причина рядом со следствием: Буфер переполняется → идут Дропы
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StreetStatCard(
                "Буфер",
                s?.sndBufferMs?.toString() ?: "—",
                Modifier.weight(1f),
                unit = "мс",
                labelColor = healthColor(h?.bufLevel),
                info = INFO_BUFFER,
            )
            StreetStatCard(
                "Дропы",
                h?.dropPerSec?.toString() ?: "—",
                Modifier.weight(1f),
                unit = "/с",
                labelColor = healthColor(h?.dropLevel),
                info = INFO_DROP,
            )
        }
        StreetStatCard(
            label = "Энкодер",
            value = s?.let { formatLag(it.encoderLagMs) } ?: "—",
            modifier = Modifier.fillMaxWidth(),
            labelColor = healthColor(h?.encoderLevel),
            info = INFO_ENCODER,
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

private fun formatRate(kbps: Int): String {
    val tenths = (kbps + 50) / 100
    return if (kbps >= 1000) "${tenths / 10}.${tenths % 10} Mbps" else "$kbps kbps"
}

private fun formatLag(ms: Int): String {
    val tenths = (ms + 50) / 100
    return if (ms >= 1000) "+${tenths / 10}.${tenths % 10}с" else "${ms}мс"
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
