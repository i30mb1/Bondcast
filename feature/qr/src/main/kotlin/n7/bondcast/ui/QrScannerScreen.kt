package n7.bondcast.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import n7.bondcast.DiscordColors
import n7.bondcast.feature.qr.R
import n7.bondcast.qr.BarcodeAnalyzer
import n7.bondcast.qr.QrPayload
import n7.bondcast.qr.qrPayloadParser
import n7.bondcast.ui.street.BackIcon
import n7.bondcast.ui.street.InfoIcon
import n7.bondcast.ui.street.SpringAppear
import n7.bondcast.ui.street.StreetShape
import n7.bondcast.ui.street.StreetTooltip
import n7.bondcast.ui.street.pressBounce
import n7.bondcast.ui.street.streetButton
import n7.bondcast.ui.street.streetLabel
import n7.bondcast.ui.street.streetTitle
import n7.bondcast.ui.street.streetUnit
import n7.bondcast.ui.street.streetValue
import n7.bondcast.ui.street.upper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Состояния экрана. Цвет рамки окна и есть индикатор: акцент — ищем,
 * зелёная — прочитали, жёлтая — код чужой, красная — кадра нет вовсе.
 */
private sealed interface ScanUiState {
    data object Scanning : ScanUiState
    data class Found(val payload: QrPayload) : ScanUiState
    data class Failed(val busy: Boolean) : ScanUiState
}

/** Затемнение поверх кадра: весь текст экрана лежит на нём, а не на превью. */
private val Scrim = DiscordColors.background.copy(alpha = 0.8f)

private val WindowShape = RoundedCornerShape(8.dp)
private val RailButtonShape = RoundedCornerShape(16.dp)
private val BigButtonShape = RoundedCornerShape(10.dp)
private val RailButtonSize = 48.dp

// пропорция «сколько кадра над окном и сколько под ним» — из макета (200 : 344)
private const val SPACE_ABOVE = 200f
private const val SPACE_BELOW = 344f

@Composable
public fun QrScannerScreen(
    onResult: (QrPayload) -> Unit,
    onBack: () -> Unit,
) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    val parser = remember { qrPayloadParser() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var uiState by remember { mutableStateOf<ScanUiState>(ScanUiState.Scanning) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var boundUseCases by remember { mutableStateOf<Array<UseCase>>(emptyArray()) }
    var torchOn by remember { mutableStateOf(false) }

    // читается из потока анализа, поэтому обычный флаг, а не compose-состояние:
    // первый распознанный кадр закрывает ворота, «Ещё раз» открывает их снова
    val accepting = remember { AtomicBoolean(true) }

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull()
        if (cameraProvider == null) {
            uiState = ScanUiState.Failed(busy = false)
            return@LaunchedEffect
        }
        provider = cameraProvider
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(
                    analysisExecutor,
                    BarcodeAnalyzer { raw ->
                        if (accepting.compareAndSet(true, false)) {
                            uiState = ScanUiState.Found(parser.parse(raw))
                        }
                    },
                )
            }
        boundUseCases = arrayOf(preview, analysis)
        // без unbindAll(): чтобы не сорвать живой эфир, снимаем только свои use-case на выходе
        runCatching {
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
            .onSuccess { camera = it }
            .onFailure { uiState = ScanUiState.Failed(busy = true) }
    }

    LaunchedEffect(camera, torchOn) {
        runCatching { camera?.cameraControl?.enableTorch(torchOn) }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { camera?.cameraControl?.enableTorch(false) }
            if (boundUseCases.isNotEmpty()) runCatching { provider?.unbind(*boundUseCases) }
            analysisExecutor.shutdown()
        }
    }

    fun retry() {
        accepting.set(true)
        uiState = ScanUiState.Scanning
    }

    val state = uiState
    val frameColor = when (state) {
        is ScanUiState.Failed -> DiscordColors.danger

        is ScanUiState.Found ->
            if (state.payload is QrPayload.Unknown) DiscordColors.yellow else DiscordColors.green

        ScanUiState.Scanning -> DiscordColors.accent
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val windowSize = minOf(300.dp, maxWidth - 88.dp, maxHeight * 0.36f)

        if (state !is ScanUiState.Failed) {
            surfaceRequest?.let { request ->
                CameraXViewfinder(surfaceRequest = request, modifier = Modifier.fillMaxSize())
            }
        }

        // затемнение собрано из полос вокруг окна — так «дырка» получается без слоёв и blend-режимов
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(SPACE_ABOVE).background(Scrim))
            Row(modifier = Modifier.fillMaxWidth().height(windowSize)) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Scrim))
                ScanWindow(
                    size = windowSize,
                    color = frameColor,
                    animated = state is ScanUiState.Scanning,
                    glyph = if (state is ScanUiState.Failed) {
                        { NoCameraIcon(DiscordColors.danger, Modifier.size(34.dp)) }
                    } else {
                        null
                    },
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Scrim))
            }
            Box(modifier = Modifier.fillMaxWidth().weight(SPACE_BELOW).background(Scrim))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            QrRailButton(onClick = onBack) { tint ->
                BackIcon(color = tint, modifier = Modifier.size(22.dp))
            }
            Text(
                text = stringResource(R.string.qr_scanner_title_label).upper(),
                color = DiscordColors.textPrimary,
                style = streetTitle.copy(fontSize = 22.sp),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 24.dp),
        ) {
            when (state) {
                ScanUiState.Scanning -> {
                    ScannerRail(
                        enabled = true,
                        torchOn = torchOn,
                        hasTorch = camera?.cameraInfo?.hasFlashUnit() == true,
                        onTorch = { torchOn = !torchOn },
                    )
                    ScanningFooter()
                }

                is ScanUiState.Found -> SpringAppear {
                    if (state.payload is QrPayload.Unknown) {
                        UnknownCard(onRetry = ::retry)
                    } else {
                        ResultCard(
                            payload = state.payload,
                            onApply = { onResult(state.payload) },
                            onRetry = ::retry,
                        )
                    }
                }

                is ScanUiState.Failed -> {
                    ScannerRail(enabled = false, torchOn = false, hasTorch = true, onTorch = {})
                    CameraFailurePanel(busy = state.busy, onBack = onBack)
                }
            }
        }
    }
}

