package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.vic.VideoModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much time the processor actually gets.
 *
 * A C64's processor does not run at 985248 instructions' worth of cycles a second: on every eighth
 * raster line of the display the video chip takes the bus away for forty cycles to fetch the video
 * matrix. Games are written against that — a raster routine that fits in a line with a badline in
 * it fits, and one that does not, does not — so the amount taken is worth pinning down.
 */
class MachineTimingTest {

    /** A loop that counts iterations into a sixteen-bit counter at $c000. */
    private val counter = intArrayOf(
        0xEE, 0x00, 0xC0, // INC $C000
        0xD0, 0x03,       // BNE over the high byte
        0xEE, 0x01, 0xC0, // INC $C001
        0x4C, 0x00, 0xE0, // JMP $E000
    )

    private fun countIterations(displayEnabled: Boolean, frames: Int): Int {
        val machine = Machine(TestRoms.of(counter))
        machine.memory.write(0xD011, if (displayEnabled) 0x1B else 0x0B)
        // Settle for a frame first, so that the count covers whole frames only.
        machine.runFrame()
        machine.memory.poke(0xC000, 0)
        machine.memory.poke(0xC001, 0)
        repeat(frames) { machine.runFrame() }
        return machine.memory.peek(0xC000) or (machine.memory.peek(0xC001) shl 8)
    }

    @Test
    fun `a frame is exactly as many cycles as the chip says it is`() {
        val machine = Machine(TestRoms.of())
        machine.runFrame()
        val start = machine.cycles
        repeat(50) { machine.runFrame() }
        val elapsed = machine.cycles - start
        val expected = VideoModel.PAL.cyclesPerFrame.toLong() * 50
        // The frame ends at the first instruction boundary after the chip finishes, so a frame can
        // run a few cycles long; over fifty of them the drift stays within one instruction.
        assertTrue("$elapsed cycles against an expected $expected", Math.abs(elapsed - expected) < 20)
    }

    @Test
    fun `PAL runs at just over fifty frames a second`() {
        assertEquals(50.12, VideoModel.PAL.framesPerSecond, 0.01)
        assertEquals(59.83, VideoModel.NTSC.framesPerSecond, 0.01)
    }

    @Test
    fun `badlines take about a twentieth of the processor's time`() {
        val withScreen = countIterations(displayEnabled = true, frames = 20)
        val withoutScreen = countIterations(displayEnabled = false, frames = 20)

        assertTrue("nothing ran", withoutScreen > 1000)
        assertTrue(
            "the screen cost nothing, so badlines are not stealing cycles",
            withScreen < withoutScreen,
        )
        // Twenty-five badlines of forty cycles each, out of 19656 in a frame: 5.1%.
        val stolen = (withoutScreen - withScreen).toDouble() / withoutScreen
        assertTrue("badlines took %.1f%% of the time".format(stolen * 100), stolen in 0.035..0.075)
    }

    @Test
    fun `an NTSC machine has a shorter frame and more of them`() {
        val machine = Machine(TestRoms.of(), model = VideoModel.NTSC)
        machine.runFrame()
        val start = machine.cycles
        machine.runFrame()
        assertTrue(machine.cycles - start < VideoModel.PAL.cyclesPerFrame)
        assertEquals(65 * 263, VideoModel.NTSC.cyclesPerFrame)
    }
}
