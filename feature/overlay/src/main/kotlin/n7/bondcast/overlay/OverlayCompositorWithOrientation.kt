package n7.bondcast.overlay

import android.graphics.Matrix

internal class OverlayCompositorWithOrientation(
    private val origin: OverlayCompositor,
) : OverlayCompositor by origin {

    private val matrix = Matrix()
    private var cachedWidth = -1
    private var cachedHeight = -1
    private var cachedRotation = Int.MIN_VALUE
    private var cachedMirror = false

    override fun drawAll(frame: OverlayFrame) {
        val canvas = frame.canvas
        val restore = canvas.save()
        canvas.concat(matrixFor(frame))
        origin.drawAll(frame)
        canvas.restoreToCount(restore)
    }

    private fun matrixFor(frame: OverlayFrame): Matrix {
        val width = frame.size.width
        val height = frame.size.height
        val changed = width != cachedWidth ||
            height != cachedHeight ||
            frame.rotationDegrees != cachedRotation ||
            frame.isMirror != cachedMirror
        if (changed) {
            matrix.reset()
            matrix.postRotate(-frame.rotationDegrees.toFloat(), width / 2f, height / 2f)
            if (frame.isMirror) {
                matrix.postScale(-1f, 1f, width / 2f, height / 2f)
            }
            cachedWidth = width
            cachedHeight = height
            cachedRotation = frame.rotationDegrees
            cachedMirror = frame.isMirror
        }
        return matrix
    }
}
