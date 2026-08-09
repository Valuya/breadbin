package be.valuya.breadbin.engine.vic

/**
 * PAL and NTSC machines differ in more than refresh rate: the line is two cycles shorter on NTSC
 * and there are fifty fewer of them, so a game written to a PAL raster runs differently on an NTSC
 * machine and the other way round. Most European releases want PAL; most American ones want NTSC.
 */
enum class VideoModel(
    val cyclesPerLine: Int,
    val linesPerFrame: Int,
    val firstVisibleLine: Int,
    val height: Int,
    /** Clock rate in Hz, which the SID and the CIAs are counted at too. */
    val clockHz: Int,
) {
    PAL(63, 312, 15, 272, 985_248),
    NTSC(65, 263, 27, 235, 1_022_727),
    ;

    /** 320 pixels of display window plus a slice of border on each side. */
    val width = 384

    /**
     * The sprite X coordinate of the leftmost output pixel: forty pixels to the left of the display
     * window, which the beam reaches before it wraps to zero.
     */
    val firstVisibleX = 496

    val cyclesPerFrame get() = cyclesPerLine * linesPerFrame

    val framesPerSecond get() = clockHz.toDouble() / cyclesPerFrame
}
