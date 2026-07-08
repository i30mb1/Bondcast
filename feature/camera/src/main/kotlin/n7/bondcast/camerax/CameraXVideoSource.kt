package n7.bondcast.camerax

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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

internal class CameraXVideoSource(
    private val context: Context,
    val cameraId: String,
) : IVideoSourceInternal, ISurfaceSourceInternal {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var outputSurface: Surface? = null
    private var config: VideoSourceConfig? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    private val _infoProviderFlow = MutableStateFlow<ISourceInfoProvider>(CameraXSourceInfoProvider(context, cameraId))

    private var cameraProvider: ProcessCameraProvider? = null
    private val lifecycleOwner = SourceLifecycleOwner()

    override val timebase: Timebase = Timebase.UPTIME
    override val infoProviderFlow: StateFlow<ISourceInfoProvider> = _infoProviderFlow.asStateFlow()
    override val isStreamingFlow: StateFlow<Boolean> = _isStreamingFlow.asStateFlow()

    override suspend fun getOutput(): Surface? = outputSurface

    override suspend fun setOutput(surface: Surface) {
        outputSurface = surface
        Log.i(TAG, "setOutput streaming=${_isStreamingFlow.value} valid=${surface.isValid}")
        CameraXPreviewBus.listener = { mainHandler.post { bind() } }
        bind()
    }

    override suspend fun resetOutput() {
        outputSurface = null
        CameraXPreviewBus.listener = null
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
        mainHandler.post {
            unbind()
            lifecycleOwner.destroy()
        }
    }

    private fun bind() {
        mainHandler.post {
            val size = config?.resolution ?: return@post
            val encoder = outputSurface ?: return@post
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val cameraProvider = runCatching { future.get() }.getOrNull() ?: return@addListener
                this.cameraProvider = cameraProvider
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()

                val encoderPreview = Preview.Builder().setResolutionSelector(selector).build()
                encoderPreview.setSurfaceProvider(mainExecutor) { request ->
                    Log.i(TAG, "encoder surfaceRequest ${request.resolution} target=$size")
                    request.provideSurface(encoder, mainExecutor) { }
                }

                val displayPreview = CameraXPreviewBus.provider?.let { provider ->
                    Preview.Builder().setResolutionSelector(selector).build().apply {
                        setSurfaceProvider(provider)
                    }
                }

                val useCases: Array<UseCase> = listOfNotNull(encoderPreview, displayPreview).toTypedArray()
                runCatching {
                    cameraProvider.unbindAll()
                    lifecycleOwner.resume()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selectorFor(cameraId), *useCases)
                    Log.i(TAG, "bound cameraId=$cameraId size=$size preview=${displayPreview != null}")
                }.onFailure { Log.w(TAG, "bind failed: $it") }
            }, mainExecutor)
        }
    }

    private fun unbind() {
        runCatching { cameraProvider?.unbindAll() }
        lifecycleOwner.pause()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorFor(id: String): CameraSelector =
        CameraSelector.Builder()
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
    fun resume() { registry.currentState = Lifecycle.State.RESUMED }
    fun pause() { registry.currentState = Lifecycle.State.CREATED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
