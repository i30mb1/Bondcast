package n7.bondcast.overlay

import android.graphics.Canvas
import android.util.Size

public interface StreamOverlay {
    public fun draw(canvas: Canvas, frame: Size)
}
