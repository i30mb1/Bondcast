package n7.bondcast.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Иконки экрана сканера: обводкой, той же толщиной и скруглением, что [n7.bondcast.ui.street.BackIcon]
 * и [n7.bondcast.ui.street.InfoIcon] — чтобы рейл выглядел одним набором.
 */
private fun DrawScope.streetStroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Молния — включение фонарика. */
@Composable
internal fun FlashIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.55f, h * 0.10f)
            lineTo(w * 0.20f, h * 0.55f)
            lineTo(w * 0.45f, h * 0.55f)
            lineTo(w * 0.40f, h * 0.90f)
            lineTo(w * 0.75f, h * 0.43f)
            lineTo(w * 0.50f, h * 0.43f)
            close()
        }
        drawPath(path, color, style = streetStroke(w * 0.10f))
    }
}

/** Галочка — код прочитан. */
@Composable
internal fun CheckIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = streetStroke(w * 0.12f)
        drawLine(color, Offset(w * 0.20f, h * 0.52f), Offset(w * 0.42f, h * 0.74f), stroke.width, stroke.cap)
        drawLine(color, Offset(w * 0.42f, h * 0.74f), Offset(w * 0.80f, h * 0.28f), stroke.width, stroke.cap)
    }
}

/** Перечёркнутая камера — кадра нет вовсе. */
@Composable
internal fun NoCameraIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = streetStroke(w * 0.08f)
        val body = Path().apply {
            moveTo(w * 0.10f, h * 0.34f)
            lineTo(w * 0.30f, h * 0.34f)
            lineTo(w * 0.37f, h * 0.22f)
            lineTo(w * 0.63f, h * 0.22f)
            lineTo(w * 0.70f, h * 0.34f)
            lineTo(w * 0.90f, h * 0.34f)
            lineTo(w * 0.90f, h * 0.78f)
            lineTo(w * 0.10f, h * 0.78f)
            close()
        }
        drawPath(body, color, style = stroke)
        drawCircle(color, radius = w * 0.15f, center = center, style = stroke)
        drawLine(color, Offset(w * 0.13f, h * 0.13f), Offset(w * 0.87f, h * 0.87f), stroke.width, stroke.cap)
    }
}

/** Стрелка вправо — уводит на лендинг. */
@Composable
internal fun ArrowIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = streetStroke(w * 0.11f)
        drawLine(color, Offset(w * 0.16f, h * 0.5f), Offset(w * 0.84f, h * 0.5f), stroke.width, stroke.cap)
        drawLine(color, Offset(w * 0.84f, h * 0.5f), Offset(w * 0.59f, h * 0.27f), stroke.width, stroke.cap)
        drawLine(color, Offset(w * 0.84f, h * 0.5f), Offset(w * 0.59f, h * 0.73f), stroke.width, stroke.cap)
    }
}
