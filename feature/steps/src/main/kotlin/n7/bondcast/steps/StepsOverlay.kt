package n7.bondcast.steps

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import n7.bondcast.overlay.OverlayFrame
import n7.bondcast.overlay.OverlayLayout
import n7.bondcast.overlay.OverlayPoint
import n7.bondcast.overlay.StreamOverlay

/**
 * Рамка со счётчиком шагов, запекаемая в кадр.
 *
 * Рисуется на потоке `OverlayEffect`, а число и позицию меняет UI-поток — отсюда `@Volatile`:
 * кадром позже, чем случился шаг, но без синхронизации на каждом кадре.
 */
public class StepsOverlay : StreamOverlay {

    @Volatile
    public var steps: Int = 0

    @Volatile
    public var position: OverlayPoint = OverlayPoint(StepsBadge.DEFAULT_X, StepsBadge.DEFAULT_Y)

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 255, 255, 255)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val box = RectF()
    private val textBounds = Rect()

    override fun draw(frame: OverlayFrame) {
        val scale = OverlayLayout.scale(frame.uprightSize.height)
        if (scale <= 0f) return
        val frameWidth = frame.uprightSize.width.toFloat()
        val frameHeight = frame.uprightSize.height.toFloat()
        val safe = StepsBadge.coerce(position, frameWidth, frameHeight)

        val left = frame.uprightLeft + safe.x * frameWidth
        val top = frame.uprightTop + safe.y * frameHeight
        box.set(left, top, left + StepsBadge.WIDTH * scale, top + StepsBadge.HEIGHT * scale)

        val canvas = frame.canvas
        val radius = CORNER * scale
        canvas.drawRoundRect(box, radius, radius, background)
        border.strokeWidth = BORDER * scale
        canvas.drawRoundRect(box, radius, radius, border)

        drawPrints(canvas, box.left + (GLYPH_MARGIN + GLYPH_LENGTH / 2f) * scale, box.centerY(), GLYPH_LENGTH * scale)

        val text = StepsMath.format(steps)
        val textLeft = box.left + (GLYPH_MARGIN * 2f + GLYPH_LENGTH) * scale
        val textRight = box.right - GLYPH_MARGIN * scale
        label.textSize = fittingTextSize(text, textRight - textLeft, scale)
        // по чернильным границам, а не по ascent/descent: у цифр нет выносных элементов,
        // и центровка по метрикам шрифта заметно сажает число вниз
        label.getTextBounds(text, 0, text.length, textBounds)
        canvas.drawText(text, (textLeft + textRight) / 2f, box.centerY() - textBounds.exactCenterY(), label)
    }

    /** Уменьшает кегль, если число переросло рамку (за сутки бывает и шестизначное). */
    private fun fittingTextSize(text: String, available: Float, scale: Float): Float {
        val preferred = TEXT_SIZE * scale
        label.textSize = preferred
        val width = label.measureText(text)
        return if (width <= available) preferred else preferred * available / width
    }

    private fun drawPrints(canvas: Canvas, centerX: Float, centerY: Float, length: Float) {
        drawPrint(canvas, centerX - length * 0.20f, centerY + length * 0.12f, length, -PRINT_TILT)
        drawPrint(canvas, centerX + length * 0.20f, centerY - length * 0.12f, length, PRINT_TILT)
    }

    /** Один след: подушка стопы плюс пятка. */
    private fun drawPrint(canvas: Canvas, centerX: Float, centerY: Float, length: Float, tilt: Float) {
        val restore = canvas.save()
        canvas.rotate(tilt, centerX, centerY)
        canvas.drawOval(
            centerX - length * 0.20f,
            centerY - length * 0.50f,
            centerX + length * 0.20f,
            centerY + length * 0.14f,
            glyph,
        )
        canvas.drawOval(
            centerX - length * 0.14f,
            centerY + length * 0.24f,
            centerX + length * 0.14f,
            centerY + length * 0.50f,
            glyph,
        )
        canvas.restoreToCount(restore)
    }

    public companion object {
        private const val CORNER = 18f
        private const val BORDER = 3f
        private const val GLYPH_MARGIN = 18f
        private const val GLYPH_LENGTH = 46f
        private const val TEXT_SIZE = 56f
        private const val PRINT_TILT = 14f
    }
}
