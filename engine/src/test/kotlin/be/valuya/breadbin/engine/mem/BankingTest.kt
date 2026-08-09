package be.valuya.breadbin.engine.mem

import be.valuya.breadbin.engine.Machine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PLA's truth table, checked at the configurations programs actually use.
 *
 * Getting this wrong is the sort of bug that leaves the machine looking fine until a game switches
 * the KERNAL out to use the RAM underneath it, so the specific values matter more than the general
 * shape.
 */
class BankingTest {

    private val machine = Machine(markedRoms())

    /** ROMs whose every byte says which ROM it is, so a read identifies what is banked in. */
    private fun markedRoms(): Roms {
        val basic = IntArray(0x2000) { BASIC }
        val kernal = IntArray(0x2000) { KERNAL }
        val character = IntArray(0x1000) { CHARACTER }
        kernal[0xFFFC - 0xE000] = 0x00
        kernal[0xFFFD - 0xE000] = 0xE0
        return Roms(basic, kernal, character)
    }

    private fun port(value: Int) {
        machine.memory.write(0x0000, 0x2F)
        machine.memory.write(0x0001, value)
    }

    @Test
    fun `the default configuration shows BASIC, IO and the KERNAL`() {
        port(0x37)
        assertEquals(BASIC, machine.memory.read(0xA000))
        assertEquals(KERNAL, machine.memory.read(0xE000))
        // $d011 reads back the control register, not RAM, when I/O is banked in.
        assertEquals(0x1B, machine.memory.read(0xD011) and 0x7F)
    }

    @Test
    fun `banking out BASIC leaves RAM in its place`() {
        machine.memory.poke(0xA000, 0x42)
        port(0x36)
        assertEquals(0x42, machine.memory.read(0xA000))
        assertEquals(KERNAL, machine.memory.read(0xE000))
    }

    @Test
    fun `the character ROM appears where IO was when CHAREN is clear`() {
        port(0x33)
        assertEquals(CHARACTER, machine.memory.read(0xD000))
    }

    @Test
    fun `$34 gives sixty-four kilobytes of RAM`() {
        machine.memory.poke(0xD000, 0x11)
        machine.memory.poke(0xE000, 0x22)
        machine.memory.poke(0xA000, 0x33)
        port(0x34)
        assertEquals(0x11, machine.memory.read(0xD000))
        assertEquals(0x22, machine.memory.read(0xE000))
        assertEquals(0x33, machine.memory.read(0xA000))
    }

    @Test
    fun `$35 keeps IO but nothing else`() {
        machine.memory.poke(0xE000, 0x22)
        port(0x35)
        assertEquals(0x22, machine.memory.read(0xE000))
        assertEquals(0x1B, machine.memory.read(0xD011) and 0x7F)
    }

    @Test
    fun `a write under a ROM lands in the RAM beneath it`() {
        port(0x37)
        machine.memory.write(0xE000, 0x99)
        assertEquals(KERNAL, machine.memory.read(0xE000))
        port(0x34)
        assertEquals(0x99, machine.memory.read(0xE000))
    }

    @Test
    fun `the VIC sees the character ROM in banks zero and two`() {
        // CIA 2 port A selects the bank, inverted: 3 means bank 0.
        machine.memory.write(0xDD02, 0x3F)
        machine.memory.write(0xDD00, 0x03)
        assertEquals(CHARACTER, machine.memory.vicRead(0x1000))
        machine.memory.poke(0x0400, 0x77)
        assertEquals(0x77, machine.memory.vicRead(0x0400))
    }

    private companion object {
        const val BASIC = 0xB1
        const val KERNAL = 0xCE
        const val CHARACTER = 0xC4
    }
}
