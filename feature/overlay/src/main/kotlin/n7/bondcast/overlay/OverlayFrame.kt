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

    /**
     * Левый верхний угол вертикального кадра в координатах холста — начало отсчёта для оверлеев.
     *
     * Компоситор поворачивает холст вокруг центра буфера, поэтому при повороте на 90/270 буфер и
     * вертикальный кадр меняются сторонами, и (0,0) уезжает за границу картинки. Смещение на
     * полуразницу сторон возвращает начало отсчёта в угол кадра; при повороте 0/180 оно нулевое.
     */
    public val uprightLeft: Float = (size.width - uprightSize.width) / 2f
    public val uprightTop: Float = (size.height - uprightSize.height) / 2f
}
