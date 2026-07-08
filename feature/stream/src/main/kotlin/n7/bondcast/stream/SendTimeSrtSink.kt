package n7.bondcast.stream

import io.github.thibaultbee.srtdroid.core.enums.Boundary
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.MsgCtrl
import io.github.thibaultbee.srtdroid.core.models.SrtUrl.Mode
import io.github.thibaultbee.srtdroid.core.models.Stats
import io.github.thibaultbee.srtdroid.ktx.CoroutineSrtSocket
import io.github.thibaultbee.srtdroid.ktx.connect
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.endpoints.ClosedException
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.data.Packet
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.data.SrtPacket
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.AbstractSink
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.sinks.SinkConfiguration
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Копия штатного SrtSink из StreamPack 3.1.2 с одним отличием: srcTime в libsrt НЕ передаётся.
 *
 * Штатный sink кладёт в MsgCtrl.srcTime PTS кадра из MediaCodec — это другая временная база,
 * чем часы libsrt. Из-за этого sender мгновенно считал пакеты «протухшими» (pktSndDrop ≈ весь
 * поток, ретрансмиты невозможны, msSndBuf мусорный/отрицательный), а приёмник дропал каждый
 * реально потерянный пакет как безнадёжно опоздавший. Без srcTime libsrt штампует пакеты сам
 * в момент отправки, и вся TSBPD-математика становится согласованной.
 */
internal class SendTimeSrtSink(private val coroutineDispatcher: CoroutineDispatcher) : AbstractSink() {
    override val supportedSinkTypes: List<MediaSinkType> = listOf(MediaSinkType.SRT)

    private var socket: CoroutineSrtSocket? = null
    private var completionException: Throwable? = null
    private var isOnError: Boolean = false

    private var bitrate = 0L

    @Volatile
    private var firstTsUs = 0L

    @Volatile
    private var firstWallNanos = 0L

    @Volatile
    private var latestTsUs = 0L

    /**
     * Насколько пайплайн кодирования отстаёт от реального времени, мс.
     *
     * Сравниваем прогресс PTS-таймлайна пакетов с настенными часами от первого пакета сессии.
     * Когда телефон не успевает кодировать (перегрев, битрейт не по силам), таймлайн идёт
     * медленнее реального времени и отставание копится — зритель видит рывки и растущую
     * задержку. SRT-статистика этого не видит: очередь стоит ДО сокета, буфер отправки пустой.
     * Сравнивать PTS аудио с PTS видео нельзя: муксер интерливит дорожки, аудио ждёт видео.
     */
    val encoderLagMs: Int
        get() {
            if (firstTsUs == 0L) return 0
            val mediaMs = (latestTsUs - firstTsUs) / 1_000
            val wallMs = (System.nanoTime() - firstWallNanos) / 1_000_000
            return (wallMs - mediaMs).coerceAtLeast(0L).toInt()
        }

    override val metrics: Stats
        get() = socket?.bistats(clear = true, instantaneous = true)
            ?: throw IllegalStateException("Socket is not initialized")

    private val _isOpenFlow = MutableStateFlow(false)
    override val isOpenFlow = _isOpenFlow.asStateFlow()

    override fun configure(config: SinkConfiguration) {
        bitrate = config.streamConfigs.sumOf { it.startBitrate.toLong() }
    }

    override suspend fun openImpl(mediaDescriptor: MediaDescriptor) = open(SrtMediaDescriptor(mediaDescriptor))

    private suspend fun open(mediaDescriptor: SrtMediaDescriptor) {
        if (mediaDescriptor.srtUrl.mode != null) {
            require(mediaDescriptor.srtUrl.mode == Mode.CALLER) { "Invalid mode: ${mediaDescriptor.srtUrl.mode}. Only caller supported." }
        }
        if (mediaDescriptor.srtUrl.payloadSize != null) {
            require(mediaDescriptor.srtUrl.payloadSize == PAYLOAD_SIZE)
        }
        if (mediaDescriptor.srtUrl.transtype != null) {
            require(mediaDescriptor.srtUrl.transtype == Transtype.LIVE)
        }

        firstTsUs = 0L
        latestTsUs = 0L
        socket = CoroutineSrtSocket(coroutineDispatcher)
        socket?.let {
            it.setSockFlag(SockOpt.PAYLOADSIZE, PAYLOAD_SIZE)
            it.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
            completionException = null
            isOnError = false
            it.socketContext.invokeOnCompletion { t ->
                completionException = t
                _isOpenFlow.tryEmit(false)
            }
            it.connect(mediaDescriptor.srtUrl)
        }
        _isOpenFlow.emit(true)
    }

    private fun buildMsgCtrl(packet: Packet): MsgCtrl {
        val boundary = if (packet is SrtPacket) {
            when {
                packet.isFirstPacketFrame && packet.isLastPacketFrame -> Boundary.SOLO
                packet.isFirstPacketFrame -> Boundary.FIRST
                packet.isLastPacketFrame -> Boundary.LAST
                else -> Boundary.SUBSEQUENT
            }
        } else {
            null
        }
        return if (boundary != null) MsgCtrl(boundary = boundary) else MsgCtrl()
    }

    override suspend fun write(packet: Packet): Int {
        if (isOnError) {
            return -1
        }

        completionException?.let {
            isOnError = true
            throw ClosedException(it)
        }

        val socket = requireNotNull(socket) { "SrtEndpoint is not initialized" }

        if (packet.ts > 0) {
            if (firstTsUs == 0L) {
                firstTsUs = packet.ts
                firstWallNanos = System.nanoTime()
            }
            if (packet.ts > latestTsUs) latestTsUs = packet.ts
        }

        try {
            return socket.send(packet.buffer, buildMsgCtrl(packet))
        } catch (t: Throwable) {
            isOnError = true
            if (completionException != null) {
                throw ClosedException(completionException!!)
            }
            close()
            throw ClosedException(t)
        }
    }

    override suspend fun startStream() {
        val socket = requireNotNull(socket) { "SrtEndpoint is not initialized" }
        require(socket.isConnected) { "SrtEndpoint should be connected at this point" }

        socket.setSockFlag(SockOpt.MAXBW, 0L)
        socket.setSockFlag(SockOpt.INPUTBW, bitrate)
    }

    override suspend fun stopStream() {
    }

    override suspend fun close() {
        socket?.close()
    }

    companion object {
        private const val PAYLOAD_SIZE = 1316
    }
}
