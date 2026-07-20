package n7.bondcast.overlay

import android.graphics.Canvas
import android.util.Size

public data class OverlayFrame(
    public val canvas: Canvas,
    public val size: Size,
    public val rotationDegrees: Int,
    public val isMirror: Boolean,
) {
    public val uprightSize: Size =
        if (rotationDegrees == 90 || rotationDegrees == 270) Size(size.height, size.width) else size
}
