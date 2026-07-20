package n7.bondcast.overlay

public interface OverlayCompositor {
    public fun register(overlay: StreamOverlay, z: Int = 0)
    public fun unregister(overlay: StreamOverlay)
    public fun hasOverlays(): Boolean
    public fun drawAll(frame: OverlayFrame)
}

public fun overlayCompositor(): OverlayCompositor =
    OverlayCompositorWithOrientation(OverlayCompositorImpl())
