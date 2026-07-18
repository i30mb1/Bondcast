package n7.bondcast.stream

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import io.github.thibaultbee.srtdroid.core.models.Stats
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.createDefaultTsServiceInfo
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.CompositeEndpointFactory
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.muxers.ts.TsMuxer
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.core.streamers.single.cameraSingleStreamer
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import n7.bondcast.camerax.CameraXVideoSourceFactory
import n7.bondcast.overlay.OverlayCompositor
import n7.bondcast.settings.StreamSettings
import n7.bondcast.uvc.UvcVideoSourceFactory
import kotlin.math.roundToInt

internal class StreamPackEngine(
    private val context: Context,
    private val overlayCompositor: OverlayCompositor,
    // стартовый источник поднимается по desired-камере контроллера, а не по «дефолтной тыловой»
    private val initialCameraId: () -> String? = { null },
) : StreamEngine {

    private var streamer: SingleStreamer? = null
    private var sink: SendTimeSrtSink? = null
    private var appliedSettings: StreamSettings? = null
    private val streamerLock = Mutex()

    override val lastError: Throwable?
        get() = streamer?.throwableFlow?.value

    @SuppressLint("MissingPermission")
    override suspend fun prepare(settings: StreamSettings): Unit = streamerLock.withLock {
        val current = streamer ?: run {
            // свой sink вместо штатного: без srcTime из MediaCodec PTS (см. SendTimeSrtSink)
            val newSink = SendTimeSrtSink(Dispatchers.IO)
            cameraSingleStreamer(
                context,
                endpointFactory = CompositeEndpointFactory(
                    TsMuxer().apply { addService(createDefaultTsServiceInfo()) },
                    newSink,
                ),
            ).also {
                streamer = it
                sink = newSink
                val camId = initialCameraId() ?: defaultCameraId()
                val res = runCatching { it.setVideoSource(sourceFactory(camId)) }
                Log.i("StreamCamera", "init setVideoSource($camId) -> ${res.exceptionOrNull()?.toString() ?: "ok"}")
            }
        }
        if (appliedSettings == settings) return@withLock
        if (current.isStreamingFlow.value) return@withLock
        current.setConfig(
            AudioConfig(startBitrate = 128_000),
            VideoConfig(
                mimeType = settings.videoCodec.mime,
                startBitrate = settings.videoBitrateKbps * 1000,
                resolution = Size(settings.width, settings.height),
                fps = settings.fps,
            ),
        )
        appliedSettings = settings
    }

    override suspend fun startStream(settings: StreamSettings) {
        prepare(settings)
        val current = requireNotNull(streamer)
        // open() блокирует до конца SRT-хендшейка (секунды при недоступном сервере) — держим его ВНЕ
        // streamerLock, иначе switchCamera/setVideoBitrate висят до таймаута коннекта
        current.open(
            SrtMediaDescriptor(
                host = settings.host,
                port = settings.port,
                streamId = settings.streamId,
                passPhrase = settings.passphrase.ifBlank { null },
                latency = settings.latencyMs,
            ),
        )
        current.startStream()
    }

    override suspend fun awaitDisconnect() {
        val current = streamer ?: return
        current.isOpenFlow.first { isOpen -> !isOpen }
    }

    override suspend fun readStats(): StreamStats? = streamerLock.withLock {
        val current = streamer ?: return@withLock null
        if (!current.isOpenFlow.value) return@withLock null
        val stats = runCatching { current.endpoint.metrics as? Stats }.getOrNull() ?: return@withLock null
        StreamStats(
            sendRateKbps = (stats.mbpsSendRate * 1000).roundToInt(),
            rttMs = stats.msRTT.roundToInt(),
            pktLossTotal = stats.pktSndLossTotal,
            pktRetransTotal = stats.pktRetransTotal,
            pktDropTotal = stats.pktSndDropTotal,
            sndBufferMs = stats.msSndBuf,
            bandwidthKbps = (stats.mbpsBandwidth * 1000).roundToInt(),
            encoderLagMs = sink?.encoderLagMs ?: 0,
        )
    }

    override suspend fun setVideoBitrate(kbps: Int) {
        val current = streamer ?: return
        streamerLock.withLock {
            if (!current.isStreamingFlow.value) return@withLock
            runCatching { current.videoEncoder?.bitrate = kbps * 1000 }
            runCatching { sink?.setInputBandwidth((kbps * 1000 + 128_000).toLong()) }
        }
    }

    override fun availableCameras(): List<CameraOption> {
        val manager = cameraManager() ?: return emptyList()
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val front = mutableListOf<String>()
        val back = mutableListOf<String>()
        for (id in ids) {
            when (facingOf(manager, id)) {
                CameraCharacteristics.LENS_FACING_FRONT -> front.add(id)
                CameraCharacteristics.LENS_FACING_BACK -> back.add(id)
            }
        }
        val defaultId = back.firstOrNull() ?: ids.firstOrNull()
        val options = mutableListOf<CameraOption>()
        dedupByFocal(manager, front, defaultId).forEachIndexed { i, id ->
            options.add(CameraOption(id, if (i == 0) "Фронт" else "Фронт ${i + 1}", true))
        }
        val backSorted = dedupByFocal(manager, back, defaultId).sortedBy { focalOf(manager, it) ?: Float.MAX_VALUE }
        backSorted.forEachIndexed { i, id ->
            options.add(CameraOption(id, backLabel(i, backSorted.size), false))
        }
        options.add(CameraOption(USB_CAMERA_ID, "USB", false))
        return options
    }

    override suspend fun switchCamera(cameraId: String): Result<Unit> {
        // streamer ещё не создан — источник поставит prepare() по desired-камере (initialCameraId),
        // поэтому это не ошибка: desired будет учтён, откатывать currentCamera не нужно
        val current = streamer ?: return Result.success(Unit)
        return streamerLock.withLock {
            runCatching { current.setVideoSource(sourceFactory(cameraId)) }
                .map { }
                .onFailure { Log.w("StreamCamera", "switchCamera($cameraId): $it") }
        }
    }

    private fun sourceFactory(cameraId: String): IVideoSourceInternal.Factory = if (cameraId == USB_CAMERA_ID) {
        UvcVideoSourceFactory()
    } else {
        CameraXVideoSourceFactory(cameraId, overlayCompositor)
    }

    private fun cameraManager(): CameraManager? = context.getSystemService(CameraManager::class.java)

    private fun defaultCameraId(): String {
        val manager = cameraManager() ?: return "0"
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val back = ids.firstOrNull { facingOf(manager, it) == CameraCharacteristics.LENS_FACING_BACK }
        return back ?: ids.firstOrNull() ?: "0"
    }

    private fun facingOf(manager: CameraManager, id: String): Int? = runCatching {
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
    }.getOrNull()

    private fun focalOf(manager: CameraManager, id: String): Float? = runCatching {
        manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull()
    }.getOrNull()

    private fun dedupByFocal(manager: CameraManager, ids: List<String>, preferId: String?): List<String> {
        val ordered = ids.sortedByDescending { it == preferId }
        val seen = HashSet<Int>()
        val out = mutableListOf<String>()
        for (id in ordered) {
            val key = focalOf(manager, id)?.let { (it * 10).toInt() } ?: (-1 - out.size)
            if (seen.add(key)) out.add(id)
        }
        return out.sortedBy { ids.indexOf(it) }
    }

    private fun backLabel(index: Int, count: Int): String = when {
        count <= 1 -> "Осн"
        count == 2 -> if (index == 0) "Ультра" else "Осн"
        index == 0 -> "Ультра"
        index == count - 1 -> "Теле"
        index == 1 -> "Осн"
        else -> "Осн $index"
    }

    override suspend fun stopStream() {
        val current = streamer ?: return
        streamerLock.withLock {
            runCatching { current.stopStream() }
            runCatching { current.close() }
            appliedSettings = null
        }
    }

    override suspend fun release() = streamerLock.withLock {
        runCatching { streamer?.release() }
        streamer = null
        sink = null
        appliedSettings = null
    }
}