/* ───────────────────────── окно сканирования ───────────────────────── */

@Composable
private fun ScanWindow(
    size: Dp,
    color: Color,
    animated: Boolean,
    glyph: (@Composable () -> Unit)? = null,
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        CornerScrim(Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, color.copy(alpha = 0.27f), WindowShape),
        )
        WindowCorners(color, Modifier.fillMaxSize())
        if (animated) SweepLine(color, size)
        glyph?.invoke()
    }
}

/**
 * Дырка в затемнении вырезана полосами, то есть прямоугольная, а рамка — скруглённая:
 * без этого в четырёх углах просвечивал бы чистый кадр. Докрашиваем ровно эти уголки —
 * разницу между квадратом и скруглённым прямоугольником, той же плёнкой.
 */
@Composable
private fun CornerScrim(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = CornerRadius(8.dp.toPx())
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(Rect(Offset.Zero, size), radius, radius, radius, radius))
        }
        drawPath(path, Scrim)
    }
}

/** Четыре скобки-уголка: линия — дуга — линия, тем же скруглением, что и рамка. */
@Composable
private fun WindowCorners(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val len = 40.dp.toPx()
        val sw = 2.dp.toPx()
        val r = 8.dp.toPx()
        val i = sw / 2f
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(i, len)
            lineTo(i, r + i)
            arcTo(Rect(i, i, i + 2 * r, i + 2 * r), 180f, 90f, false)
            lineTo(len, i)

            moveTo(w - len, i)
            lineTo(w - r - i, i)
            arcTo(Rect(w - i - 2 * r, i, w - i, i + 2 * r), 270f, 90f, false)
            lineTo(w - i, len)

            moveTo(w - i, h - len)
            lineTo(w - i, h - r - i)
            arcTo(Rect(w - i - 2 * r, h - i - 2 * r, w - i, h - i), 0f, 90f, false)
            lineTo(w - len, h - i)

            moveTo(len, h - i)
            lineTo(r + i, h - i)
            arcTo(Rect(i, h - i - 2 * r, i + 2 * r, h - i), 90f, 90f, false)
            lineTo(i, h - len)
        }
        drawPath(path, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Бегущая линия: гаснет у краёв, чтобы не выглядела упирающейся в рамку. */
@Composable
private fun SweepLine(color: Color, windowSize: Dp) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val fade = 0.12f
    val alpha = when {
        progress < fade -> progress / fade
        progress > 1f - fade -> (1f - progress) / fade
        else -> 1f
    }
    Box(modifier = Modifier.fillMaxSize().padding(6.dp).clipToBounds()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .offset(y = (windowSize - 14.dp) * progress)
                .alpha(alpha)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent))),
        )
    }
}

