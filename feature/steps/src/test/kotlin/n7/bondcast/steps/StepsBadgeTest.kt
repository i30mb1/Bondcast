package n7.bondcast.steps

import n7.bondcast.overlay.OverlayPoint
import org.junit.Test
import kotlin.test.assertEquals

class StepsBadgeTest {

    @Test
    fun sizeIsMeasuredInDesignUnitsOfFrameHeight() {
        // кадр ровно в дизайн-высоту: рамка занимает свои дизайн-пиксели один в один
        assertEquals(StepsBadge.WIDTH / 1920f, StepsBadge.widthFraction(1920f, 1080f))
        assertEquals(StepsBadge.HEIGHT / 1080f, StepsBadge.heightFraction())
    }

    @Test
    fun sizeScalesWithFrameHeightNotWidth() {
        // вдвое меньший кадр — та же доля: рамка ужимается вместе с картинкой
        assertEquals(
            StepsBadge.widthFraction(1920f, 1080f),
            StepsBadge.widthFraction(960f, 540f),
        )
    }

    @Test
    fun degenerateFrameDoesNotDivideByZero() {
        assertEquals(0f, StepsBadge.widthFraction(0f, 1080f))
    }

    @Test
    fun keepsBadgeInsideFrame() {
        val out = StepsBadge.coerce(OverlayPoint(5f, -2f), 1920f, 1080f)
        assertEquals(1f - StepsBadge.widthFraction(1920f, 1080f), out.x)
        assertEquals(0f, out.y)
    }

    @Test
    fun leavesPositionInsideFrameUntouched() {
        val inside = OverlayPoint(0.25f, 0.5f)
        assertEquals(inside, StepsBadge.coerce(inside, 1920f, 1080f))
    }

    @Test
    fun oldDefaultMovesDownToTheNewOne() {
        assertEquals(
            OverlayPoint(StepsBadge.DEFAULT_X, StepsBadge.DEFAULT_Y),
            StepsBadge.migrated(OverlayPoint(0.04f, 0.08f)),
        )
    }

    @Test
    fun draggedPositionSurvivesTheDefaultMove() {
        // рамку уже двигали руками — переезд дефолта не должен её трогать
        val moved = OverlayPoint(0.6f, 0.1f)
        assertEquals(moved, StepsBadge.migrated(moved))
    }
}
