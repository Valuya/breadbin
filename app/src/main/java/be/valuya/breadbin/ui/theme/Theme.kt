package be.valuya.breadbin.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Ordinary Android, on purpose.
 *
 * There was a hand-written palette here, taken from the machine's own colours, and it looked like
 * the machine — which is charming on the emulator screen and a liability everywhere else. A colour
 * scheme is not a list of colours you like: Material defines around forty roles that its components
 * pick from, and setting eight of them leaves the other thirty-odd on a default palette that has
 * nothing to do with the eight. Cards, dialogs and menus draw themselves from exactly those
 * left-over roles, which is how a screen ends up with text nobody can read on a background nobody
 * chose.
 *
 * So the app now uses the system's own scheme, which on Android 12 and later is generated from the
 * user's wallpaper and is guaranteed to have the contrast the platform promises. The machine's blue
 * still belongs on the emulator screen, where it is a border rather than a background.
 */
@Composable
fun BreadbinTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