/* ───────────────────────── рейл и подвал ───────────────────────── */

@Composable
private fun ScannerRail(
    enabled: Boolean,
    torchOn: Boolean,
    hasTorch: Boolean,
    onTorch: () -> Unit,
) {
    var infoOpen by remember { mutableStateOf(false) }
    val hint = stringResource(R.string.qr_scanner_hint_label)
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (hasTorch) {
                QrRailButton(active = torchOn, enabled = enabled, onClick = onTorch) { tint ->
                    FlashIcon(tint, Modifier.size(22.dp))
                }
            }
            QrRailButton(enabled = enabled, onClick = { infoOpen = !infoOpen }) { tint ->
                Box {
                    InfoIcon(tint, Modifier.size(20.dp))
                    if (infoOpen) StreetTooltip(text = hint, onDismissRequest = { infoOpen = false })
                }
            }
        }
    }
}

/** Что делать → какие схемы подойдут → куда за готовым сервером. */
@Composable
private fun ScanningFooter() {
    val context = LocalContext.current
    val landingUrl = stringResource(R.string.qr_scanner_landing_url)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .clip(StreetShape)
                .background(DiscordColors.plate)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            PulsingDot()
            Text(
                text = stringResource(R.string.qr_scanner_aim_label).upper(),
                color = DiscordColors.accent,
                style = streetButton,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.qr_scanner_scheme_obs),
                color = DiscordColors.textSecondary,
                style = streetUnit.copy(fontSize = 12.sp),
            )
            Box(Modifier.size(3.dp).background(DiscordColors.textMuted, CircleShape))
            Text(
                text = stringResource(R.string.qr_scanner_scheme_bondcast),
                color = DiscordColors.textSecondary,
                style = streetUnit.copy(fontSize = 12.sp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(StreetShape)
                .background(DiscordColors.inputBackground)
                .border(1.dp, DiscordColors.divider, StreetShape)
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(landingUrl)))
                    }
                }
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Text(
                text = stringResource(R.string.qr_scanner_landing_label),
                color = DiscordColors.link,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            )
            ArrowIcon(DiscordColors.link, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "blip")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blipAlpha",
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .alpha(alpha)
            .background(DiscordColors.accent, CircleShape),
    )
}

/* ───────────────────────── карточки состояний ───────────────────────── */

