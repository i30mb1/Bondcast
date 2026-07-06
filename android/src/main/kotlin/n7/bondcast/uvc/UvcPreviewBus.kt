package n7.bondcast.uvc

import android.view.Surface

public object UvcPreviewBus {

    @Volatile
    var surface: Surface? = null
        private set

    @Volatile
    var listener: ((Surface?) -> Unit)? = null

    fun set(value: Surface?) {
        surface = value
        listener?.invoke(value)
    }
}
