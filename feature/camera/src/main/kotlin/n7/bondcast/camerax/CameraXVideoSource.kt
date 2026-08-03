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
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
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
                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()

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

                val encoderPreview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .apply { if (nrSwitchable) setNoiseReductionMode(nrMode) }
                    .build()
                encoderPreview.setSurfaceProvider(mainExecutor) { request ->
                    Log.i(TAG, "encoder surfaceRequest ${request.resolution} target=$size")
                    request.provideSurface(encoder, mainExecutor) { }
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
                val useCases = listOfNotNull(encoderPreview, displayPreview)
                val effects = if (compositor.hasOverlays()) listOf(overlayEffect()) else emptyList()

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
                // frameRateRange без дефолта-null — если камера не подтвердила диапазон, не передаём его вовсе
                val sessionConfig = if (frameRateRange != null) {
                    SessionConfig(
                        useCases = useCases,
                        effects = effects,
                        frameRateRange = frameRateRange,
                        preferredFeatureGroup = preferredFeatures,
                    )
                } else {
                    SessionConfig(
                        useCases = useCases,
                        effects = effects,
                        preferredFeatureGroup = preferredFeatures,
                    )
                }
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
                    boundUseCases = useCases
                    CameraControlBus.publishCamera(this, camera)
                    Log.i(TAG, "bound cameraId=$cameraId size=$size preview=${displayPreview != null} fps=$frameRateRange")
                }.onFailure { Log.w(TAG, "bind failed: $it") }
            }, mainExecutor)
        }
    }

    /** Подбирает диапазон, реально поддерживаемый камерой, под целевой fps энкодера. */
    private fun CameraInfo.pickFrameRateRange(fps: Int): Range<Int>? {
        val desired = Range(fps, fps)
        val ranges = runCatching { supportedFrameRateRanges }.getOrNull() ?: return null
        return ranges.firstOrNull { it == desired } ?: ranges.firstOrNull { it.contains(fps) }
    }

    private fun unbind() {
        // хирургически: снимаем только свои use-cases. unbindAll() убил бы привязку нового источника
        // при switchCamera (StreamPack биндит новый ДО release() старого — см. CameraControlBus)
        val toUnbind = boundUseCases
        if (toUnbind.isNotEmpty()) runCatching { cameraProvider?.unbind(*toUnbind.toTypedArray()) }
        boundUseCases = emptyList()
        lifecycleOwner.pause()
        camera = null
    }

    private fun overlayEffect(): OverlayEffect {
        overlayEffect?.let { return it }
        val thread = HandlerThread("camerax-overlay").apply { start() }
        val effect = OverlayEffect(
            CameraEffect.PREVIEW,
            4,
            Handler(thread.looper),
            { Log.w(TAG, "overlay error: $it") },
        )
        effect.setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val info = _infoProviderFlow.value
            compositor.drawAll(OverlayFrame(canvas, frame.size, info.rotationDegrees, info.isMirror))
            true
        }
        overlayThread = thread
        overlayEffect = effect
        return effect
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorFor(id: String): CameraSelector = CameraSelector.Builder()
        .addCameraFilter { infos ->
            infos.filter { runCatching { Camera2CameraInfo.from(it).cameraId == id }.getOrDefault(false) }
        }
        .build()

    private companion object {
        const val TAG = "CameraXSource"
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