@Composable
private fun ResultCard(
    payload: QrPayload,
    onApply: () -> Unit,
    onRetry: () -> Unit,
) {
    val source: String
    val value: String
    val port: String?
    val sub: String?
    val note: String
    when (payload) {
        is QrPayload.ObsConnect -> {
            source = stringResource(R.string.qr_scanner_source_obs_label)
            value = payload.host
            port = ": ${payload.port}"
            sub = stringResource(
                if (payload.password.isBlank()) {
                    R.string.qr_scanner_password_none
                } else {
                    R.string.qr_scanner_password_masked
                },
            )
            note = stringResource(R.string.qr_scanner_apply_note_obs)
        }

        is QrPayload.ServerConfig -> {
            source = stringResource(R.string.qr_scanner_source_config_label)
            value = payload.host ?: payload.srtlaHost.orEmpty()
            port = (payload.port ?: payload.srtlaPort)?.let { ": $it" }
            sub = payload.streamName?.let { stringResource(R.string.qr_scanner_stream_name_label, it) }
            note = stringResource(R.string.qr_scanner_apply_note_config)
        }

        // сюда не попадаем: неизвестный код показывает UnknownCard
        is QrPayload.Unknown -> return
    }

    StateColumn {
        StateHeader(
            label = stringResource(R.string.qr_scanner_found_label),
            color = DiscordColors.green,
            icon = { CheckIcon(DiscordColors.green, Modifier.size(20.dp)) },
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(StreetShape)
                .background(DiscordColors.inputBackground)
                .border(1.dp, DiscordColors.divider, StreetShape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(text = source.upper(), color = DiscordColors.accent, style = streetLabel)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = value, color = DiscordColors.textPrimary, style = streetValue)
                if (port != null) {
                    Text(
                        text = port,
                        color = DiscordColors.textSecondary,
                        style = streetUnit.copy(fontSize = 12.sp),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            if (sub != null) {
                Text(text = sub, color = DiscordColors.textSecondary, style = streetUnit.copy(fontSize = 12.sp))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoIcon(DiscordColors.textMuted, Modifier.size(16.dp))
            Text(text = note, color = DiscordColors.textMuted, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            BigButton(
                text = stringResource(R.string.qr_scanner_apply_button),
                background = DiscordColors.accent,
                textColor = DiscordColors.onAccent,
                onClick = onApply,
                modifier = Modifier.weight(1f),
            )
            BigButton(
                text = stringResource(R.string.qr_scanner_retry_button),
                background = DiscordColors.plate,
                textColor = DiscordColors.textSecondary,
                onClick = onRetry,
            )
        }
    }
}

@Composable
private fun UnknownCard(onRetry: () -> Unit) {
    StateColumn {
        NoticePanel(
            title = stringResource(R.string.qr_scanner_unknown_label),
            hint = stringResource(R.string.qr_scanner_unknown_hint),
            color = DiscordColors.yellow,
        )
        BigButton(
            text = stringResource(R.string.qr_scanner_retry_button),
            background = DiscordColors.plate,
            textColor = DiscordColors.textPrimary,
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CameraFailurePanel(busy: Boolean, onBack: () -> Unit) {
    StateColumn {
        NoticePanel(
            title = stringResource(
                if (busy) R.string.qr_scanner_camera_busy_title else R.string.qr_scanner_camera_open_title,
            ),
            hint = stringResource(
                if (busy) R.string.qr_scanner_camera_busy_hint else R.string.qr_scanner_camera_open_hint,
            ),
            color = DiscordColors.danger,
        )
        BigButton(
            text = stringResource(R.string.qr_scanner_to_settings_button),
            background = DiscordColors.plate,
            textColor = DiscordColors.textPrimary,
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ───────────────────────── общие куски ───────────────────────── */

@Composable
private fun StateColumn(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        content()
    }
}

@Composable
private fun StateHeader(label: String, color: Color, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon()
        Text(text = label.upper(), color = color, style = streetLabel.copy(fontSize = 12.sp))
    }
}

/** Панель с цветным контуром: заголовок состояния и строка «что делать». */
@Composable
private fun NoticePanel(title: String, hint: String, color: Color) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(StreetShape)
            .background(DiscordColors.inputBackground)
            .border(1.dp, color, StreetShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text = title.upper(), color = color, style = streetLabel.copy(fontSize = 12.sp))
        Text(text = hint, color = DiscordColors.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BigButton(
    text: String,
    background: Color,
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .pressBounce(interaction)
            .clip(BigButtonShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(text = text.upper(), color = textColor, style = streetButton)
    }
}

@Composable
private fun QrRailButton(
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    glyph: @Composable (Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .pressBounce(interaction)
            .size(RailButtonSize)
            .clip(RailButtonShape)
            .background(if (active) DiscordColors.accent else DiscordColors.plate)
            .border(
                width = 2.dp,
                color = if (active) DiscordColors.accent else DiscordColors.iconBorder,
                shape = RailButtonShape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        glyph(
            when {
                !enabled -> DiscordColors.textMuted
                active -> DiscordColors.onAccent
                else -> DiscordColors.textSecondary
            },
        )
    }
}

private fun Context.findLifecycleOwner(): LifecycleOwner {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        current = current.baseContext
    }
    error("Нет LifecycleOwner в контексте")
}
