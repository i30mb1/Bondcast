package n7.bondcast.ui.components

import android.os.PowerManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.thermal.ThermalState

/** Цвета лепестков пламени: внешний (градиент снизу вверх), средний и внутренний. */
private data class FlamePalette(
    val outerBottom: Color,
    val outerTop: Color,
    val middle: Color,
    val inner: Color,
)

/** Прохладно: телефону хорошо, пламя декоративно-синее. */
private val CoolPalette = FlamePalette(
    outerBottom = Color(0xFF0D4CFF),
    outerTop = Color(0xFF02A5FC),
    middle = Color(0xFF02A5FC),
    inner = Color(0xFF8AE7FF),
)

/** Тепло: оригинальные цвета fire.xml — обычный огонёк. */
private val WarmPalette = FlamePalette(
    outerBottom = Color(0xFFFF4C0D),
    outerTop = Color(0xFFFC9502),
    middle = Color(0xFFFC9502),
    inner = Color(0xFFFCE202),
)

/** Жарко: багровое пламя — пора открывать термопанель. */
private val HotPalette = FlamePalette(
    outerBottom = Color(0xFF7F0000),
    outerTop = Color(0xFFE53935),
    middle = Color(0xFFE53935),
    inner = Color(0xFFFF6E40),
)

// контуры из app/src/main/res/drawable/fire.xml (viewport 255×255)
private const val OUTER_PATH = "M220.9,164.81C218.8,214.87 177.57,254.81 127,254.81C75.08,254.81 33,211.31 33,160.81C33,154.06 32.88,140.57 43,117.81C49.06,104.19 52.86,95.63 55,87.81C56.18,83.51 58.47,76.68 65,87.81C68.85,94.37 69,103.81 69,103.81C69,103.81 83.33,92.82 93,71.81C107.18,41.02 95.87,22.61 92,9.81C90.66,5.38 89.82,-2.57 99,0.81C108.35,4.26 133.08,21.57 146,39.81C164.45,65.85 171,90.81 171,90.81C171,90.81 176.91,83.48 179,75.81C181.37,67.15 181.4,58.57 189,67.81C196.23,76.6 206.96,93.11 213,108.81C223.97,137.32 220.9,164.81 220.9,164.81Z"
private const val MIDDLE_PATH = "M127,254.81C91.1,254.81 62,225.71 62,189.81C62,168.15 70.73,155 88.9,137.17C100.53,125.75 111.42,111.72 116.04,102.17C116.95,100.29 119.03,90.5 127.02,101.97C131.21,107.98 137.79,118.68 142,127.81C149.27,143.55 151,158.81 151,158.81C151,158.81 158.12,154.62 163,143.81C164.57,140.33 167.75,127.15 176.64,140.33C183.17,150 192.13,167.39 192,189.81C192,225.71 162.9,254.81 127,254.81Z"
private const val INNER_PATH = "M128,183.81C137.25,183.81 137.25,200.94 149,223.81C156.82,239.04 145.12,254.81 128,254.81C110.88,254.81 102,240.93 102,223.81C102,206.69 118.75,183.81 128,183.81Z"

private fun flameVector(palette: FlamePalette): ImageVector =
    ImageVector.Builder(
        name = "fire",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 255f,
        viewportHeight = 255f,
    ).apply {
        addPath(
            pathData = addPathNodes(OUTER_PATH),
            pathFillType = PathFillType.EvenOdd,
            fill = Brush.linearGradient(
                colors = listOf(palette.outerBottom, palette.outerTop),
                start = Offset(127.14f, 255f),
                end = Offset(127.14f, 0.19f),
            ),
        )
        addPath(
            pathData = addPathNodes(MIDDLE_PATH),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(palette.middle),
        )
        addPath(
            pathData = addPathNodes(INNER_PATH),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(palette.inner),
        )
    }.build()

/** Кнопка термопанели: пламя перекрашивается по нагреву — синее/оранжевое/багровое. */
@Composable
public fun FlameIcon(
    state: ThermalState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // статус системы главнее: headroom легко держится ~0.8 при полностью здоровом телефоне,
    // поэтому красним только у реального троттлинга (SEVERE или headroom у единицы)
    val palette = when {
        state.status >= PowerManager.THERMAL_STATUS_SEVERE || state.heat >= 0.95f -> HotPalette
        state.status >= PowerManager.THERMAL_STATUS_MODERATE || state.heat >= 0.65f -> WarmPalette
        else -> CoolPalette
    }
    val vector = remember(palette) { flameVector(palette) }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(DiscordColors.background.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = vector,
            contentDescription = "Температура",
            modifier = Modifier.size(22.dp),
        )
    }
}
