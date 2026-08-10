package be.valuya.breadbin.engine.cia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard matrix, checked against the table in the Programmer's Reference Guide rather than
 * against the way this emulator happens to store it.
 *
 * The whole point of these is that they are written from the outside: drive a line, read a port,
 * compare with what a real C64 would put there. Testing the matrix against itself would have been
 * perfectly happy with the rows and the columns swapped over, which is exactly how the on-screen
 * keyboard came to do nothing at all.
 */
class KeyboardTest {

    private val keyboard = Keyboard()
    private val cia = Cia(keyboard, 985_248) { }

    init {
        cia.write(0x02, 0xFF) // port A drives
        cia.write(0x03, 0x00) // port B senses
    }

    /** Drives every port A line low except [row], then reads the columns back. */
    private fun scan(row: Int): Int {
        cia.write(0x00, (1 shl row).inv() and 0xFF)
        return cia.read(0x01)
    }

    @Test
    fun `an idle keyboard reads as nothing pressed`() {
        for (row in 0 until 8) assertEquals("row $row", 0xFF, scan(row))
    }

    @Test
    fun `A is on PA1 and comes back on PB2`() {
        // The guide's table: driving PA1 low reads 3, W, A, 4, Z, S, E and left shift on PB0..PB7.
        keyboard.press(C64Key.A)
        assertEquals(0xFF and (1 shl 2).inv(), scan(1))
        // and on no other row.
        for (row in 0 until 8) if (row != 1) assertEquals("row $row", 0xFF, scan(row))
    }

    @Test
    fun `RETURN is on PA0 and comes back on PB1`() {
        keyboard.press(C64Key.RETURN)
        assertEquals(0xFF and (1 shl 1).inv(), scan(0))
    }

    @Test
    fun `RUN STOP is on PA7 and comes back on PB7`() {
        // The top three lines of each port are also the joystick's, which is where an idle stick
        // holding them down would show up: everything from row five up would read as jammed.
        keyboard.press(C64Key.RUN_STOP)
        assertEquals(0xFF and (1 shl 7).inv(), scan(7))
        assertEquals(0xFF, scan(6))
    }

    @Test
    fun `two keys in one row come back together`() {
        keyboard.press(C64Key.Z)
        keyboard.press(C64Key.S)
        assertEquals(0xFF and (1 shl 4).inv() and (1 shl 5).inv(), scan(1))
    }

    @Test
    fun `releasing a key lets its line back up`() {
        keyboard.press(C64Key.A)
        keyboard.release(C64Key.A)
        assertEquals(0xFF, scan(1))
    }

    /**
     * The other direction. The KERNAL finds out whether anything at all is down by driving every
     * column low and reading port A, and that read goes through completely different code.
     */
    @Test
    fun `a keypress shows up scanning the matrix the other way up`() {
        cia.write(0x02, 0x00) // port A senses
        cia.write(0x03, 0xFF) // port B drives
        cia.write(0x01, 0x00) // every column low

        assertEquals(0xFF, cia.read(0x00))
        keyboard.press(C64Key.A) // row one
        assertEquals(0xFF and (1 shl 1).inv(), cia.read(0x00))
    }

    @Test
    fun `an idle joystick holds nothing down`() {
        keyboard.setJoystick(JoystickPort.TWO, up = false, down = false, left = false, right = false, fire = false)
        keyboard.setJoystick(JoystickPort.ONE, up = false, down = false, left = false, right = false, fire = false)
        for (row in 0 until 8) assertEquals("row $row", 0xFF, scan(row))
    }

    /**
     * Port two, which is the one games use, and the one nothing here tested until a game would not
     * start. It reads on port A, through the same handler as the keyboard rows.
     */
    @Test
    fun `a joystick in port two pulls its lines low on port A`() {
        // What a game does before reading it: stop driving port A, so the stick is all that is
        // pulling on it.
        cia.write(0x02, 0x00)
        cia.write(0x03, 0xFF)
        cia.write(0x01, 0xFF) // no keyboard column held down

        assertEquals("an idle stick is not holding anything", 0xFF, cia.read(0x00))

        keyboard.setJoystick(JoystickPort.TWO, up = false, down = false, left = false, right = false, fire = true)
        assertEquals("fire is bit 4, active low", 0xFF and 0x10.inv(), cia.read(0x00))

        keyboard.setJoystick(JoystickPort.TWO, up = true, down = false, left = false, right = false, fire = false)
        assertEquals("up is bit 0", 0xFF and 0x01.inv(), cia.read(0x00))

        keyboard.setJoystick(JoystickPort.TWO, up = false, down = false, left = false, right = true, fire = true)
        assertEquals("right and fire together", 0xFF and 0x08.inv() and 0x10.inv(), cia.read(0x00))
    }

    /** The other thing a game does: leave port A driving high and read it anyway. */
    @Test
    fun `port two still reads with the port driven high`() {
        cia.write(0x02, 0xFF)
        cia.write(0x00, 0xFF)
        keyboard.setJoystick(JoystickPort.TWO, up = false, down = false, left = false, right = false, fire = true)
        assertEquals(0xFF and 0x10.inv(), cia.read(0x00))
    }

    @Test
    fun `the two sticks do not read each other`() {
        cia.write(0x02, 0x00)
        cia.write(0x03, 0xFF)
        cia.write(0x01, 0xFF)
        keyboard.setJoystick(JoystickPort.ONE, up = false, down = false, left = false, right = false, fire = true)
        assertEquals("port one showed up on port A", 0xFF, cia.read(0x00))
    }

    @Test
    fun `a joystick in port one pulls its direction low on port B`() {
        keyboard.setJoystick(JoystickPort.ONE, up = false, down = false, left = false, right = true, fire = true)
        // Right is bit 3, fire is bit 4, and both are active low.
        assertEquals(0xFF and 0x08.inv() and 0x10.inv(), scan(0))
    }

    /**
     * The one that matters off the desktop, and the one no ordinary test can see.
     *
     * Every test in this file drives both sides from one thread. The app does not: the finger is on
     * one thread and the processor is on another, reading these in a tight loop that touches
     * nothing else. A plain field there may be loaded once and kept in a register for good, and
     * then the joystick moves and the emulated machine never finds out — which is exactly what was
     * reported, and why the keyboard appeared to work while the stick did not. The matrix is an
     * array, whose loads are not hoisted so eagerly, so it got away with it.
     *
     * Asserting on the declaration rather than racing two threads makes this deterministic. A race
     * test would pass on a good day with the bug present, which is worse than no test at all.
     */
    @Test
    fun `the state the UI writes is safely published to the emulation thread`() {
        val joystickOne = Keyboard::class.java.getDeclaredField("joystick1")
        val joystickTwo = Keyboard::class.java.getDeclaredField("joystick2")
        for (field in listOf(joystickOne, joystickTwo)) {
            assertTrue(
                "${field.name} is not volatile, so a moved joystick may never be seen",
                java.lang.reflect.Modifier.isVolatile(field.modifiers),
            )
        }
        val matrix = Keyboard::class.java.getDeclaredField("matrix")
        assertEquals(
            "the key matrix is a plain array, which carries no promise across threads",
            java.util.concurrent.atomic.AtomicIntegerArray::class.java,
            matrix.type,
        )
    }
}
