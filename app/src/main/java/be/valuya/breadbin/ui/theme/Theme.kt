package be.valuya.breadbin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** The machine's own palette, which is the only palette this app has any business using. */
private val Blue = Color(0xFF2E2C9B)
private val LightBlue = Color(0xFF706DEB)
private val Grey = Color(0xFF7B7B7B)
private val LightGrey = Color(0xFFB2B2B2)
private val Yellow = Color(0xFFEDF171)
private val Red = Color(0xFF813338)

private val dark = darkColorScheme(
    primary = LightBlue,
    onPrimary = Color.Black,
    primaryContainer = Blue,
    onPrimaryContainer = LightGrey,
    secondary = Yellow,
    onSecondary = Color.Black,
    background = Color(0xFF16153F),
    onBackground = LightGrey,
    surface = Color(0xFF1E1D52),
    onSurface = LightGrey,
    surfaceVariant = Color(0xFF2A2969),
    onSurfaceVariant = LightGrey,
    error = Red,
)

private val light = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = LightBlue,
    onPrimaryContainer = Color.Black,
    secondary = Blue,
    background = Color(0xFFF2F1FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE3E2F2),
    onSurfaceVariant = Color(0xFF3A3A4A),
    error = Red,
)

private val typography = Typography().let { base ->
    // The machine's own font is not something to inflict on body text, but the monospaced feel of
    // the titles is a nod worth keeping.
    base.copy(
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Monospace),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Monospace),
        labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
    )
}

@Composable
fun BreadbinTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) dark else light,
        typography = typography,
        content = content,
    )
}
