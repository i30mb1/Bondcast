package n7.bondcast.ui.street

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

public val streetTitle: TextStyle = TextStyle(
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    fontSize = 17.sp,
    letterSpacing = 1.sp,
)

public val streetLabel: TextStyle = TextStyle(
    fontWeight = FontWeight.ExtraBold,
    fontStyle = FontStyle.Italic,
    fontSize = 11.sp,
    letterSpacing = 0.8.sp,
)

public val streetButton: TextStyle = TextStyle(
    fontWeight = FontWeight.Black,
    fontStyle = FontStyle.Italic,
    fontSize = 16.sp,
    letterSpacing = 1.2.sp,
)

public val streetValue: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 22.sp,
)

public val streetUnit: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
)

public fun String.upper(): String = uppercase()
