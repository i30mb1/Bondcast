package n7.bondcast

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val White = Color(0xFFFFFFFF)
private val Accent = Color(0xFFFF3C3C)
private val AccentPressed = Color(0xFFD42F2F)
private val Green = Color(0xFF2FBF5B)
private val Red = Color(0xFFFF3C3C)
private val Yellow = Color(0xFFF0B232)
private val Background = Color(0xFF0E0E0E)
private val CardSurface = Color(0xFF0E0E0E)
private val Elevated = Color(0xFF232323)
private val InputBackground = Color(0xFF161616)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextSecondary = Color(0xFFAAAAAA)
private val TextMuted = Color(0xFF666666)
private val Link = Color(0xFFFF6B6B)
private val Divider = Color(0xFF2A2A2A)
private val GroupDivider = Color(0xFF3A3A3A)
private val Sticker = Color(0xFFFFFFFF)
private val Stripe = Color(0xFF161616)

private val ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = White,
    primaryContainer = AccentPressed,
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
    val blurple = Accent
    val green = Green
    val danger = Red
    val yellow = Yellow
    val accent = Accent
    val plate = Elevated
    val panel = Background
    val groupDivider = GroupDivider
    val sticker = Sticker
    val stripe = Stripe
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
