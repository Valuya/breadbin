package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.mem.Roms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Noticing when a program calls into the KERNAL's private half.
 *
 * The point of this is to tell somebody that a game needs Commodore's ROMs rather than leaving them
 * with a hung machine and no idea why. That only helps if it is right, so the tests that matter
 * here are the ones where it must stay quiet: a false alarm sends somebody off to find ROMs they
 * did not need, which is worse than saying nothing.
 *
 * Everything is driven by writing 6502 into RAM and running it, so what is under test is the rule
 * as the processor sees it rather than the arithmetic written out a second time.
 */
class KernalShortcutTest {

    /** A machine with ROMs that are only shaped like ROMs; nothing here executes them. */
    private fun machine(): Machine {
        val kernal = IntArray(0x2000)
        // RTS at every address, so a jump into the KERNAL comes straight back rather than running
        // off into whatever a zero-filled ROM decodes as.
        for (i in kernal.indices) kernal[i] = 0x60
        // The jump table has to be JMPs to itself, near enough: each entry returns as well.
        val basic = IntArray(0x2000) { 0x60 }
        // The reset vector points somewhere harmless that sits still.
        kernal[0x1FFC] = 0x00
        kernal[0x1FFD] = 0xE0
        return Machine(Roms(basic, kernal, IntArray(0x1000)))
    }

    /**
     * Puts a program at $C000 and runs it. $C000 is RAM under no ROM at all, which is where this
     * sort of code actually lives.
     */
    private fun run(machine: Machine, vararg code: Int) {
        for (i in code.indices) machine.memory.poke(0xC000 + i, code[i])
        machine.cpu.pc = 0xC000
        repeat(code.size + 8) { machine.cpu.step() }
    }

    @Test
    fun `a JSR into the middle of the KERNAL is noticed`() {
        val machine = machine()
        // JSR $F4A5 — the KERNAL's LOAD internals, a favourite shortcut.
        run(machine, 0x20, 0xA5, 0xF4)
        assertEquals(0xF4A5, machine.kernalInternalJump)
    }

    @Test
    fun `a JMP into the middle of the KERNAL is noticed too`() {
        val machine = machine()
        // JMP $EA31 — the tail of the interrupt handler, which games chain onto.
        run(machine, 0x4C, 0x31, 0xEA)
        assertEquals(0xEA31, machine.kernalInternalJump)
    }

    @Test
    fun `going in through the jump table is not a shortcut`() {
        val machine = machine()
        // CHROUT at $FFD2, an entry point that every replacement KERNAL implements.
        run(machine, 0x20, 0xD2, 0xFF)
        assertNull("calling CHROUT was reported as a shortcut", machine.kernalInternalJump)
    }

    /** The bounds are written out here rather than taken from the machine, which would agree with
     * itself whatever they were. $FF81 is OPEN and $FFF3 is IOBASE, from the published table. */
    @Test
    fun `every entry in the jump table is allowed`() {
        for (entry in 0xFF81..0xFFF3 step 3) {
            val machine = machine()
            run(machine, 0x20, entry and 0xFF, (entry shr 8) and 0xFF)
            assertNull(
                "calling \$%04X was reported as a shortcut".format(entry),
                machine.kernalInternalJump,
            )
        }
    }

    /** An address inside the table but not on an entry is a shortcut: it lands mid-instruction. */
    @Test
    fun `landing between two jump table entries is still a shortcut`() {
        val machine = machine()
        run(machine, 0x20, 0xD3, 0xFF) // one past CHROUT
        assertEquals(0xFFD3, machine.kernalInternalJump)
    }

    @Test
    fun `a jump that stays in RAM is nobody's business`() {
        val machine = machine()
        run(machine, 0x4C, 0x10, 0xC0)
        assertNull(machine.kernalInternalJump)
    }

