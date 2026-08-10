package be.valuya.breadbin.engine.cia

/**
 * A key on the C64's keyboard, given by where it sits in the 8x8 matrix the KERNAL scans: the
 * column is driven low on CIA 1's port A and the row is read back on port B.
 *
 * RESTORE is not in the matrix at all — it is wired straight to the processor's NMI — so it is not
 * here either; [be.valuya.breadbin.engine.Machine.restore] presses it.
 */
enum class C64Key(val row: Int, val column: Int) {
    INSERT_DELETE(0, 0), RETURN(0, 1), CURSOR_RIGHT(0, 2), F7(0, 3),
    F1(0, 4), F3(0, 5), F5(0, 6), CURSOR_DOWN(0, 7),

    KEY_3(1, 0), W(1, 1), A(1, 2), KEY_4(1, 3),
    Z(1, 4), S(1, 5), E(1, 6), LEFT_SHIFT(1, 7),

    KEY_5(2, 0), R(2, 1), D(2, 2), KEY_6(2, 3),
    C(2, 4), F(2, 5), T(2, 6), X(2, 7),

    KEY_7(3, 0), Y(3, 1), G(3, 2), KEY_8(3, 3),
    B(3, 4), H(3, 5), U(3, 6), V(3, 7),

    KEY_9(4, 0), I(4, 1), J(4, 2), KEY_0(4, 3),
    M(4, 4), K(4, 5), O(4, 6), N(4, 7),

    PLUS(5, 0), P(5, 1), L(5, 2), MINUS(5, 3),
    PERIOD(5, 4), COLON(5, 5), AT(5, 6), COMMA(5, 7),

    POUND(6, 0), ASTERISK(6, 1), SEMICOLON(6, 2), HOME(6, 3),
    RIGHT_SHIFT(6, 4), EQUALS(6, 5), ARROW_UP(6, 6), SLASH(6, 7),

    KEY_1(7, 0), ARROW_LEFT(7, 1), CONTROL(7, 2), KEY_2(7, 3),
    SPACE(7, 4), COMMODORE(7, 5), Q(7, 6), RUN_STOP(7, 7),
}

/** One of the two joystick ports. Port 2 is the one games usually want. */
enum class JoystickPort { ONE, TWO }

/**
 * The keyboard and the two joysticks, which share CIA 1's ports and therefore each other's wiring:
 * this is why a joystick in port 1 types into BASIC if you waggle it.
 */
class Keyboard : CiaPorts {

    /**
     * One bit per column, per row: set means the key is down.
     *
     * Atomic rather than a plain array for the same reason the joysticks are volatile — it is
     * written by the thread with the user's finger on it and read by the one running the processor,
     * and a plain array carries no promise that the second ever sees what the first wrote. Each
     * read is an ordinary load on every machine this runs on, and port reads happen a few thousand
     * times a second rather than a few million, so it costs nothing worth measuring.
     */
    private val matrix = java.util.concurrent.atomic.AtomicIntegerArray(8)

    /**
     * What each joystick is pulling down, as a mask over the whole port: a zero bit is a direction
     * or a button being held. Only the bottom five bits belong to the stick, and the other three
     * have to stay set — they carry keyboard lines, and a stick that quietly held them low would
     * jam a third of the keyboard down for ever.
     *
     * Volatile, and that is not a formality. These are written by whichever thread has the user's
     * finger on it and read by the one running the processor, several million times a second in a
     * loop that does nothing else to them. Without this a compiler is entitled to load them once
     * and keep them in a register for ever, and it does: the joystick moves, the field changes, and
     * the emulated machine goes on reading the value it cached before anybody touched anything. It
     * is a bug that cannot happen in a test, because a test drives both sides from one thread.
     */
    @Volatile private var joystick1 = 0xFF

    @Volatile private var joystick2 = 0xFF

    fun press(key: C64Key) {
        matrix.updateAndGet(key.row) { it or (1 shl key.column) }
    }

    fun release(key: C64Key) {
        matrix.updateAndGet(key.row) { it and (1 shl key.column).inv() }
    }

    fun releaseAll() {
        for (row in 0 until 8) matrix.set(row, 0)
    }

    fun isPressed(key: C64Key) = matrix.get(key.row) and (1 shl key.column) != 0

    /**
     * Sets a joystick direction or the fire button. The five lines are active low, which the
     * caller does not have to care about.
     */
    fun setJoystick(port: JoystickPort, up: Boolean, down: Boolean, left: Boolean, right: Boolean, fire: Boolean) {
        var bits = 0xFF
        if (up) bits = bits and 0x01.inv()
        if (down) bits = bits and 0x02.inv()
        if (left) bits = bits and 0x04.inv()
        if (right) bits = bits and 0x08.inv()
        if (fire) bits = bits and 0x10.inv()
        when (port) {
            JoystickPort.ONE -> joystick1 = bits
            JoystickPort.TWO -> joystick2 = bits
        }
    }

    /**
     * Port A carries the row drive and joystick 2. Scanning normally goes the other way — a row
     * driven low here, the columns read back on port B — but a key shorts the two together, so a
     * column held low on port B pulls every row containing a pressed key in that column low here.
     * That is how the KERNAL notices a keypress at all without scanning: it drives all of port B
     * low and watches port A.
     */
    override fun readPortA(cia: Cia): Int {
        var value = cia.portA and joystick2
        val columns = cia.portB
        for (row in 0 until 8) {
            // Any key in this row whose column is being held low shorts this row's line down.
            if (matrix.get(row) and columns.inv() and 0xFF != 0) value = value and (1 shl row).inv()
        }
        return value and 0xFF
    }

    /** Port B carries the column sense and joystick 1: the normal direction of a scan. */
    override fun readPortB(cia: Cia): Int {
        var value = cia.portB and joystick1
        val rows = cia.portA
        for (row in 0 until 8) {
            if (rows and (1 shl row) == 0) value = value and matrix.get(row).inv()
        }
        return value and 0xFF
    }
}
