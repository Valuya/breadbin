package be.valuya.breadbin.ui.emulator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** What the stick and the button are doing at this instant. */
data class JoystickState(
    val up: Boolean = false,
    val down: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
    val fire: Boolean = false,
)

/**
 * The on-screen joystick and fire button.
 *
 * Both are handled by one pointer loop rather than two gesture detectors, because a game needs the
 * stick and the button at the same time and independent detectors fight over pointers. Each finger
 * is claimed by whichever control it landed on and keeps that control until it lifts, so sliding
 * off the button does not let go of it and a second finger never steals the stick.
 */
@Composable
fun TouchControls(
    scale: Float,
    opacity: Float,
    onState: (JoystickState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val stickRadius = with(density) { (72.dp * scale).toPx() }
    val knobRadius = with(density) { (30.dp * scale).toPx() }
    val fireRadius = with(density) { (46.dp * scale).toPx() }
    val margin = with(density) { 28.dp.toPx() }

    var state by remember { mutableStateOf(JoystickState()) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    val listener by rememberUpdatedState(onState)

    Box(modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(stickRadius, fireRadius) {
                    // Never further down than the box is tall: with the keyboard open the picture
                    // is a short strip, and a control placed a fixed distance from the bottom of a
                    // short strip is a control half off the top of it.
                    val stickCentre = Offset(
                        margin + stickRadius,
                        (size.height - margin - stickRadius).coerceAtLeast(stickRadius),
                    )
                    val fireCentre = Offset(
                        size.width - margin - fireRadius,
                        (size.height - margin - fireRadius).coerceAtLeast(fireRadius),
                    )
                    // Generous catch areas: a thumb aiming for the stick rarely lands on it.
                    val stickCatch = stickRadius * 1.6f
                    val fireCatch = fireRadius * 1.5f

                    val claimed = mutableMapOf<PointerId, Boolean>() // true for the stick

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                if (!change.pressed) {
                                    claimed.remove(change.id)
                                    continue
                                }
                                if (change.id !in claimed) {
                                    val toStick = (change.position - stickCentre).getDistance()
                                    val toFire = (change.position - fireCentre).getDistance()
                                    claimed[change.id] = when {
                                        toStick <= stickCatch && toStick <= toFire -> true
                                        toFire <= fireCatch -> false
                                        // A finger anywhere else in the lower half is treated as a
                                        // second fire button, which is how most people hold a phone.
                                        change.position.y > size.height / 2 -> false
                                        else -> continue
                                    }
                                    change.consume()
                                }
                                if (claimed[change.id] == true) change.consume()
                            }

                            val stickPointer = event.changes.firstOrNull {
                                it.pressed && claimed[it.id] == true
                            }
                            val firing = event.changes.any { it.pressed && claimed[it.id] == false }

                            val direction = if (stickPointer == null) {
                                knob = Offset.Zero
                                JoystickState(fire = firing)
                            } else {
                                val offset = stickPointer.position - stickCentre
                                val distance = offset.getDistance()
                                knob = if (distance > stickRadius) {
                                    offset * (stickRadius / distance)
                                } else {
                                    offset
                                }
                                directionOf(offset, stickRadius).copy(fire = firing)
                            }

                            if (direction != state) {
                                state = direction
                                listener(direction)
                            }
                        }
                    }
                }
        ) {
            val stickCentre = Offset(
                margin + stickRadius,
                (size.height - margin - stickRadius).coerceAtLeast(stickRadius),
            )
            val fireCentre = Offset(
                size.width - margin - fireRadius,
                (size.height - margin - fireRadius).coerceAtLeast(fireRadius),
            )
            // Everything is drawn twice: a dark halo, then a bright line on top of it. A single
            // white line at half opacity is what these were, and on a game that is mostly black —
            // which is most of them — a grey line on black is very nearly nothing at all. Two
            // passes cost nothing and read against a night sky and a white loading screen alike.
            //
            // The slider still reaches nothing at all at zero, because a control you can hide is
            // the point of having the slider. What changes is the middle of its range: alpha rises
            // as the square root, so halfway along is two thirds opaque rather than half.
            val strength = sqrt(opacity.coerceIn(0f, 1f))
            val ink = Color.White.copy(alpha = strength)
            val halo = Color.Black.copy(alpha = strength * 0.75f)
            // A faint light wash rather than a dark one: the point is to lift the inside of the
            // ring off a black background, and black on black lifts nothing.
            val fill = Color.White.copy(alpha = strength * 0.14f)

            fun ring(radius: Float, centre: Offset) {
                drawCircle(halo, radius, centre, style = Stroke(width = HALO_WIDTH))
                drawCircle(ink, radius, centre, style = Stroke(width = LINE_WIDTH))
            }

            drawCircle(fill, stickRadius, stickCentre)
            ring(stickRadius, stickCentre)
            drawCircle(halo, knobRadius + HALO_WIDTH / 2f, stickCentre + knob)
            drawCircle(ink, knobRadius, stickCentre + knob)

            drawCircle(if (state.fire) ink else fill, fireRadius, fireCentre)
            ring(fireRadius, fireCentre)
        }
    }
}

/**
 * Turns the stick's offset into one of the eight directions a real joystick could manage, with a
 * dead zone in the middle so that resting a thumb on it does not walk the player off a ledge.
 */
private fun directionOf(offset: Offset, radius: Float): JoystickState {
    val distance = hypot(offset.x, offset.y)
    if (distance < radius * 0.35f) return JoystickState()
    // A direction counts when its component is at least a third of the larger one, which gives the
    // diagonals a comfortable share of the circle without making them easy to hit by accident.
    val threshold = maxOf(abs(offset.x), abs(offset.y)) * 0.45f
    return JoystickState(
        up = offset.y < -threshold,
        down = offset.y > threshold,
        left = offset.x < -threshold,
        right = offset.x > threshold,
    )
}

/** The bright line, and the dark one under it that makes the bright one visible on anything. */
private const val LINE_WIDTH = 4f
private const val HALO_WIDTH = 10f
