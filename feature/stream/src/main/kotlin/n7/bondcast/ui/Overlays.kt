package n7.bondcast.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import n7.bondcast.ui.street.panelEnter
import n7.bondcast.ui.street.panelExit
import n7.bondcast.ui.street.shakeOnAppear

/**
 * Оконный менеджер стрим-экрана: одна панель за раз, эксклюзивно.
 *
 * Тап по активной иконке закрывает панель (toggle), тап мимо — [closeAll].
 * Новое окошко в будущем = toggle(id) на кнопке + PanelSlot(isOpen(id)) в рендере.
 */
internal class PanelManager {
    var current by mutableStateOf<String?>(null)
        private set

    val anyOpen: Boolean get() = current != null

    fun isOpen(id: String): Boolean = current == id

    fun toggle(id: String) {
        current = if (current == id) null else id
    }

    /** Гарантированно показывает окно (для советов-ссылок). */
    fun open(id: String) {
        current = id
    }

    fun close(id: String) {
        if (current == id) current = null
    }

    fun closeAll() {
        current = null
    }
}

/** Панель слева поверх видео: spring-въезд при появлении, spring-выезд при закрытии. */
@Composable
internal fun BoxScope.PanelSlot(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = panelEnter,
        exit = panelExit,
        modifier = Modifier
            .align(Alignment.TopStart)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        Box(shakeOnAppear()) { content() }
    }
}
