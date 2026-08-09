package be.valuya.breadbin.engine.sid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The sound chip, checked by listening to it.
 *
 * There is not much point asserting on a waveform sample by sample — the interesting parts of a SID
 * are analogue and approximate, and a test that pinned every value would break on every improvement.
 * What can be pinned is everything a person would notice immediately: that a gated voice makes a
 * sound, that an ungated one does not, that the volume register does something, that a note stops
 * when it is released, and — the one nothing was watching — that what comes out is at a level a
 * speaker can actually reproduce.
 */
class SidTest {

    private val clock = 985_248
    private val rate = 48_000

    private fun sid() = Sid(clock, rate).also { it.write(0x18, 0x0F) }

    /** Sets a voice going with a sawtooth and a square envelope: instant on, full sustain. */
    private fun Sid.play(voice: Int, frequency: Int = 0x1000) {
        write(voice * 7 + 0, frequency and 0xFF)
        write(voice * 7 + 1, (frequency shr 8) and 0xFF)
        write(voice * 7 + 5, 0x00) // attack 0, decay 0
        write(voice * 7 + 6, 0xF0) // sustain 15, release 0
        write(voice * 7 + 4, 0x21) // sawtooth, gated
    }

    private fun Sid.release(voice: Int) = write(voice * 7 + 4, 0x20)

    /** Runs for a while and returns the loudest sample that came out. */
    private fun Sid.peakOver(seconds: Double): Int {
        val buffer = ShortArray((rate * seconds).toInt() + 16)
        var peak = 0
        var produced = 0
        repeat((clock * seconds).toInt()) { clock() }
        produced = readSamples(buffer, buffer.size)
        for (i in 0 until produced) {
            val level = abs(buffer[i].toInt())
            if (level > peak) peak = level
        }
        return peak
    }

    private fun percent(peak: Int) = 100.0 * peak / 32767

    @Test
    fun `a gated voice comes out at a level something could play`() {
        val sid = sid()
        sid.play(0)
        val peak = sid.peakOver(0.5)
        // The exact number is a matter of taste. Being audible is not: one voice this far down
        // would be inaudible on a phone under any circumstances, and for a long time it was.
        assertTrue("one voice peaks at only %.1f%% of full scale".format(percent(peak)), peak > 32767 / 10)
        assertTrue("one voice peaks at %.1f%%, which will clip".format(percent(peak)), peak < 32767 * 6 / 10)
    }

    @Test
    fun `three voices are louder than one and still fit`() {
        val one = sid().also { it.play(0) }.peakOver(0.5)
        val three = sid().also { it.play(0, 0x1000); it.play(1, 0x1400); it.play(2, 0x1800) }.peakOver(0.5)

        assertTrue("three voices ($three) are no louder than one ($one)", three > one)
        // Headroom matters: the resonant filter adds to this, and everything above full scale is
        // thrown away by the clamp on the way out.
        assertTrue(
            "three voices peak at %.1f%% of full scale, with nothing left for the filter".format(percent(three)),
            three < 32767 * 9 / 10,
        )
    }

    @Test
    fun `a chip with nothing gated is silent`() {
        assertEquals(0, sid().peakOver(0.2))
    }

    @Test
    fun `a voice with the waveform bits clear is silent`() {
        val sid = sid()
        sid.write(0x05, 0x00)
        sid.write(0x06, 0xF0)
        sid.write(0x04, 0x01) // gated, but no waveform selected
        assertEquals(0, sid.peakOver(0.2))
    }

    @Test
    fun `volume zero is silence`() {
        val sid = sid()
        sid.play(0)
        sid.write(0x18, 0x00)
        assertEquals(0, sid.peakOver(0.2))
    }

    @Test
    fun `a released note dies away`() {
        val sid = sid()
        sid.play(0)
        sid.peakOver(0.2)

        sid.write(0x06, 0xF0) // release 0, which is the fastest there is
        sid.release(0)
        sid.peakOver(0.2) // the note is on its way down through this, and allowed to be
        assertEquals("the note is still sounding after it was released", 0, sid.peakOver(0.2))
    }

    @Test
    fun `samples come out at the rate they were asked for`() {
        val sid = sid()
        sid.play(0)
        repeat(clock) { sid.clock() } // one second of machine time
        val buffer = ShortArray(rate * 2)
        val produced = sid.readSamples(buffer, buffer.size)
        // A cycle either way is rounding; anything more is a clock that does not keep time, and
        // the audio device paces the whole emulator off this.
        assertTrue("one second of machine produced $produced samples, not about $rate",
            abs(produced - rate) < rate / 100)
    }

    @Test
    fun `the third oscillator can be read back while it runs`() {
        val sid = sid()
        sid.play(2)
        val seen = mutableSetOf<Int>()
        repeat(20_000) {
            sid.clock()
            seen += sid.read(0x1B)
        }
        // Music routines use this as a random number generator and as a modulation source, so a
        // value that never changes is a voice that is not running.
        assertTrue("oscillator 3 read back only ${seen.size} distinct values", seen.size > 16)
    }

    @Test
    fun `a write-only register reads back the last thing written`() {
        val sid = sid()
        sid.write(0x04, 0x41)
        assertEquals(0x41, sid.read(0x04))
    }
}
