package n7.bondcast.uvc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class UvcVideoSource(private val context: Context) :
    IVideoSourceInternal,
    ISurfaceSourceInternal {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var outputSurface: Surface? = null
    private var config: VideoSourceConfig? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    private val _infoProviderFlow = MutableStateFlow<ISourceInfoProvider>(UvcSourceInfoProvider())

    private var helper: ICameraHelper? = null
    private var started = false

    @Volatile
    private var renderBitmap: Bitmap = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)

    @Volatile
    private var srcRect = Rect(0, 0, FRAME_W, FRAME_H)
    private var frameCount = 0L

    override val timebase: Timebase = Timebase.UPTIME
    override val infoProviderFlow: StateFlow<ISourceInfoProvider> = _infoProviderFlow.asStateFlow()
    override val isStreamingFlow: StateFlow<Boolean> = _isStreamingFlow.asStateFlow()

    override suspend fun getOutput(): Surface? = outputSurface

    override suspend fun setOutput(surface: Surface) {
        outputSurface = surface
        Log.i(TAG, "setOutput streaming=${_isStreamingFlow.value} valid=${surface.isValid}")
        openHelper()
    }

    override suspend fun resetOutput() {
        outputSurface = null
        mainHandler.post { closeHelper() }
    }

    override suspend fun configure(config: VideoSourceConfig) {
        this.config = config
    }

    override suspend fun startStream() {
        _isStreamingFlow.value = true
        Log.i(TAG, "startStream surface=${outputSurface != null}")
        if (outputSurface != null) openHelper()
    }

    override suspend fun stopStream() {
        _isStreamingFlow.value = false
    }

    override suspend fun release() {
        closeHelper()
    }

    private fun openHelper() {
        mainHandler.post {
            if (helper != null) return@post
            val h = CameraHelper()
            h.setStateCallback(stateCallback)
            helper = h
            trySelectDevice(0)
        }
    }

    private fun trySelectDevice(attempt: Int) {
        val h = helper ?: return
        val device = androidUvcDevice()
        Log.i(TAG, "openHelper attempt=$attempt herohan=${h.deviceList.size} android=${device?.deviceName}")
        when {
            device != null -> h.selectDevice(device)
            attempt < 5 -> mainHandler.postDelayed({ trySelectDevice(attempt + 1) }, 300)
            else -> Log.w(TAG, "no uvc device found")
        }
    }

    private fun androidUvcDevice(): UsbDevice? {
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return um.deviceList.values.firstOrNull { isUvc(it) }
    }

    private fun closeHelper() {
        mainHandler.post {
            helper?.let {
                runCatching { it.stopPreview() }
                runCatching { it.closeCamera() }
                runCatching { it.release() }
            }
            helper = null
            started = false
            runCatching { renderBitmap.recycle() }
        }
    }

    private fun ensureBitmap(width: Int, height: Int) {
        val current = renderBitmap
        if (!current.isRecycled && current.width == width && current.height == height) return
        renderBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        srcRect = Rect(0, 0, width, height)
        if (!current.isRecycled) runCatching { current.recycle() }
    }

    private fun isUvc(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }

    private val frameCallback = IFrameCallback { frame ->
        val ok = runCatching {
            frame.rewind()
            renderBitmap.copyPixelsFromBuffer(frame)
        }.isSuccess
        if (ok) {
            renderToSurface(outputSurface)
            if (frameCount++ % PREVIEW_FRAME_DIVISOR == 0L) renderToSurface(UvcPreviewBus.surface)
        }
    }

    private fun renderToSurface(target: Surface?) {
        if (target == null || !target.isValid) return
        val canvas = runCatching { target.lockCanvas(null) }.getOrNull() ?: return
        runCatching {
            canvas.drawBitmap(renderBitmap, srcRect, Rect(0, 0, canvas.width, canvas.height), null)
        }
        runCatching { target.unlockCanvasAndPost(canvas) }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            Log.i(TAG, "onAttach ${device.deviceName} cls=${runCatching { device.getInterface(0).interfaceClass }.getOrNull()}")
            if (isUvc(device)) helper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            Log.i(TAG, "onDeviceOpen firstOpen=$isFirstOpen")
            val param = UVCParam()
            runCatching { helper?.openCamera(param) }
                .onFailure { Log.w(TAG, "openCamera: $it") }
        }

        override fun onCameraOpen(device: UsbDevice) {
            val h = helper ?: return
            if (started) return
            started = true
            val surface = outputSurface
            val sizes = runCatching { h.supportedSizeList }.getOrDefault(emptyList())
            Log.i(TAG, "onCameraOpen sizes=${sizes.joinToString { "${it.width}x${it.height}/t${it.type}" }} surface=$surface valid=${surface?.isValid}")
            val mjpeg = sizes.firstOrNull { it.type == UVCCamera.UVC_VS_FRAME_MJPEG }
            if (mjpeg != null) {
                runCatching { h.previewSize = mjpeg }
                ensureBitmap(mjpeg.width, mjpeg.height)
                Log.i(TAG, "set previewSize ${mjpeg.width}x${mjpeg.height}")
            }
            runCatching { h.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RGBX) }
                .onFailure { Log.w(TAG, "setFrameCallback: $it") }
            try {
                h.startPreview()
                Log.i(TAG, "startPreview OK opened=${h.isCameraOpened}")
            } catch (e: Throwable) {
                Log.w(TAG, "startPreview FAIL: $e")
            }
        }

        override fun onCameraClose(device: UsbDevice) = Unit

        override fun onDeviceClose(device: UsbDevice) = Unit

        override fun onDetach(device: UsbDevice) = Unit

        override fun onCancel(device: UsbDevice) = Unit

        override fun onError(device: UsbDevice, e: CameraException) {
            Log.w(TAG, "uvc error: $e")
        }
    }

    private companion object {
        const val TAG = "UvcSource"
        const val FRAME_W = 1280
        const val FRAME_H = 720
        const val PREVIEW_FRAME_DIVISOR = 2L
    }
}
