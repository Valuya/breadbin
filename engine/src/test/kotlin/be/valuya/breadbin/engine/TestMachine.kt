package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.mem.Roms

/**
 * A stand-in for the Commodore ROMs, which cannot be shipped with the tests any more than they can
 * be shipped with the app.
 *
 * The KERNAL here is real enough for the parts under test: a jump table in the right place, so the
 * virtual drive can find and patch the serial routines, and a reset vector pointing at whatever
 * the test wants to run.
 */
object TestRoms {

    const val KERNAL_BASE = 0xE000

    /** Where [kernal] puts the serial routines it advertises in the jump table. */
    val serialRoutines = mapOf(
        0xFF93 to 0xE100, // SECOND
        0xFF96 to 0xE110, // TKSA
        0xFFA5 to 0xE120, // ACPTR
        0xFFA8 to 0xE130, // CIOUT
        0xFFAB to 0xE140, // UNTLK
        0xFFAE to 0xE150, // UNLSN
        0xFFB1 to 0xE160, // LISTEN
        0xFFB4 to 0xE170, // TALK
    )

    /**
     * Builds a ROM set. [program] is written at [at] and the reset vector points to it, so a test
     * can hand over machine code and let the real processor run it.
     */
    fun of(
        program: IntArray = intArrayOf(0x4C, 0x00, 0xE0),
        at: Int = 0xE000,
        irqAt: Int = at,
    ): Roms {
        val kernal = IntArray(0x2000)
        for (i in program.indices) kernal[at - KERNAL_BASE + i] = program[i]

        for ((vector, target) in serialRoutines) {
            val offset = vector - KERNAL_BASE
            kernal[offset] = 0x4C // JMP
            kernal[offset + 1] = target and 0xFF
            kernal[offset + 2] = (target shr 8) and 0xFF
            // Each routine is a bare RTS until the drive patches a trap over it.
            kernal[target - KERNAL_BASE] = 0x60
        }

        kernal[0xFFFC - KERNAL_BASE] = at and 0xFF
        kernal[0xFFFD - KERNAL_BASE] = (at shr 8) and 0xFF
        kernal[0xFFFE - KERNAL_BASE] = irqAt and 0xFF
        kernal[0xFFFF - KERNAL_BASE] = (irqAt shr 8) and 0xFF

        val character = IntArray(0x1000)
        // Character 1 is a solid block, which makes it obvious in a rendered frame.
        for (row in 0 until 8) character[8 + row] = 0xFF

        return Roms(IntArray(0x2000), kernal, character)
    }
}
