package n7.bondcast.stream

import android.content.Context
import android.util.Size
import io.github.thibaultbee.srtdroid.core.models.Stats
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
            sndBufferMs = stats.msSndBuf,
            bandwidthKbps = (stats.mbpsBandwidth * 1000).roundToInt(),
        )
    }

    override suspend fun stopStream() {
        val current = streamer ?: return
        streamerLock.withLock {
            runCatching { current.stopStream() }
            runCatching { current.close() }
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
