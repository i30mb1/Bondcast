package n7.bondcast.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import n7.bondcast.DiscordColors
import n7.bondcast.feature.stream.R
import n7.bondcast.ui.street.StreetPanelScaffold

/**
 * Окно всех оверлеев, запекаемых в кадр.
 *
 * Пульт, а не экран показаний: у каждого оверлея свой блок с парой кнопок, поэтому окно компактное
 * (`fillHeight = false`) — во всю высоту оно закрывало бы кадр пустой подложкой. Новый оверлей =
 * ещё одна секция в [content], трогать само окно не нужно.
 */
@Composable
internal fun OverlaysPanel(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    StreetPanelScaffold(
        title = stringResource(R.string.overlays_panel_title),
        onClose = onClose,
        modifier = modifier,
        leading = { OverlaysIcon(color = DiscordColors.accent) },
        info = stringResource(R.string.overlays_info_main),
        fillHeight = false,
        content = content,
    )
}
