package be.valuya.breadbin.engine.vic

/**
 * The sixteen colours, as measured off real hardware by Philip Timmermann ("Pepto"). Picking a
 * palette is picking a look, and this is the one most people recognise as a C64.
 */
object Palette {
    val ARGB = intArrayOf(
        0xFF000000.toInt(), // black
        0xFFFFFFFF.toInt(), // white
        0xFF813338.toInt(), // red
        0xFF75CEC8.toInt(), // cyan
        0xFF8E3C97.toInt(), // purple
        0xFF56AC4D.toInt(), // green
        0xFF2E2C9B.toInt(), // blue
        0xFFEDF171.toInt(), // yellow
        0xFF8E5029.toInt(), // orange
        0xFF553800.toInt(), // brown
        0xFFC46C71.toInt(), // light red
        0xFF4A4A4A.toInt(), // dark grey
        0xFF7B7B7B.toInt(), // grey
        0xFFA9FF9F.toInt(), // light green
        0xFF706DEB.toInt(), // light blue
        0xFFB2B2B2.toInt(), // light grey
    )
}
