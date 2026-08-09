package be.valuya.breadbin.ui.emulator

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.valuya.breadbin.engine.cia.C64Key

/** A key as it appears on the machine: what is written on it, and how wide it is. */
private class Legend(
    val label: String,
    val key: C64Key?,
    val width: Float = 1f,
    /** Modifiers stay down until the next ordinary key has been and gone. */
    val modifier: Boolean = false,
    /** RESTORE is not in the matrix; it goes straight to the processor's NMI. */
    val restore: Boolean = false,
)

private val ROWS: List<List<Legend>> = listOf(
    listOf(
        Legend("←", C64Key.ARROW_LEFT), Legend("1", C64Key.KEY_1), Legend("2", C64Key.KEY_2),
        Legend("3", C64Key.KEY_3), Legend("4", C64Key.KEY_4), Legend("5", C64Key.KEY_5),
        Legend("6", C64Key.KEY_6), Legend("7", C64Key.KEY_7), Legend("8", C64Key.KEY_8),
        Legend("9", C64Key.KEY_9), Legend("0", C64Key.KEY_0), Legend("+", C64Key.PLUS),
        Legend("-", C64Key.MINUS), Legend("£", C64Key.POUND), Legend("DEL", C64Key.INSERT_DELETE, 1.5f),
    ),
    listOf(
        Legend("CTRL", C64Key.CONTROL, 1.5f), Legend("Q", C64Key.Q), Legend("W", C64Key.W),
        Legend("E", C64Key.E), Legend("R", C64Key.R), Legend("T", C64Key.T), Legend("Y", C64Key.Y),
        Legend("U", C64Key.U), Legend("I", C64Key.I), Legend("O", C64Key.O), Legend("P", C64Key.P),
        Legend("@", C64Key.AT), Legend("*", C64Key.ASTERISK), Legend("↑", C64Key.ARROW_UP),
        Legend("RST", null, 1.5f, restore = true),
    ),
    listOf(
        Legend("R/S", C64Key.RUN_STOP, 1.5f), Legend("A", C64Key.A), Legend("S", C64Key.S),
        Legend("D", C64Key.D), Legend("F", C64Key.F), Legend("G", C64Key.G), Legend("H", C64Key.H),
        Legend("J", C64Key.J), Legend("K", C64Key.K), Legend("L", C64Key.L),
        Legend(":", C64Key.COLON), Legend(";", C64Key.SEMICOLON), Legend("=", C64Key.EQUALS),
        Legend("RETURN", C64Key.RETURN, 2.5f),
    ),
    listOf(
        Legend("C=", C64Key.COMMODORE, 1.5f, modifier = true),
        Legend("SHIFT", C64Key.LEFT_SHIFT, 1.5f, modifier = true),
        Legend("Z", C64Key.Z), Legend("X", C64Key.X), Legend("C", C64Key.C), Legend("V", C64Key.V),
        Legend("B", C64Key.B), Legend("N", C64Key.N), Legend("M", C64Key.M),
        Legend(",", C64Key.COMMA), Legend(".", C64Key.PERIOD), Legend("/", C64Key.SLASH),
        Legend("HOME", C64Key.HOME, 1.5f),
    ),
    listOf(
        Legend("F1", C64Key.F1), Legend("F3", C64Key.F3), Legend("F5", C64Key.F5),
        Legend("F7", C64Key.F7),
        Legend("SPACE", C64Key.SPACE, 6f),
        Legend("↑", C64Key.CURSOR_DOWN, 1.2f), // with SHIFT, which the app applies for you
        Legend("↓", C64Key.CURSOR_DOWN, 1.2f),
        Legend("←", C64Key.CURSOR_RIGHT, 1.2f),
        Legend("→", C64Key.CURSOR_RIGHT, 1.2f),
    ),
)

/**
 * The C64 keyboard, all of it.
 *
 * A phone cannot show sixty-six keys at a comfortable size, so they are shown at an uncomfortable
 * one rather than hidden: a keyboard missing the key a game wants is worse than a small keyboard,
 * and the games that need typing need exactly the odd keys an abbreviated layout leaves out.
 *
 * SHIFT and C= latch: tapping one holds it down until the next ordinary key has been pressed and
 * released, which is the only way to manage a two-key combination with one thumb.
 */
@Composable
fun VirtualKeyboard(
    onPress: (C64Key) -> Unit,
    onRelease: (C64Key) -> Unit,
    onRestore: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var latched by remember { mutableStateOf(setOf<C64Key>()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((index, row) in ROWS.withIndex()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (legend in row) {
                    Key(
                        legend = legend,
                        // The cursor keys on the machine are two keys and a shift between four
                        // directions; up and left are the shifted halves.
                        shifted = index == 4 && legend.label in setOf("↑", "←"),
                        held = legend.key in latched,
                        onDown = { shift ->
                            if (legend.restore) {
                                onRestore(true)
                                return@Key
                            }
                            val key = legend.key ?: return@Key
                            if (legend.modifier) {
                                latched = if (key in latched) latched - key else latched + key
                                if (key in latched) onPress(key) else onRelease(key)
                                return@Key
                            }
                            if (shift) onPress(C64Key.RIGHT_SHIFT)
                            onPress(key)
                        },
                        onUp = { shift ->
                            if (legend.restore) {
                                onRestore(false)
                                return@Key
                            }
                            val key = legend.key ?: return@Key
                            if (legend.modifier) return@Key
                            onRelease(key)
                            if (shift) onRelease(C64Key.RIGHT_SHIFT)
                            // An ordinary key releases whatever was latched with it.
                            for (held in latched) onRelease(held)
                            latched = emptySet()
                        },
                        modifier = Modifier.weight(legend.width),
                    )
                }
            }
        }
    }
}

@Composable
private fun Key(
    legend: Legend,
    shifted: Boolean,
    held: Boolean,
    onDown: (Boolean) -> Unit,
    onUp: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val background = when {
        held -> MaterialTheme.colorScheme.primary
        pressed -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .pointerInput(legend.label, shifted) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onDown(shifted)
                        // Waiting for the release rather than firing on tap keeps a held key held,
                        // which games that read the matrix directly rely on.
                        tryAwaitRelease()
                        pressed = false
                        onUp(shifted)
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = legend.label,
            fontSize = if (legend.label.length > 3) 9.sp else 13.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            color = if (held) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
