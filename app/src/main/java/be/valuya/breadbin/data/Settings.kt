package be.valuya.breadbin.data

import be.valuya.breadbin.engine.vic.VideoModel

/** How the picture is shaped on screen. */
enum class Aspect {
    /** Four by three, which is the shape of the television the machine was plugged into. */
    TELEVISION,

    /** The pixels as the chip made them, square and slightly wider than a television showed. */
    SQUARE,
}

data class Settings(
    val model: VideoModel = VideoModel.PAL,
    val aspect: Aspect = Aspect.TELEVISION,
    val smoothing: Boolean = false,
    val showBorder: Boolean = true,
    val sound: Boolean = true,
    val haptics: Boolean = true,
    /** Multiplier on the on-screen joystick, from a half to double. */
    val stickSize: Float = 1f,
    /** How solid the touch controls are over the picture. */
    val opacity: Float = 0.5f,
    val autostart: Boolean = true,
    val joystickPort: Int = 2,
)
