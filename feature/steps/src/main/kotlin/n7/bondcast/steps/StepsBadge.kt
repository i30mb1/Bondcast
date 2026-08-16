package n7.bondcast.steps

import n7.bondcast.overlay.OverlayLayout
import n7.bondcast.overlay.OverlayPoint

/**
 * Рамка шагомера в кадре.
 *
 * Размеры заданы в дизайн-единицах кадра высотой [OverlayLayout.DESIGN_HEIGHT] и масштабируются по
 * высоте кадра — так рамка одинаково выглядит и в 1080p, и в 720p. Позиция хранится долями кадра:
 * превью на экране вписано в то же соотношение, что и эфир, поэтому доля на экране равна доле в
 * эфире, и рамка встаёт у зрителей ровно туда, куда её перетащили (тот же приём, что у врезки PiP).
 */
public object StepsBadge {

    public const val WIDTH: Float = 300f
    public const val HEIGHT: Float = 96f

    /**
     * Место по умолчанию — низ слева.
     *
     * Верх кадра занят системной строкой состояния и наклейкой «не в эфире»: там рамку не видно
     * целиком и не ухватить пальцем. Низ слева свободен — чат уходит туда только когда включён,
     * кнопка «в эфир» стоит по центру, а рейл кнопок вообще лежит за пределами кадра.
     */
    public const val DEFAULT_X: Float = 0.04f
    public const val DEFAULT_Y: Float = 0.78f

    /** Место по умолчанию до переезда вниз — рамка там перекрывалась наклейкой «не в эфире». */
    private val LEGACY_DEFAULT = OverlayPoint(0.04f, 0.08f)

    /**
     * Переезд дефолта: у тех, кто уже включал шагомер, старое место успело сохраниться в настройках,
     * и новый дефолт сам по себе их бы не сдвинул. Двигаем только нетронутое место — если рамку уже
     * перетаскивали куда-то ещё, оставляем как есть.
     */
    public fun migrated(position: OverlayPoint): OverlayPoint = if (position == LEGACY_DEFAULT) OverlayPoint(DEFAULT_X, DEFAULT_Y) else position

    /** Доля ширины кадра, которую занимает рамка. */
    public fun widthFraction(frameWidth: Float, frameHeight: Float): Float = if (frameWidth <= 0f) 0f else WIDTH * frameHeight / OverlayLayout.DESIGN_HEIGHT / frameWidth

    /** Доля высоты кадра, которую занимает рамка. */
    public fun heightFraction(): Float = HEIGHT / OverlayLayout.DESIGN_HEIGHT

    /** Не даёт утащить рамку за границу кадра — иначе часть числа обрежется в эфире. */
    public fun coerce(position: OverlayPoint, frameWidth: Float, frameHeight: Float): OverlayPoint {
        val maxX = (1f - widthFraction(frameWidth, frameHeight)).coerceAtLeast(0f)
        val maxY = (1f - heightFraction()).coerceAtLeast(0f)
        return OverlayPoint(position.x.coerceIn(0f, maxX), position.y.coerceIn(0f, maxY))
    }
}
