package be.valuya.breadbin.engine.disk

import be.valuya.breadbin.engine.drive.IecBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The serial bus, with a computer at the other end written in Kotlin rather than 6502.
 *
 * The end-to-end proof that this works is in BootTest, where a real KERNAL loads a real file — but
 * that one needs a ROM set that cannot live in this repository, so it is skipped as often as it is
 * run. This is the version that always runs, and it is stricter as well: it drives the three lines
 * by hand, one at a time, and knows exactly which byte it expected and when.
 *
 * The computer here is deliberately written from the protocol rather than from [IecWire], so that
 * the two are not agreeing with each other about a shared misunderstanding.
 */
class IecWireTest {

    private val iec = Iec()
    private val bus = IecBus()
    private val wire = IecWire(iec, bus)

    private val disk = D64.blank(Petscii.fromAscii("TEST DISK"), Petscii.fromAscii("01")).also {
        it.writeFile(Petscii.fromAscii("HELLO"), IntArray(300) { i -> (i * 7) and 0xFF }, 2, false)
        it.writeFile(Petscii.fromAscii("SHORT"), intArrayOf(1, 2, 3), 2, false)
    }

    init {
        iec.mount(8, disk)
    }

    // ---- the wires, from the computer's side ---------------------------------------------------

    private fun tick(count: Int) = repeat(count) { wire.cycle() }

    private fun waitFor(what: String, limit: Int = 100_000, condition: () -> Boolean) {
        var remaining = limit
        while (remaining-- > 0 && !condition()) wire.cycle()
        assertTrue("gave up waiting for $what", condition())
    }

    /** Counts cycles until something happens, or gives up. Null if it never did. */
    private fun waitUpTo(limit: Int, condition: () -> Boolean): Int? {
        var waited = 0
        while (waited < limit) {
            if (condition()) return waited
            wire.cycle()
            waited++
        }
        return null
    }

    /**
     * Sends one byte as the talker: a bit on DATA, a rising edge on CLK to say it is there, and a
     * wait at the end for the listener to say it took it.
     */
    private fun sendByte(value: Int, expectAck: Boolean = true) {
        bus.computerClock = false // ready to send
        waitFor("the drive to be ready for %02X".format(value)) { !bus.data }
        bus.computerClock = true
        tick(30)
        var bits = value
        repeat(8) {
            bus.computerData = bits and 1 == 0 // a zero is the line pulled down
            bits = bits shr 1
            tick(40)
            bus.computerClock = false
            tick(80)
            bus.computerClock = true
            bus.computerData = false
        }
        if (expectAck) waitFor("the drive to take %02X".format(value)) { bus.data }
    }

    /**
     * Takes one byte as the listener, and says whether it was the last. Null when the drive has
     * simply let go of the bus, which is what it does when it has nothing to send at all.
     */
    private fun receiveByte(): Pair<Int, Boolean>? {
        if (waitUpTo(GONE) { !bus.clock } == null) return null
        bus.computerData = false // ready for it

        var last = false
        if (waitUpTo(PATIENCE) { bus.clock } == null) {
            // Nothing clocked for long enough that there is nothing more after this one. Say so by
            // taking hold of the data line for a moment.
            last = true
            bus.computerData = true
            tick(80)
            bus.computerData = false
            if (waitUpTo(GONE) { bus.clock } == null) return null
        }

        var value = 0
        repeat(8) {
            waitFor("a bit") { !bus.clock }
            value = (value shr 1) or (if (bus.data) 0 else 0x80)
            waitFor("the clock to fall") { bus.clock }
        }
        bus.computerData = true // taken
        return value to last
    }

    /** Pulls attention and sends command bytes under it. */
    private fun command(vararg bytes: Int) {
        bus.computerAtn = true
        bus.computerClock = true
        bus.computerData = false
        waitFor("anything to answer attention") { bus.data }
        for (byte in bytes) sendByte(byte)
    }

    private fun release() {
        bus.computerAtn = false
        tick(200)
    }

    // ---- the things a computer does with a drive -----------------------------------------------

    private fun open(secondary: Int, name: String) {
        command(LISTEN + 8, OPEN + secondary)
        release()
        for (code in Petscii.fromAscii(name)) sendByte(code)
        command(UNLISTEN)
        release()
    }

