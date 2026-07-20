package n7.bondcast.overlay

public data class OverlayPoint(
    public val x: Float,
    public val y: Float,
)

public object OverlayLayout {

    public const val DESIGN_HEIGHT: Float = 1080f

    public fun scale(uprightHeight: Int): Float = uprightHeight / DESIGN_HEIGHT

    public fun anchor(
        width: Int,
        height: Int,
        anchor: Anchor,
        marginPx: Float,
        safeAreaFraction: Float,
    ): OverlayPoint {
        val insetX = width * safeAreaFraction + marginPx
        val insetY = height * safeAreaFraction + marginPx
        val x = when (anchor) {
            Anchor.TOP_START, Anchor.MID_START, Anchor.BOTTOM_START -> insetX
            Anchor.TOP_CENTER, Anchor.CENTER, Anchor.BOTTOM_CENTER -> width / 2f
            Anchor.TOP_END, Anchor.MID_END, Anchor.BOTTOM_END -> width - insetX
        }
        val y = when (anchor) {
            Anchor.TOP_START, Anchor.TOP_CENTER, Anchor.TOP_END -> insetY
            Anchor.MID_START, Anchor.CENTER, Anchor.MID_END -> height / 2f
            Anchor.BOTTOM_START, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_END -> height - insetY
        }
        return OverlayPoint(x, y)
    }
}
