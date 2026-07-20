package n7.bondcast.overlay

import android.graphics.Color
import android.graphics.Paint

public class DebugOverlay : StreamOverlay {

    private val bar = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val box = Paint().apply { color = Color.MAGENTA }
    private val label = Paint().apply {
        color = Color.WHITE
        textSize = 96f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private var frames = 0L

    override fun draw(frame: OverlayFrame) {
        frames++
        val canvas = frame.canvas
        val width = frame.uprightSize.width.toFloat()
        canvas.drawRect(0f, 0f, width, 260f, bar)
        canvas.drawRect(40f, 40f, 220f, 220f, box)
        canvas.drawText("OVERLAY #$frames", 260f, 175f, label)
    }
}
