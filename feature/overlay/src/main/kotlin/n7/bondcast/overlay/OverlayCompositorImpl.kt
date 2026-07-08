package n7.bondcast.overlay

import android.graphics.Canvas
import android.util.Size
import java.util.concurrent.CopyOnWriteArrayList

internal class OverlayCompositorImpl : OverlayCompositor {

    private val overlays = CopyOnWriteArrayList<StreamOverlay>()

    override fun register(overlay: StreamOverlay) {
        overlays.addIfAbsent(overlay)
    }

    override fun unregister(overlay: StreamOverlay) {
        overlays.remove(overlay)
    }

    override fun hasOverlays(): Boolean = overlays.isNotEmpty()

    override fun drawAll(canvas: Canvas, frame: Size) {
        for (overlay in overlays) overlay.draw(canvas, frame)
    }
}
