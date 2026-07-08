package n7.bondcast.overlay

import android.graphics.Canvas
import android.util.Size

public interface OverlayCompositor {
    public fun register(overlay: StreamOverlay)
    public fun unregister(overlay: StreamOverlay)
    public fun hasOverlays(): Boolean
    public fun drawAll(canvas: Canvas, frame: Size)
}

public fun overlayCompositor(): OverlayCompositor = OverlayCompositorImpl()
