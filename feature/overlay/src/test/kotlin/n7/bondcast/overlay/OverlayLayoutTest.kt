package n7.bondcast.overlay

import org.junit.Test
import kotlin.test.assertEquals

class OverlayLayoutTest {

    @Test
    fun topStartInsetsBySafeAreaAndMargin() {
        val p = OverlayLayout.anchor(1920, 1080, Anchor.TOP_START, 10f, 0.05f)
        assertEquals(1920 * 0.05f + 10f, p.x)
        assertEquals(1080 * 0.05f + 10f, p.y)
    }

    @Test
    fun centerIsMiddle() {
        val p = OverlayLayout.anchor(1920, 1080, Anchor.CENTER, 0f, 0f)
        assertEquals(1920 / 2f, p.x)
        assertEquals(1080 / 2f, p.y)
    }

    @Test
    fun bottomEndInsetsFromFarEdges() {
        val p = OverlayLayout.anchor(1000, 1000, Anchor.BOTTOM_END, 0f, 0.1f)
        val inset = 1000 * 0.1f + 0f
        assertEquals(1000 - inset, p.x)
        assertEquals(1000 - inset, p.y)
    }

    @Test
    fun scaleRelativeToDesignHeight() {
        assertEquals(1f, OverlayLayout.scale(1080))
        assertEquals(540 / 1080f, OverlayLayout.scale(540))
    }
}
