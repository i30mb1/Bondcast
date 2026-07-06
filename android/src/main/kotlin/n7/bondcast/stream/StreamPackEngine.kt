package n7.bondcast.stream

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import io.github.thibaultbee.srtdroid.core.models.Stats
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.core.streamers.single.cameraSingleStreamer
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import n7.bondcast.settings.StreamSettings
import n7.bondcast.uvc.UvcVideoSourceFactory
import kotlin.math.roundToInt

internal class StreamPackEngine(private val context: Context) : StreamEngine {

    private var streamer: SingleStreamer? = null
    private var appliedSettings: StreamSettings? = null
    private val streamerLock = Mutex()

    override val lastError: Throwable?
        get() = streamer?.throwableFlow?.value

    override suspend fun prepare(settings: StreamSettings) {
        val current = streamer ?: cameraSingleStreamer(context).also { streamer = it }
        if (appliedSettings == settings) return
        if (current.isStreamingFlow.value) return
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
        streamerLock.withLock {
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
        )
    }

    override suspend fun setVideoBitrate(kbps: Int) {
        val current = streamer ?: return
        streamerLock.withLock {
            if (!current.isStreamingFlow.value) return@withLock
            runCatching { current.videoEncoder?.bitrate = kbps * 1000 }
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

    override suspend fun switchCamera(cameraId: String) {
        val current = streamer ?: return
        streamerLock.withLock {
            val factory = if (cameraId == USB_CAMERA_ID) {
                UvcVideoSourceFactory()
            } else {
                CameraSourceFactory(cameraId)
            }
            runCatching { current.setVideoSource(factory) }
                .onFailure { Log.w("StreamCamera", "switchCamera($cameraId): $it") }
        }
    }

    private fun cameraManager(): CameraManager? = context.getSystemService(CameraManager::class.java)

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

    override suspend fun bindPreview(view: PreviewView) {
        view.setVideoSourceProvider(streamer)
    }

    override suspend fun release() {
        streamer?.release()
        streamer = null
        appliedSettings = null
    }
}