    private fun readAll(secondary: Int): List<Int> {
        command(TALK + 8, DATA + secondary)
        // Turning the bus round: let go of the clock, take hold of the data line, drop attention.
        bus.computerClock = false
        bus.computerData = true
        release()

        val out = ArrayList<Int>()
        while (out.size < 4096) {
            val (byte, last) = receiveByte() ?: break
            out += byte
            if (last) break
        }
        command(UNTALK)
        release()
        bus.computerClock = false
        bus.computerData = false
        return out
    }

    private fun writeAll(secondary: Int, bytes: List<Int>) {
        command(LISTEN + 8, DATA + secondary)
        release()
        for (byte in bytes) sendByte(byte)
        command(UNLISTEN)
        release()
    }

    // ---- the tests -----------------------------------------------------------------------------

    @Test
    fun `a file comes back byte for byte`() {
        open(0, "HELLO")
        val loaded = readAll(0)
        val expected = disk.readFile(disk.find(Petscii.fromAscii("HELLO"))!!).toList()
        assertEquals("wrong length", expected.size, loaded.size)
        assertEquals(expected, loaded)
    }

    @Test
    fun `a short file comes back too`() {
        open(0, "SHORT")
        assertEquals(listOf(1, 2, 3), readAll(0))
    }

    @Test
    fun `the directory comes back as a program`() {
        open(0, "$")
        val listing = readAll(0)
        assertEquals("a directory starts with its load address", listOf(0x01, 0x04), listing.take(2))
        val text = listing.joinToString("") { Petscii.toAscii(intArrayOf(it)) }
        assertTrue("no disk name in:\n$text", text.contains("TEST DISK"))
        assertTrue("no file in:\n$text", text.contains("HELLO"))
    }

    @Test
    fun `a file written over the bus is on the disk afterwards`() {
        open(1, "NEW")
        writeAll(1, listOf(0x41, 0x42, 0x43))
        val entry = disk.find(Petscii.fromAscii("NEW"))
        assertTrue("the file was never created", entry != null)
        assertEquals(listOf(0x41, 0x42, 0x43), disk.readFile(entry!!).toList())
    }

    @Test
    fun `a name that is not on the disk gives back nothing`() {
        open(0, "MISSING")
        assertEquals(emptyList<Int>(), readAll(0))
    }

    @Test
    fun `the error channel says what went wrong`() {
        open(0, "MISSING")
        readAll(0)
        val status = readAll(15).joinToString("") { Petscii.toAscii(intArrayOf(it)) }
        assertTrue("no file-not-found in \"$status\"", status.startsWith("62,"))
    }

    @Test
    fun `a device that is not there does not answer`() {
        bus.computerAtn = true
        bus.computerClock = true
        bus.computerData = false
        waitFor("anything to answer attention") { bus.data }

        // Everything on the bus answers attention before it knows who is wanted, so this says
        // nothing yet. What matters is what happens once the address turns out to be somebody
        // else's: the drive lets go, and that silence is the whole of how a computer works out
        // that there is nothing on device nine.
        sendByte(LISTEN + 9, expectAck = false)
        waitFor("the drive to let go") { !bus.deviceData && !bus.deviceClock }
    }

    @Test
    fun `attention part way through a transfer abandons it`() {
        open(0, "HELLO")
        command(TALK + 8, DATA + 0)
        bus.computerClock = false
        bus.computerData = true
        release()
        repeat(3) { receiveByte() }


        // Pull attention in the middle of the file, the way a program that has had enough does.
        command(UNTALK)
        release()
        bus.computerClock = false
        bus.computerData = false

        // And then start again from the beginning: the drive should be listening, not still trying
        // to finish what it was doing.
        open(0, "SHORT")
        assertEquals(listOf(1, 2, 3), readAll(0))
    }

    private companion object {
        const val LISTEN = 0x20
        const val UNLISTEN = 0x3F
        const val TALK = 0x40
        const val UNTALK = 0x5F
        const val OPEN = 0xF0
        const val DATA = 0x60

        /** How long to wait for a byte to start before deciding the file is over. */
        const val PATIENCE = 300

        /** And how long before deciding the drive has gone away entirely. */
        const val GONE = 4000
    }
}
