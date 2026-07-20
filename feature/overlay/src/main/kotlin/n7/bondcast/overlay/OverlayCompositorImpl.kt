package n7.bondcast.overlay

internal class OverlayCompositorImpl : OverlayCompositor {

    private val lock = Any()
    private val entries = mutableListOf<Entry>()

    @Volatile
    private var ordered: Array<StreamOverlay> = emptyArray()

    override fun register(overlay: StreamOverlay, z: Int) {
        synchronized(lock) {
            if (entries.any { it.overlay === overlay }) return
            entries.add(Entry(overlay, z))
            rebuild()
        }
    }

    override fun unregister(overlay: StreamOverlay) {
        synchronized(lock) {
            if (entries.removeAll { it.overlay === overlay }) rebuild()
        }
    }

    override fun hasOverlays(): Boolean = ordered.isNotEmpty()

    override fun drawAll(frame: OverlayFrame) {
        val snapshot = ordered
        for (i in snapshot.indices) snapshot[i].draw(frame)
    }

    private fun rebuild() {
        entries.sortBy { it.z }
        ordered = Array(entries.size) { entries[it].overlay }
    }

    private class Entry(val overlay: StreamOverlay, val z: Int)
}
