package n7.bondcast.camerax

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.utils.extensions.isNaturalToPortrait
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import n7.bondcast.overlay.OverlayCompositor
import n7.bondcast.overlay.OverlayFrame

internal class CameraXVideoSource(
    private val context: Context,
    val cameraId: String,
    private val compositor: OverlayCompositor,
) : IVideoSourceInternal,
    ISurfaceSourceInternal {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var outputSurface: Surface? = null
    private var config: VideoSourceConfig? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    private val _infoProviderFlow = MutableStateFlow<ISourceInfoProvider>(CameraXSourceInfoProvider(context, cameraId))

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    // держим только для setCompositionSettings: перетаскивание врезки без ребинда
    private var concurrentCamera: ConcurrentCamera? = null

    // свои use-cases: teardown снимает ТОЛЬКО их, а не unbindAll() — иначе старый источник при
    // switchCamera убивает привязку нового (делят один ProcessCameraProvider), камера гаснет
    private var boundUseCases: List<UseCase> = emptyList()
    private val lifecycleOwner = SourceLifecycleOwner()

    private var overlayThread: HandlerThread? = null
    private var overlayEffect: OverlayEffect? = null

    override val timebase: Timebase = Timebase.UPTIME
    override val infoProviderFlow: StateFlow<ISourceInfoProvider> = _infoProviderFlow.asStateFlow()
    override val isStreamingFlow: StateFlow<Boolean> = _isStreamingFlow.asStateFlow()

    override suspend fun getOutput(): Surface? = outputSurface

    override suspend fun setOutput(surface: Surface) {
        outputSurface = surface
        Log.i(TAG, "setOutput streaming=${_isStreamingFlow.value} valid=${surface.isValid}")
        CameraXPreviewBus.onWantChanged = { mainHandler.post { bind() } }
        // claim ДО bind(): при свитче камеры StreamPack держит старый источник живым ещё какое-то
        // время, и его resetOutput()/release() не должны затирать то, что публикует этот источник
        CameraControlBus.claim(this)
        CameraControlBus.onStabilizationChanged = { mainHandler.post { bind() } }
        CameraControlBus.onNoiseReductionChanged = { mainHandler.post { bind() } }
        // вкл/выкл PiP и смена местами меняют набор камер — нужен полный ребинд
        CameraControlBus.onPipChanged = { mainHandler.post { bind() } }
        bind()
    }

    override suspend fun resetOutput() {
        outputSurface = null
        CameraXPreviewBus.onWantChanged = null
        CameraXPreviewBus.offerRequest(null)
        CameraControlBus.release(this)
        mainHandler.post { unbind() }
    }

    override suspend fun configure(config: VideoSourceConfig) {
        this.config = config
    }

    override suspend fun startStream() {
        _isStreamingFlow.value = true
        Log.i(TAG, "startStream surface=${outputSurface != null}")
        if (outputSurface != null) bind()
    }

    override suspend fun stopStream() {
        _isStreamingFlow.value = false
    }

    override suspend fun release() {
        CameraXPreviewBus.onWantChanged = null
        CameraXPreviewBus.offerRequest(null)
        CameraControlBus.release(this)
        mainHandler.post {
            unbind()
            lifecycleOwner.destroy()
            overlayEffect?.close()
            overlayEffect = null
            overlayThread?.quitSafely()
            overlayThread = null
        }
    }

    private fun bind() {
        mainHandler.post {
            val size = config?.resolution ?: return@post
            val fps = config?.fps
            val encoder = outputSurface ?: return@post
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val cameraProvider = runCatching { future.get() }.getOrNull() ?: return@addListener
                this.cameraProvider = cameraProvider
                val cameraSelector = selectorFor(cameraId)
                val resolutionSelector = resolutionSelectorFor(size)

                val cameraInfo = runCatching { cameraProvider.getCameraInfo(cameraSelector) }.getOrNull()

                // шумодав сенсора/ISP — CaptureRequest-опция, ставится на билдер до бинда
                val nrModes = cameraInfo?.noiseReductionModes() ?: IntArray(0)
                val nrSwitchable = nrModes.noiseReductionSwitchable()
                CameraControlBus.publishNoiseReductionSupported(this, nrSwitchable)
                val nrMode = if (CameraControlBus.noiseReductionWanted.value) {
                    nrModes.bestNoiseReductionMode()
                } else {
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF
                }

                // умеет ли устройство две камеры разом — до бинда, чтобы UI знал, показывать ли тумблер
                val concurrentCombo = cameraProvider.pipCombo()
                CameraControlBus.publishPipSupported(this, concurrentCombo != null)
                val pip = concurrentCombo != null && CameraControlBus.pipWanted.value

                // В PiP кадр энкодера забирает VideoCapture, а не Preview: composition-режим CameraX
                // включает только для пары Preview + VideoCapture (см. EncoderVideoOutput). В обычном
                // режиме остаётся Preview — он дешевле и не тянет MediaSpec/QualitySelector.
                // Поворот фиксируем, чтобы кадр не крутился вслед за устройством, но фиксируем
                // именно в landscape, а не в ROTATION_0: у телефона натуральная ориентация
                // портретная, поэтому ROTATION_0 — это ПОРТРЕТ, и энкодерный кадр уезжал на 90°
                // относительно эфира. Видно было по превью на экране: оно targetRotation не задаёт,
                // берёт текущий поворот дисплея (ROTATION_90) и рисует правильно, а энкодер с
                // ROTATION_0 — повёрнуто.
                val landscapeRotation = landscapeRotation()
                val encoderUseCase: UseCase = if (pip) {
                    VideoCapture.Builder(EncoderVideoOutput(encoder))
                        .setResolutionSelector(resolutionSelector)
                        .setTargetRotation(landscapeRotation)
                        .build()
                } else {
                    Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setTargetRotation(landscapeRotation)
                        .build()
                        .apply {
                            setSurfaceProvider(mainExecutor) { request ->
                                Log.i(TAG, "encoder surfaceRequest ${request.resolution} target=$size")
                                request.provideSurface(encoder, mainExecutor) { }
                            }
                        }
                }

                val displayPreview = if (CameraXPreviewBus.wantPreview) {
                    Preview.Builder().setResolutionSelector(resolutionSelector).build().apply {
                        setSurfaceProvider(mainExecutor) { request -> CameraXPreviewBus.offerRequest(request) }
                    }
                } else {
                    CameraXPreviewBus.offerRequest(null)
                    null
                }

                val frameRateRange = fps?.let { cameraInfo?.pickFrameRateRange(it) }
                val useCases = listOfNotNull(encoderUseCase, displayPreview)
                // Эффект ставим ВСЕГДА, даже когда рисовать нечего (а сейчас нечего: StreamOverlay
                // никто не регистрирует, hasOverlays() всегда false). Дело не в оверлеях: его
                // GL-проход — единственное место, где поворот из targetRotation реально применяется
                // к пикселям. `Preview` пишет в нашу Surface сырой буфер сенсора и сообщает поворот
                // отдельно, через SurfaceRequest.TransformationInfo, а мы в provideSurface() его не
                // применяем. Проверено на A024 тремя прогонами: эффект без landscape-targetRotation
                // — кадр на 90°; landscape-targetRotation без эффекта — кадр на 90°; вместе — ровно.
                val effects = listOf(overlayEffect())

                if (pip) {
                    bindPip(cameraProvider, concurrentCombo!!, useCases, effects, size)
                    return@addListener
                }

                // умеет ли камера в принципе (напр. фронталка часто не умеет) — до бинда, чтобы UI мог скрыть тумблер
                val stabilizationSupported = cameraInfo?.let { info ->
                    runCatching {
                        info.isSessionConfigSupported(
                            SessionConfig(useCases = useCases, requiredFeatureGroup = setOf(GroupableFeature.PREVIEW_STABILIZATION)),
                        )
                    }.getOrDefault(false)
                } ?: false
                CameraControlBus.publishStabilizationSupported(this, stabilizationSupported)

                val preferredFeatures = if (CameraControlBus.stabilizationWanted.value) {
                    listOf(GroupableFeature.PREVIEW_STABILIZATION)
                } else {
                    emptyList()
                }
                val sessionConfig = SessionConfig.Builder(useCases)
                    .apply {
                        effects.forEach { addEffect(it) }
                        setPreferredFeatureGroup(*preferredFeatures.toTypedArray())
                        // frameRateRange без дефолта-null — если камера не подтвердила диапазон, не ставим его вовсе
                        if (frameRateRange != null) setFrameRateRange(frameRateRange)
                        if (nrSwitchable) setNoiseReductionMode(nrMode)
                    }
                    .build()
                // предпочтительная фича — CameraX сам решит, влезла ли стабилизация в комбинацию
                sessionConfig.setFeatureSelectionListener(mainExecutor) { selected ->
                    CameraControlBus.publishStabilizationActive(this, selected.contains(GroupableFeature.PREVIEW_STABILIZATION))
                }

                runCatching {
                    // новый источник обязан очистить старую камеру (открыть две сразу нельзя) — здесь
                    // unbindAll() уместен; опасен именно teardown старого источника, см. unbind()
                    cameraProvider.unbindAll()
                    lifecycleOwner.resume()
                    val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, sessionConfig)
                    this.camera = camera
                    // вышли из PiP — дальше unbind() снова работает точечно
                    concurrentCamera = null
                    CameraControlBus.onPipLayoutChanged = null
                    boundUseCases = useCases
                    CameraControlBus.publishCamera(this, camera)
                    Log.i(TAG, "bound cameraId=$cameraId size=$size preview=${displayPreview != null} fps=$frameRateRange")
                }.onFailure { Log.w(TAG, "bind failed: $it") }
            }, mainExecutor)
        }
    }

    /**
     * Комбинация «тыл + фронт», если устройство её разрешает. Берём ту, где есть обе линзы: на A024
     * доступна ровно одна такая комбинация.
     */
    private fun ProcessCameraProvider.pipCombo(): List<CameraInfo>? = runCatching { availableConcurrentCameraInfos }.getOrNull()
        ?.firstOrNull { combo ->
            combo.size == 2 && combo.map { it.lensFacing }.toSet() ==
                setOf(CameraSelector.LENS_FACING_BACK, CameraSelector.LENS_FACING_FRONT)
        }

    /**
     * Бинд двух камер разом: CameraX сам сшивает их по [CompositionSettings].
     *
     * Ограничения concurrent-режима, проверенные на A024 и на минимальном стенде:
     * - в группе должно быть РОВНО два use-case'а: Preview + VideoCapture. Иначе CameraX молча (без
     *   единой строчки в лог) уходит в non-composition ветку: обе камеры биндятся независимо,
     *   CompositionSettings игнорируются, врезки нет. Именно поэтому кадр энкодера здесь отдаёт
     *   VideoCapture, а не второй Preview — см. [EncoderVideoOutput];
     * - конфиги обязаны иметь одинаковые lifecycleOwner, viewPort и effects, иначе
     *   IllegalArgumentException («Two camera configs need to have the same...»);
     * - селекторы только через requireLensFacing: bindToLifecycle сравнивает getLensFacing(), и при
     *   null считает камеры одной («dual selfie») → «Camera is already running»;
     * - use-case'ы обеих групп объединяются в один LegacySessionConfig, поэтому fps, стабилизация и
     *   шумодав (они живут в SessionConfig) в этом режиме недоступны — их отдаёт только одиночный бинд.
     */
    private fun bindPip(
        provider: ProcessCameraProvider,
        combo: List<CameraInfo>,
        useCases: List<UseCase>,
        effects: List<CameraEffect>,
        size: Size,
    ) {
        val layout = CameraControlBus.pipLayout.value.coerced()
        val mainLens = if (layout.mainIsBack) CameraSelector.LENS_FACING_BACK else CameraSelector.LENS_FACING_FRONT

        // ОДИН UseCaseGroup на обе камеры: CameraX сшивает их потоки в его выход через StreamSharing,
        // и composition-режим включается только когда есть и вторая камера, и шаринг (проверка
        // mSecondaryCameraInternal != null && mStreamSharing != null в CameraUseCaseAdapter). Если
        // дать врезке отдельный Preview, композиции не будет: бинд пройдёт, но setCompositionSettings
        // упадёт с «The camera is not in concurrent camera composition mode», а врезка не появится.
        val group = UseCaseGroup.Builder()
            .apply {
                useCases.forEach { addUseCase(it) }
                effects.forEach { addEffect(it) }
            }
            .build()

        val configs = combo
            .sortedByDescending { it.lensFacing == mainLens }
            .mapIndexed { index, info ->
                val isMain = index == 0
                val composition = if (isMain) CompositionSettings.DEFAULT else layout.insetComposition()
                ConcurrentCamera.SingleCameraConfig(
                    CameraSelector.Builder().requireLensFacing(info.lensFacing).build(),
                    group,
                    composition,
                    lifecycleOwner,
                )
            }

        runCatching {
            provider.unbindAll()
            lifecycleOwner.resume()
            val concurrent = provider.bindToLifecycle(configs)
            concurrentCamera = concurrent
            camera = concurrent.cameras.firstOrNull()
            boundUseCases = useCases
            CameraControlBus.publishCamera(this, camera)
            // перетаскивание меняет только композицию — без ребинда, иначе камера мигала бы на жесте
            CameraControlBus.onPipLayoutChanged = { updated -> applyPipComposition(updated) }
            Log.i(TAG, "bound PiP main=$mainLens size=$size layout=$layout")
        }.onFailure {
            Log.w(TAG, "PiP bind failed, откат на одиночную камеру", it)
            concurrentCamera = null
            CameraControlBus.setPipWanted(false)
        }
    }

    /**
     * Врезка в терминах CameraX. Единственное место перевода наших долей кадра в NDC —
     * [CompositionSettings] считает от центра кадра (-1..1, Y вверх), а [PipLayout] от левого
     * верхнего угла (0..1, Y вниз); без пересчёта врезка уезжает мимо рамки-хвата в превью.
     */
    private fun PipLayout.insetComposition(): CompositionSettings = CompositionSettings.Builder()
        .setOffset(ndcCenterX(), ndcCenterY())
        .setScale(scale, scale)
        .setZOrder(1)
        // скругление и рамку рисует сам CameraX — они попадают в эфир, а не только на экран
        .setRoundedCornerRatio(INSET_CORNER_RATIO)
        .setBorderWidthRatio(INSET_BORDER_RATIO)
        .setBorderColor(INSET_BORDER_COLOR)
        .build()

    /** Применяет раскладку к живой сессии — без ребинда камеры. */
    private fun applyPipComposition(layout: PipLayout) {
        val concurrent = concurrentCamera ?: return
        val safe = layout.coerced()
        runCatching {
            concurrent.setCompositionSettings(listOf(CompositionSettings.DEFAULT, safe.insetComposition()))
        }.onFailure { Log.w(TAG, "setCompositionSettings failed: $it") }
    }

    /**
     * Подбирает диапазон, реально поддерживаемый камерой, под целевой fps энкодера.
     *
     * Порядок важен именно из-за 24 к/с: жёсткий [24,24] есть не у всех сенсоров, а «просто
     * содержащий 24» диапазон вроде [7,30] или [15,30] — плавающий, и на свету камера в нём
     * уедет к 30. Поэтому сначала жёсткая фиксация, потом диапазон с ВЕРХНЕЙ границей ровно
     * в целевой fps (там 24 — это потолок, ниже автоэкспозиция опускается только в темноте),
     * и лишь в последнюю очередь любой подходящий.
     */
    private fun CameraInfo.pickFrameRateRange(fps: Int): Range<Int>? {
        val ranges = runCatching { supportedFrameRateRanges }.getOrNull() ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.firstOrNull { it.upper == fps }
            ?: ranges.firstOrNull { it.contains(fps) }
    }

    private fun unbind() {
        val toUnbind = boundUseCases
        if (concurrentCamera != null) {
            // в concurrent-режиме точечный unbind запрещён («Unbind UseCase is not supported in
            // concurrent camera mode, call unbindAll() first») — только целиком
            runCatching { cameraProvider?.unbindAll() }
            concurrentCamera = null
        } else if (toUnbind.isNotEmpty()) {
            // хирургически: снимаем только свои use-cases. unbindAll() убил бы привязку нового источника
            // при switchCamera (StreamPack биндит новый ДО release() старого — см. CameraControlBus)
            runCatching { cameraProvider?.unbind(*toUnbind.toTypedArray()) }
        }
        boundUseCases = emptyList()
        lifecycleOwner.pause()
        camera = null
    }

    /** Поворот, при котором кадр горизонтальный. У планшетов натуральная ориентация уже landscape. */
    private fun landscapeRotation(): Int =
        if (context.isNaturalToPortrait) Surface.ROTATION_90 else Surface.ROTATION_0

    private fun overlayEffect(): OverlayEffect {
        overlayEffect?.let { return it }
        val thread = HandlerThread("camerax-overlay").apply { start() }
        // PREVIEW|VIDEO_CAPTURE, а не только PREVIEW: CameraX включает StreamSharing (а с ним и
        // composition-режим двух камер) лишь когда у эффекта больше одного таргета — см.
        // CameraUseCaseAdapter.isSharingEffect → TargetUtils.getNumberOfTargets > 1
        val effect = OverlayEffect(
            CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
            4,
            Handler(thread.looper),
            { Log.w(TAG, "overlay error: $it") },
        )
        effect.setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            // поворот/зеркало берём из самого кадра, а не из провайдера: провайдер сообщает
            // ориентацию для StreamPack (там 0, кадр уже развёрнут), а эффекту нужна фактическая
            compositor.drawAll(OverlayFrame(canvas, frame.size, frame.rotationDegrees, frame.isMirroring))
            true
        }
        overlayThread = thread
        overlayEffect = effect
        return effect
    }

    private fun selectorFor(id: String): CameraSelector = Camera2Interop.getCameraSelectorFromCameraId(id)

    private companion object {
        const val TAG = "CameraXSource"

        // доли от половины меньшей стороны врезки, а не пиксели — врезка масштабируется вместе с рамкой
        const val INSET_CORNER_RATIO = 0.08f
        const val INSET_BORDER_RATIO = 0.012f
        const val INSET_BORDER_COLOR = Color.WHITE
    }
}

private class SourceLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }
    fun pause() {
        registry.currentState = Lifecycle.State.CREATED
    }
    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
