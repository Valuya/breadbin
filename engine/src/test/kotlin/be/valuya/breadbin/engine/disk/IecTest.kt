package be.valuya.breadbin.engine.disk

import be.valuya.breadbin.engine.Machine
import be.valuya.breadbin.engine.TestRoms
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The virtual drive, driven the way the KERNAL drives it: real 6502 code calling through the
 * KERNAL jump table, with the traps answering from a .d64 image.
 *
 * This is the test that matters for disk support, because it exercises the whole path — the jump
 * table lookup, the patches, the channel handling and the sector chains — rather than any one part
 * of it.
 */
class IecTest {

    /** Assembles the sequence a program uses to open a file, read it, and stop. */
    private fun loadRoutine(name: String): IntArray {
        val code = ArrayList<Int>()
        fun lda(value: Int) { code += 0xA9; code += value }
        fun jsr(address: Int) { code += 0x20; code += address and 0xFF; code += (address shr 8) and 0xFF }

        lda(0x08); jsr(0xFFB1)              // LISTEN 8
        lda(0xF0); jsr(0xFF93)              // SECOND: open channel 0
        for (character in name) { lda(Petscii.fromAscii(character)); jsr(0xFFA8) } // CIOUT the name
        jsr(0xFFAE)                         // UNLSN
        lda(0x08); jsr(0xFFB4)              // TALK 8
        lda(0x60); jsr(0xFF96)              // TKSA: data on channel 0
        // A sixteen-bit destination pointer in zero page, because a file is longer than a page.
        lda(0x00); code += 0x85; code += 0xFB
        lda(0xC0); code += 0x85; code += 0xFC
        code += 0xA0; code += 0x00          // LDY #0

        val loop = code.size
        jsr(0xFFA5)                         // ACPTR
        code += 0x91; code += 0xFB          // STA ($FB),Y
        code += 0xC8                        // INY
        code += 0xD0; code += 0x02          // BNE over the page bump
        code += 0xE6; code += 0xFC          // INC $FC
        code += 0xA5; code += 0x90          // LDA ST
        code += 0xF0                        // BEQ loop
        code += (loop - (code.size + 1)) and 0xFF

        jsr(0xFFAB)                         // UNTLK
        code += 0x12                        // an illegal opcode, to stop the processor dead
        return code.toIntArray()
    }

    private fun runLoad(disk: D64, name: String, steps: Int = 200_000): Machine {
        val machine = Machine(TestRoms.of(loadRoutine(name)))
        assertTrue("the drive could not patch the KERNAL", machine.virtualDriveAvailable)
        machine.insertDisk(disk)
        machine.reset()
        var remaining = steps
        while (remaining-- > 0 && !machine.cpu.jammed) machine.cpu.step()
        assertTrue("the routine never finished", machine.cpu.jammed)
        return machine
    }

    private fun read(machine: Machine, count: Int) =
        IntArray(count) { machine.memory.peek(0xC000 + it) }

    @Test
    fun `a file loads through the KERNAL's serial routines`() {
        val disk = D64.blank(Petscii.fromAscii("DISK"), Petscii.fromAscii("01"))
        val contents = IntArray(700) { (it * 7) and 0xFF }
        disk.writeFile(Petscii.fromAscii("GAME"), contents, fileType = 2, replace = false)

        val machine = runLoad(disk, "GAME")
        assertArrayEquals(contents, read(machine, contents.size))
        // EOI, not a timeout: the file was there and it ended.
        assertEquals(0x40, machine.memory.peek(0x90))
    }

    @Test
    fun `a missing file reports a timeout, which is what FILE NOT FOUND is made of`() {
        val disk = D64.blank(Petscii.fromAscii("DISK"), Petscii.fromAscii("01"))
        val machine = runLoad(disk, "ABSENT")
        assertEquals(0x02, machine.memory.peek(0x90) and 0x02)
    }

    @Test
    fun `no drive at all reports device not present`() {
        val machine = Machine(TestRoms.of(loadRoutine("GAME")))
        machine.reset()
        var remaining = 200_000
        while (remaining-- > 0 && !machine.cpu.jammed) machine.cpu.step()
        assertEquals(0x80, machine.memory.peek(0x90) and 0x80)
    }

    @Test
    fun `the directory loads as a program`() {
        val disk = D64.blank(Petscii.fromAscii("MY DISK"), Petscii.fromAscii("01"))
        disk.writeFile(Petscii.fromAscii("THING"), IntArray(300), 2, replace = false)

        val machine = runLoad(disk, "$")
        val listing = read(machine, DirectoryListing.of(disk).size)
        assertArrayEquals(DirectoryListing.of(disk), listing)
    }

    @Test
    fun `a wildcard loads the first matching file`() {
        val disk = D64.blank(Petscii.fromAscii("DISK"), Petscii.fromAscii("01"))
        val contents = IntArray(100) { 0x5A }
        disk.writeFile(Petscii.fromAscii("LOADER"), contents, 2, replace = false)
        disk.writeFile(Petscii.fromAscii("OTHER"), IntArray(100) { 0x11 }, 2, replace = false)

        val machine = runLoad(disk, "*")
        assertArrayEquals(contents, read(machine, contents.size))
    }
}
