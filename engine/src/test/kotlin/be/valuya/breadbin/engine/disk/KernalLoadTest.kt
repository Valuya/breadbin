package be.valuya.breadbin.engine.disk

import be.valuya.breadbin.engine.Machine
import be.valuya.breadbin.engine.mem.Roms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Commodore's own KERNAL, loading a file off the emulated bus.
 *
 * Everything else that tests the drive tests it against the free replacement ROMs, because those
 * are the ones that can live in this repository — which meant the drive was tuned, twice, against
 * one particular KERNAL's idea of how fast a bit may go past. Commodore's is a different program
 * with different timings, and it is the one every game was written for.
 *
 * BASIC is not needed for this and is not used: a synthetic BASIC ROM whose cold-start vector
 * points at a few dozen bytes of 6502 is enough. Those bytes call SETLFS, SETNAM and LOAD through
 * the KERNAL's own jump table and write the answer where the test can read it, so what is under
 * test is the KERNAL's serial routines against this drive and nothing else at all.
 *
 * Point `BREADBIN_ROMS` at a directory holding an 8K KERNAL to run it.
 */
class KernalLoadTest {

    /** Where the stub leaves its answer. */
    private val statusAt = 0xC000
    private val endLowAt = 0xC001
    private val endHighAt = 0xC002
    private val doneAt = 0xC003

    /**
     * A BASIC ROM that is not BASIC. The KERNAL finishes its reset with `JMP ($A000)`, so the first
     * two bytes decide where the machine goes, and everything after them is ours.
     */
    private fun stubBasic(name: String, secondary: Int): IntArray {
        val rom = IntArray(0x2000)
        val start = 0xA010
        val nameAt = 0xA080
        rom[0] = start and 0xFF
        rom[1] = (start shr 8) and 0xFF
        rom[2] = start and 0xFF
        rom[3] = (start shr 8) and 0xFF

        val code = ArrayList<Int>()
        code += listOf(0xA9, 0x01)                       // LDA #1        logical file
        code += listOf(0xA2, 0x08)                       // LDX #8        device
        code += listOf(0xA0, secondary)                  // LDY #secondary
        code += listOf(0x20, 0xBA, 0xFF)                 // JSR SETLFS
        code += listOf(0xA9, name.length)                // LDA #length
        code += listOf(0xA2, nameAt and 0xFF)            // LDX #<name
        code += listOf(0xA0, (nameAt shr 8) and 0xFF)    // LDY #>name
        code += listOf(0x20, 0xBD, 0xFF)                 // JSR SETNAM
        code += listOf(0xA9, 0x00)                       // LDA #0        0 means load, not verify
        code += listOf(0xA2, 0x00, 0xA0, 0x20)           // LDX/LDY       where to put it if asked
        code += listOf(0x20, 0xD5, 0xFF)                 // JSR LOAD
        code += listOf(0x8E, endLowAt and 0xFF, (endLowAt shr 8) and 0xFF)
        code += listOf(0x8C, endHighAt and 0xFF, (endHighAt shr 8) and 0xFF)
        // ST, not the accumulator: LOAD leaves an error code in A only when it sets the carry, and
        // reading A regardless is how this test spent a while accusing a working load of failing.
        code += listOf(0xA5, 0x90)                       // LDA $90
        code += listOf(0x8D, statusAt and 0xFF, (statusAt shr 8) and 0xFF)
        code += listOf(0xA9, 0xFF)                       // LDA #$FF      done
        code += listOf(0x8D, doneAt and 0xFF, (doneAt shr 8) and 0xFF)
        code += listOf(0x4C, start and 0xFF, (start shr 8) and 0xFF) // and sit there

        for (i in code.indices) rom[start - 0xA000 + i] = code[i]
        for (i in name.indices) rom[nameAt - 0xA000 + i] = Petscii.fromAscii(name[i])
        return rom
    }

    private fun kernal(): IntArray? {
        val directory = System.getenv("BREADBIN_ROMS")?.let(::File)?.takeIf { it.isDirectory }
            ?: return null
        // Commodore's, specifically — it says which revision it is at $FF80, and a replacement
        // KERNAL will not have a jump table this stub can call into.
        val file = directory.listFiles()
            ?.firstOrNull { it.isFile && it.length() == 8192L && Roms.isCommodoreKernal(it.readBytes()) }
            ?: return null
        val bytes = file.readBytes()
        return IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
    }

    private fun disk(): D64 =
        D64.blank(Petscii.fromAscii("KERNAL TEST"), Petscii.fromAscii("01")).also {
            it.writeFile(Petscii.fromAscii("THING"), CONTENTS, 2, replace = false)
        }

    /** Runs the stub and returns the machine once it has finished, or null if it never did. */
    private fun load(name: String, secondary: Int): Machine? {
        val kernal = kernal() ?: return null
        val roms = Roms(stubBasic(name, secondary), kernal, IntArray(0x1000))
        val machine = Machine(roms)
        machine.insertDisk(disk())
        var frames = 0
        while (frames++ < 4000 && machine.memory.peek(doneAt) != 0xFF) machine.runFrame()
        return machine.takeIf { it.memory.peek(doneAt) == 0xFF }
    }

    @Test
    fun `Commodore's KERNAL loads a file off the drive`() {
        assumeTrue("set BREADBIN_ROMS to a directory holding one of Commodore's KERNALs", kernal() != null)
        // Secondary address 1 loads to the address in the file, which is what a game's autostart
        // does and what LOAD"*",8,1 means.
        val machine = load("THING", 1)
        assertTrue("the KERNAL never came back from LOAD", machine != null)

        val status = machine!!.memory.peek(statusAt)
        val end = machine.memory.peek(endLowAt) or (machine.memory.peek(endHighAt) shl 8)
        println("status=%02X end=%04X wire=%d".format(status, end, machine.wire?.bytesTransferred))

        // Bit 6 is the end of the file and is expected. Everything else is a fault: bit 7 would be
        // DEVICE NOT PRESENT and the low bits are timeouts, and any of them means the transfer
        // limped rather than worked.
        assertEquals("the KERNAL recorded faults, ST = %02X".format(status), 0x40, status)
        // Served rather than sent: a plain LOAD should not put a single bit on the wire.
        assertEquals("the load went down the wire instead of being served",
            0L, machine.wire?.bytesTransferred)
        // The file says it starts at $2000 and is as long as it is, so the KERNAL should stop just
        // past the end of it.
        assertEquals("the file did not land where it says it does", 0x2000 + CONTENTS.size - 2, end)
        for (i in 2 until CONTENTS.size) {
            val got = machine.memory.peek(0x2000 + i - 2)
            assertEquals("byte $i of the file came back wrong", CONTENTS[i], got)
        }
    }

    @Test
    fun `Commodore's KERNAL gets the directory`() {
        assumeTrue(kernal() != null)
        val machine = load("$", 0)
        assertTrue("the KERNAL never came back from LOAD", machine != null)
        // Secondary address zero means "put it where I said", and the stub says $2000.
        val text = (0x2000..0x2200).map { machine!!.memory.peek(it) }
        val ascii = Petscii.toAscii(text.toIntArray())
        assertTrue("no disk name in the directory:\n$ascii", ascii.contains("KERNAL TEST"))
    }

    private companion object {
        /** Two bytes of load address, then something with a shape to it. */
        val CONTENTS = intArrayOf(0x00, 0x20) + IntArray(500) { (it * 11 + 7) and 0xFF }
    }
}
