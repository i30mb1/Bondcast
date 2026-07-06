package n7.bondcast.uvc

import android.util.Size
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider

internal class UvcSourceInfoProvider : ISourceInfoProvider {
    override val rotationDegrees: Int = 0
    override val isMirror: Boolean = false
    override fun getSurfaceSize(targetResolution: Size): Size = Size(1280, 720)
}
