package n7.bondcast

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val White = Color(0xFFFFFFFF)
private val Blurple = Color(0xFF5865F2)
private val BlurplePressed = Color(0xFF4752C4)
private val Green = Color(0xFF23A55A)
private val Red = Color(0xFFDA373C)
private val Yellow = Color(0xFFF0B232)
private val Background = Color(0xFF0B0B0D)
private val CardSurface = Color(0xFF1E1F22)
private val Elevated = Color(0xFF2B2D31)
private val InputBackground = Color(0xFF111214)
private val TextPrimary = Color(0xFFF2F3F5)
private val TextSecondary = Color(0xFFB5BAC1)
private val TextMuted = Color(0xFF80848E)
private val Link = Color(0xFF00A8FC)
private val Divider = Color(0xFF2B2D31)

private val ColorScheme = darkColorScheme(
    primary = Blurple,
    onPrimary = White,
    primaryContainer = BlurplePressed,
    secondary = Elevated,
    onSecondary = TextPrimary,
    tertiary = Green,
    onTertiary = White,
    error = Red,
    onError = White,
    background = Background,
    onBackground = TextPrimary,
    surface = CardSurface,
    onSurface = TextPrimary,
    surfaceVariant = Elevated,
    onSurfaceVariant = TextSecondary,
    outline = Divider,
    outlineVariant = Divider,
)

public object DiscordColors {
    val background = Background
    val card = CardSurface
    val elevated = Elevated
    val inputBackground = InputBackground
    val textPrimary = TextPrimary
    val textSecondary = TextSecondary
    val textMuted = TextMuted
    val link = Link
    val divider = Divider
    val blurple = Blurple
    val green = Green
    val danger = Red
    val yellow = Yellow
    fun temperature(heat: Float): Color = temperatureColor(heat)
}

public fun temperatureColor(heat: Float): Color {
    val clamped = heat.coerceIn(0f, 1f)
    return Color.hsv(240f - clamped * 240f, 0.9f, 1f)
}

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
