package n7.bondcast

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blurple = Color(0xFF5865F2)
private val Green = Color(0xFF57F287)
private val Red = Color(0xFFED4245)
private val White = Color(0xFFFFFFFF)
private val DarkGrey = Color(0xFF2C2F33)
private val DarkBackground = Color(0xFF23272A)
private val OldBlurple = Color(0xFF7289DA)

private val ColorScheme = darkColorScheme(
    primary = Blurple,
    secondary = OldBlurple,
    tertiary = Green,
    error = Red,
    background = DarkBackground,
    surface = DarkGrey,
    onPrimary = White,
    onSecondary = White,
    onTertiary = DarkBackground,
    onBackground = White,
    onSurface = White,
)

@Composable
public fun AppTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = Typography(),
        content = content,
    )
}