    /**
     * The important quiet case. With the KERNAL banked out, $E000 is RAM and a program is entitled
     * to put its own code there and jump about in it as much as it likes.
     */
    @Test
    fun `jumping into RAM under a banked-out KERNAL is not a shortcut`() {
        val machine = machine()
        // The bank switch is written as 6502 and executed, because poking $01 straight into RAM
        // does not go through the processor port and so does not switch anything — which is how
        // this test came to pass while proving nothing.
        run(
            machine,
            0xA9, 0x2F, 0x85, 0x00, // LDA #$2F : STA $00   the port's direction
            0xA9, 0x35, 0x85, 0x01, // LDA #$35 : STA $01   RAM from $A000 up, I/O still there
            0x4C, 0x31, 0xEA,       // JMP $EA31            into what is now RAM
        )
        assertNull("a jump into RAM was blamed on the KERNAL", machine.kernalInternalJump)
    }

    /**
     * And the KERNAL calling itself, which it does constantly and which must never be reported.
     *
     * The ROM here is RTS everywhere, so this puts a JMP into the ROM image itself and starts the
     * processor inside it — the one case where the jump genuinely comes from ROM.
     */
    @Test
    fun `the KERNAL jumping within itself is not reported`() {
        val kernal = IntArray(0x2000) { 0x60 }
        kernal[0x0100] = 0x4C // JMP $EA31, at $E100
        kernal[0x0101] = 0x31
        kernal[0x0102] = 0xEA
        val machine = Machine(Roms(IntArray(0x2000) { 0x60 }, kernal, IntArray(0x1000)))
        machine.cpu.pc = 0xE100
        repeat(4) { machine.cpu.step() }
        assertNull("the KERNAL calling itself was reported", machine.kernalInternalJump)
    }

    @Test
    fun `only the first one is kept`() {
        val machine = machine()
        run(machine, 0x20, 0xA5, 0xF4, 0x20, 0x31, 0xEA)
        assertEquals("a later jump overwrote the first", 0xF4A5, machine.kernalInternalJump)
    }

    /**
     * The one that decides whether this is fit to show anybody: a real ROM set, switched on and
     * left to boot, must produce nothing at all. A machine that accuses its own ROMs of taking
     * shortcuts the moment it starts would put the notice on the screen for every game ever run,
     * which is the same as having no notice.
     */
    @Test
    fun `a real ROM set boots without anything looking like a shortcut`() {
        val directory = System.getenv("BREADBIN_ROMS")?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue(directory != null)
        val files = directory!!.listFiles()?.filter { it.isFile }.orEmpty()
        val basic = files.firstOrNull { it.name.contains("basic", true) && it.length() == 8192L }
        val kernal = files.firstOrNull { it.name.contains("kernal", true) && it.length() == 8192L }
        val character = files.firstOrNull {
            (it.name.contains("char", true) || it.name.contains("font", true)) && it.length() == 4096L
        }
        assumeTrue(basic != null && kernal != null && character != null)

        val machine = Machine(Roms.of(basic!!.readBytes(), kernal!!.readBytes(), character!!.readBytes()))
        repeat(400) { machine.runFrame() }
        assertNull(
            "booting reported a shortcut to \$%04X".format(machine.kernalInternalJump ?: 0),
            machine.kernalInternalJump,
        )

        // And typing at the prompt, which is BASIC and the KERNAL working together as hard as they
        // ever do outside a game.
        machine.type("PRINT 2+2\r")
        repeat(200) { machine.runFrame() }
        assertNull(
            "using BASIC reported a shortcut to \$%04X".format(machine.kernalInternalJump ?: 0),
            machine.kernalInternalJump,
        )
    }

    @Test
    fun `a reset clears it`() {
        val machine = machine()
        run(machine, 0x20, 0xA5, 0xF4)
        assertEquals(0xF4A5, machine.kernalInternalJump)
        machine.reset()
        assertNull(machine.kernalInternalJump)
    }
}
