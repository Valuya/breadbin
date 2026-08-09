package be.valuya.breadbin.engine.vic

import be.valuya.breadbin.engine.Machine
import be.valuya.breadbin.engine.TestRoms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video chip, checked by looking at the pixels it produces.
 *
 * A frame from a machine sitting in a loop is entirely determined by the registers and the memory
 * behind them, so the colour of a given pixel is a fact rather than a judgement, and these tests
 * assert exactly that.
 */
class VicIITest {

    /** The machine spins in a loop at $e000 so that only the video chip is doing anything. */
    private fun idleMachine() = Machine(TestRoms.of(intArrayOf(0x4C, 0x00, 0xE0)))

    private fun Machine.clearScreen(character: Int = 0x20, colour: Int = 1) {
        for (i in 0 until 1000) {
            memory.poke(0x0400 + i, character)
            memory.colorRam[i] = colour
        }
    }

    /** Where a character cell's top-left pixel lands in the output frame. */
    private fun pixelOf(machine: Machine, column: Int, row: Int): Int {
        val model = machine.model
        val x = DISPLAY_LEFT + column * 8 - model.firstVisibleX
        val y = DISPLAY_TOP + row * 8 - model.firstVisibleLine
        return machine.vic.frame[((y + 512) % 512) * model.width + ((x + 512) % 512)]
    }

    @Test
    fun `a character cell is drawn in its colour on the background`() {
        val machine = idleMachine()
        machine.clearScreen()
        // Character 1 is a solid block in the test character set, so the whole cell is foreground.
        machine.memory.poke(0x0400, 0x01)
        machine.memory.colorRam[0] = 1 // white
        machine.memory.write(0xD021, 6) // blue background
        machine.runFrame()
        machine.runFrame()

        assertEquals(Palette.ARGB[1], pixelOf(machine, 0, 0))
        assertEquals(Palette.ARGB[6], pixelOf(machine, 1, 0))
    }

    @Test
    fun `the border surrounds the display window`() {
        val machine = idleMachine()
        machine.clearScreen()
        machine.memory.write(0xD020, 2) // red border
        machine.memory.write(0xD021, 6)
        machine.runFrame()
        machine.runFrame()

        val width = machine.model.width
        assertEquals(Palette.ARGB[2], machine.vic.frame[0])
        assertEquals(Palette.ARGB[2], machine.vic.frame[width - 1])
        assertEquals(Palette.ARGB[6], pixelOf(machine, 0, 0))
    }

    @Test
    fun `clearing DEN blanks the screen to the border colour`() {
        val machine = idleMachine()
        machine.clearScreen(character = 0x01)
        machine.memory.write(0xD020, 2)
        machine.memory.write(0xD011, 0x0B) // the same as the default, with DEN cleared
        machine.runFrame()
        machine.runFrame()

        assertEquals(Palette.ARGB[2], pixelOf(machine, 0, 0))
        assertEquals(Palette.ARGB[2], pixelOf(machine, 20, 10))
    }

    @Test
    fun `the raster register counts through a whole frame`() {
        val machine = idleMachine()
        val seen = HashSet<Int>()
        // Sampling the register from outside costs nothing and never disturbs the emulation.
        repeat(machine.model.cyclesPerFrame / 4) {
            machine.cpu.step()
            seen += machine.vic.rasterY
        }
        assertTrue("saw ${seen.size} raster lines", seen.size > machine.model.linesPerFrame / 2)
        assertTrue(seen.all { it in 0 until machine.model.linesPerFrame })
    }

    @Test
    fun `a raster interrupt fires once on the line it was asked for`() {
        // $e000 enables interrupts and loops; $e010 counts them and acknowledges the chip.
        val program = IntArray(0x20) { 0xEA }
        intArrayOf(0x58, 0x4C, 0x01, 0xE0).copyInto(program, 0)
        intArrayOf(0xEE, 0x00, 0xC0, 0xA9, 0x01, 0x8D, 0x19, 0xD0, 0x40).copyInto(program, 0x10)
        val machine = Machine(TestRoms.of(program, at = 0xE000, irqAt = 0xE010))
        machine.clearScreen()

        machine.memory.write(0xD012, 100)
        machine.memory.write(0xD01A, 0x01)
        machine.runFrame()
        val afterOne = machine.memory.peek(0xC000)
        machine.runFrame()
        val afterTwo = machine.memory.peek(0xC000)

        assertNotEquals("no raster interrupt arrived", 0, afterOne)
        assertEquals("a raster interrupt should fire once per frame", 1, afterTwo - afterOne)
    }

    @Test
    fun `a sprite is drawn over the background where it was placed`() {
        val machine = idleMachine()
        machine.clearScreen()
        machine.memory.write(0xD021, 0) // black background

        // Sprite data at $0340, which is the cassette buffer and where everyone put sprites.
        val pointer = 0x0340 / 64
        machine.memory.poke(0x07F8, pointer)
        for (i in 0 until 63) machine.memory.poke(0x0340 + i, 0xFF)

        machine.memory.write(0xD000, 100) // X
        machine.memory.write(0xD001, 100) // Y
        machine.memory.write(0xD027, 7)   // yellow
        machine.memory.write(0xD015, 0x01)
        machine.runFrame()
        machine.runFrame()

        val model = machine.model
        val x = 100 - model.firstVisibleX + 512
        val y = 100 - model.firstVisibleLine
        assertEquals(Palette.ARGB[7], machine.vic.frame[y * model.width + (x % 512)])
        // Just above the sprite is still background.
        assertEquals(Palette.ARGB[0], machine.vic.frame[(y - 2) * model.width + (x % 512)])
    }

    @Test
    fun `a sprite over a character registers a collision`() {
        val machine = idleMachine()
        machine.clearScreen(character = 0x01) // solid blocks everywhere
        val pointer = 0x0340 / 64
        machine.memory.poke(0x07F8, pointer)
        for (i in 0 until 63) machine.memory.poke(0x0340 + i, 0xFF)
        machine.memory.write(0xD000, 100)
        machine.memory.write(0xD001, 100)
        machine.memory.write(0xD015, 0x01)
        machine.runFrame()
        machine.runFrame()

        assertEquals(0x01, machine.memory.read(0xD01F) and 0x01)
        // Reading it clears it, so the second read finds nothing.
        assertEquals(0x00, machine.memory.read(0xD01F) and 0x01)
    }

    private companion object {
        /** The sprite X coordinate of the left edge of the display window. */
        const val DISPLAY_LEFT = 24

        /** The first raster line of the display window with RSEL set. */
        const val DISPLAY_TOP = 51
    }
}
