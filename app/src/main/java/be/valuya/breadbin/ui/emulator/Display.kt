package be.valuya.breadbin.ui.emulator

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import be.valuya.breadbin.data.Aspect
import be.valuya.breadbin.data.Settings
import be.valuya.breadbin.emu.EmulatorSession
import kotlin.math.roundToInt

/**
 * The picture.
 *
 * Drawing the emulator's bitmap straight onto the native canvas is the shortest path there is: one
 * blit per frame, scaled by the hardware, with no intermediate copies. Reading [EmulatorSession
 * .frameCount] inside the draw is what ties the two together — the emulation thread bumps it, and
 * Compose redraws.
 */
@Composable
fun Display(session: EmulatorSession, settings: Settings, modifier: Modifier = Modifier) {
    val model = session.machine.model
    val paint = remember { Paint() }
    paint.isFilterBitmap = settings.smoothing

    val source = remember(settings.showBorder, model) {
        if (settings.showBorder) {
            Rect(0, 0, model.width, model.height)
        } else {
            // The display window alone: 320 by 200, at the offset the border crops away.
            val left = DISPLAY_LEFT_X - model.firstVisibleX + LINE_LENGTH
            val top = DISPLAY_TOP_LINE - model.firstVisibleLine
            Rect(left % LINE_LENGTH, top, left % LINE_LENGTH + 320, top + 200)
        }
    }

    Canvas(modifier) {
        val frames = session.frameCount // read so that a new frame provokes a redraw
        check(frames >= 0)

        val pictureAspect = when (settings.aspect) {
            Aspect.TELEVISION -> 4f / 3f
            Aspect.SQUARE -> source.width().toFloat() / source.height()
        }
        var width = size.width
        var height = width / pictureAspect
        if (height > size.height) {
            height = size.height
            width = height * pictureAspect
        }
        val left = ((size.width - width) / 2f).roundToInt()
        val top = ((size.height - height) / 2f).roundToInt()
        val destination = Rect(left, top, left + width.roundToInt(), top + height.roundToInt())

        drawIntoCanvas { canvas ->
            session.withPicture { bitmap ->
                canvas.nativeCanvas.drawBitmap(bitmap, source, destination, paint)
            }
        }
    }
}

/** The sprite X coordinate of the left edge of the display window. */
private const val DISPLAY_LEFT_X = 24

/** The first raster line of the display window. */
private const val DISPLAY_TOP_LINE = 51

private const val LINE_LENGTH = 512
