package n7.bondcast.camerax

/**
 * Раскладка врезки второй камеры — в долях кадра, а не в пикселях экрана.
 *
 * Доли, потому что одна и та же раскладка применяется и к превью, и к эфиру: CameraX кладёт её в
 * [androidx.camera.core.CompositionSettings], который работает в нормализованных координатах. Так
 * позиция совпадает у стримера и у зрителей независимо от размера экрана.
 *
 * @param offsetX смещение левого края врезки, 0..1 от ширины кадра
 * @param offsetY смещение верхнего края врезки, 0..1 от высоты кадра
 * @param scale размер врезки относительно кадра
 * @param mainIsBack основная (полноэкранная) камера — тыловая; false — местами поменяны
 */
public data class PipLayout(
    val offsetX: Float = DEFAULT_OFFSET_X,
    val offsetY: Float = DEFAULT_OFFSET_Y,
    val scale: Float = DEFAULT_SCALE,
    val mainIsBack: Boolean = true,
) {
    /** Ограничивает врезку кадром: её правый/нижний край не должен уезжать за границу. */
    public fun coerced(): PipLayout = copy(
        offsetX = offsetX.coerceIn(0f, 1f - scale),
        offsetY = offsetY.coerceIn(0f, 1f - scale),
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
    )

    /**
     * X центра врезки в NDC CameraX: начало координат в центре кадра, диапазон -1..1.
     *
     * Наши доли считаются от левого края, а [androidx.camera.core.CompositionSettings] — от центра,
     * поэтому без пересчёта врезка уезжает относительно рамки-хвата в превью.
     */
    public fun ndcCenterX(): Float = 2f * (offsetX + scale / 2f) - 1f

    /** Y центра врезки в NDC. Знак инвертирован: у нас Y растёт вниз, в NDC — вверх. */
    public fun ndcCenterY(): Float = 1f - 2f * (offsetY + scale / 2f)

    public companion object {
        public const val MIN_SCALE: Float = 0.15f
        public const val MAX_SCALE: Float = 0.5f

        private const val DEFAULT_SCALE = 0.28f

        // правый нижний угол с небольшим полем — привычное место врезки
        private const val DEFAULT_OFFSET_X = 1f - DEFAULT_SCALE - 0.03f
        private const val DEFAULT_OFFSET_Y = 1f - DEFAULT_SCALE - 0.05f
    }
}
