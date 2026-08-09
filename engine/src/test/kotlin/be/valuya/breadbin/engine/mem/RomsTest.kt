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
}
