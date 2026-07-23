package n7.bondcast.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import n7.bondcast.feature.chat.impl.R

/** Речевой пузырёк — глиф кнопки чата в рейле, тонкой линией. */
@Composable
public fun ChatIcon(color: Color, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.chat),
        contentDescription = null,
        tint = color,
        modifier = modifier.size(22.dp),
    )
}
