package be.valuya.breadbin.engine.mem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling one ROM from another, which matters because "it does not work" and "you are running a
 * different KERNAL than you think" look identical from the outside.
 */
class RomsTest {

    private fun kernal(revisionByte: Int) = ByteArray(Roms.KERNAL_SIZE).also {
        it[0xFF80 - 0xE000] = revisionByte.toByte()
    }

    /** A KERNAL is mostly a jump table, so a plausible one has to have it. */
    private fun realisticKernal() = ByteArray(Roms.KERNAL_SIZE).also {
        for (entry in 0xFF81..0xFFEA step 3) it[entry - 0xE000] = 0x4C
        it[0x1FFC] = 0xE2.toByte()
        it[0x1FFD] = 0xFC.toByte()
        it[0xFF80 - 0xE000] = 0x03
    }

    private fun realisticBasic() = ByteArray(Roms.BASIC_SIZE).also {
        it[0] = 0x94.toByte(); it[1] = 0xE3.toByte()   // cold start $E394
        it[2] = 0x7B; it[3] = 0xE3.toByte()            // warm start $E37B
    }

    private fun realisticCharacterSet() = ByteArray(Roms.CHARACTER_SIZE) { (it * 37).toByte() }

    @Test
    fun `each kind is recognised from its contents`() {
        assertEquals(RomKind.KERNAL, Roms.identify(realisticKernal()))
        assertEquals(RomKind.BASIC, Roms.identify(realisticBasic()))
        assertEquals(RomKind.CHARACTER, Roms.identify(realisticCharacterSet()))
    }

    /**
     * The case that made all this necessary: a folder of assorted Commodore ROMs, where the sizes
     * collide and only the contents can tell them apart. Filing any of these as a BASIC or a
     * KERNAL overwrites a correct one and reports success.
     */
    @Test
    fun `things that are only the right size are refused`() {
        // Half of a 1541 DOS, which is eight kilobytes and is neither a BASIC nor a KERNAL.
        val dosHalf = ByteArray(Roms.KERNAL_SIZE) { (it * 3 + 1).toByte() }
        assertEquals("half a drive ROM was filed as something", null, Roms.identify(dosHalf))

        // A C128 MMU or anything else of the same size with no vectors in it.
        assertEquals(null, Roms.identify(ByteArray(Roms.BASIC_SIZE)))

        // Vectors that point at RAM rather than into the KERNAL are not a BASIC.
        val wrongVectors = ByteArray(Roms.BASIC_SIZE).also {
            it[0] = 0x00; it[1] = 0x20; it[2] = 0x00; it[3] = 0x30
        }
        assertEquals(null, Roms.identify(wrongVectors))


        // And a four-kilobyte file with nothing in it is not a font.
        assertEquals(null, Roms.identify(ByteArray(Roms.CHARACTER_SIZE)))
    }

    @Test
    fun `a KERNAL is not mistaken for a BASIC`() {
        // Both are eight kilobytes and the KERNAL is checked first, so this is the ordering that
        // matters rather than a coincidence worth relying on.
        assertEquals(RomKind.KERNAL, Roms.identify(realisticKernal()))
    }

    @Test
    fun `a KERNAL says which revision it is`() {
        assertTrue(Roms.describe(RomKind.KERNAL, kernal(0x03)).startsWith("Commodore revision 3"))
        assertTrue(Roms.describe(RomKind.KERNAL, kernal(0xAA)).startsWith("Commodore revision 1"))
        assertTrue(Roms.describe(RomKind.KERNAL, kernal(0x00)).startsWith("Commodore revision 2"))
    }

    @Test
    fun `a KERNAL that is nobody's known revision says so rather than guessing`() {
        val described = Roms.describe(RomKind.KERNAL, kernal(0xF0))
        assertTrue("guessed at an unknown revision: $described", described.contains("\$F0"))
        assertFalse(described.contains("Commodore"))
    }

    @Test
    fun `only Commodore's own revisions count as Commodore's`() {
        assertTrue(Roms.isCommodoreKernal(kernal(0x03)))
        // Open ROMs has $F0 there, which is not a revision number anybody shipped.
        assertFalse(Roms.isCommodoreKernal(kernal(0xF0)))
        assertFalse("a file of the wrong size cannot be a KERNAL", Roms.isCommodoreKernal(ByteArray(100)))
    }

