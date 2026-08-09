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