    /**
     * The drive's ROM has to add up or the drive never starts, and the reconstruction everybody
     * rebuilds from leaves the two checksum bytes at zero — so this is the common case, not a
     * corner one.
     */
    @Test
    fun `a drive ROM that does not add up is spotted`() {
        val broken = ByteArray(Roms.DRIVE_SIZE)
        assertFalse(Roms.driveRomPassesSelfTest(broken))
        assertTrue(Roms.describe(RomKind.DRIVE, broken).contains("self-test"))

        // Repair it the way the drive wants: each half summed backwards must equal its own page.
        val fixed = repair(broken)
        assertTrue("a repaired ROM still does not add up", Roms.driveRomPassesSelfTest(fixed))
        assertFalse(Roms.describe(RomKind.DRIVE, fixed).contains("self-test"))
    }

    /**
     * A drive ROM that does not add up is still a drive ROM. It is imported, and then not used,
     * and the difference matters: refusing the import made it disappear silently, which is the
     * worst of both.
     */
    @Test
    fun `a drive ROM that fails its test is still identified as one`() {
        assertEquals(RomKind.DRIVE, Roms.identify(ByteArray(Roms.DRIVE_SIZE)))
        assertFalse(Roms.driveRomPassesSelfTest(ByteArray(Roms.DRIVE_SIZE)))
    }

    @Test
    fun `a file of the wrong size is not a drive ROM`() {
        assertFalse(Roms.driveRomPassesSelfTest(ByteArray(1024)))
    }

    /** Brute-forces the two bytes the drive's own test is really checking. */
    private fun repair(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        for (v in 0..255) {
            out[0xFEE6 - 0xC000] = v.toByte()
            if (upperHalfOf(out) == 0xE0) break
        }
        for (v in 0..255) {
            out[0] = v.toByte()
            if (Roms.driveRomPassesSelfTest(out)) break
        }
        return out
    }

    private fun upperHalfOf(bytes: ByteArray): Int {
        var total = 0
        var carry = 0
        var page = 0x00
        repeat(32) {
            page = (page - 1) and 0xFF
            val base = (page shl 8) - 0xC000
            for (i in 0 until 256) {
                val sum = total + (bytes[base + i].toInt() and 0xFF) + carry
                total = sum and 0xFF
                carry = if (sum > 0xFF) 1 else 0
            }
        }
        return (total + carry) and 0xFF
    }

    @Test
    fun `everything else gets a checksum, and two different files get different ones`() {
        val one = Roms.describe(RomKind.BASIC, ByteArray(Roms.BASIC_SIZE))
        val two = Roms.describe(RomKind.BASIC, ByteArray(Roms.BASIC_SIZE).also { it[0] = 1 })
        assertTrue(one.startsWith("CRC "))
        assertTrue("two different ROMs described identically", one != two)
    }

    @Test
    fun `the same bytes always describe the same way`() {
        assertEquals(
            Roms.describe(RomKind.CHARACTER, ByteArray(Roms.CHARACTER_SIZE) { it.toByte() }),
            Roms.describe(RomKind.CHARACTER, ByteArray(Roms.CHARACTER_SIZE) { it.toByte() }),
        )
    }

    /**
     * Half of one set and half of the other is not a machine.
     *
     * Not hypothetical caution. Rejecting a wrongly-filed BASIC — which was the right thing to do —
     * left a user with Commodore's KERNAL, no BASIC, and so the free BASIC underneath it, and that
     * pairing boots to nothing at all. The switches say what somebody wants; this says what they
     * can actually be given.
     */
    @Test
    fun `BASIC and the KERNAL are only ever taken together`() {
        fun only(vararg on: RomKind): (RomKind) -> Boolean = { it in on }

        assertTrue(romInUse(RomKind.BASIC, only(RomKind.BASIC, RomKind.KERNAL)))
        assertTrue(romInUse(RomKind.KERNAL, only(RomKind.BASIC, RomKind.KERNAL)))

        assertFalse("a KERNAL without a BASIC was used", romInUse(RomKind.KERNAL, only(RomKind.KERNAL)))
        assertFalse("a BASIC without a KERNAL was used", romInUse(RomKind.BASIC, only(RomKind.BASIC)))
        // And the half that is switched on is held back too, not merely its missing partner.
        assertFalse(romInUse(RomKind.BASIC, only(RomKind.KERNAL)))
    }

    @Test
    fun `the character set and the drive ROM stand on their own`() {
        fun only(vararg on: RomKind): (RomKind) -> Boolean = { it in on }
        // Glyphs call into nothing and the drive is another computer, so neither waits for BASIC.
        assertTrue(romInUse(RomKind.CHARACTER, only(RomKind.CHARACTER)))
        assertTrue(romInUse(RomKind.DRIVE, only(RomKind.DRIVE)))
        assertFalse(romInUse(RomKind.CHARACTER, only(RomKind.BASIC, RomKind.KERNAL)))
    }
}
