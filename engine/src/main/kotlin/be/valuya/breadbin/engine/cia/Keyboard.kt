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

    /** One bit per column, per row: set means the key is down. */
    private val matrix = IntArray(8)

    private var joystick1 = 0x1F
    private var joystick2 = 0x1F

    fun press(key: C64Key) {
        matrix[key.row] = matrix[key.row] or (1 shl key.column)
    }

    fun release(key: C64Key) {
        matrix[key.row] = matrix[key.row] and (1 shl key.column).inv()
    }

    fun releaseAll() {
        java.util.Arrays.fill(matrix, 0)
    }

    fun isPressed(key: C64Key) = matrix[key.row] and (1 shl key.column) != 0

    /**
     * Sets a joystick direction or the fire button. The five lines are active low, which the
     * caller does not have to care about.
     */
    fun setJoystick(port: JoystickPort, up: Boolean, down: Boolean, left: Boolean, right: Boolean, fire: Boolean) {
        var bits = 0x1F
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
     * Port A carries the column drive and joystick 2. A key pulls its column low whenever its row
     * is being driven low on port B, which is how a program can scan the matrix the other way up.
     */
    override fun readPortA(cia: Cia): Int {
        var value = cia.portA and joystick2
        val rows = cia.portB
        for (row in 0 until 8) {
            if (rows and (1 shl row) == 0) value = value and matrix[row].inv()
        }
        return value and 0xFF
    }

    /** Port B carries the row sense and joystick 1. */
    override fun readPortB(cia: Cia): Int {
        var value = cia.portB and joystick1
        val columns = cia.portA
        for (row in 0 until 8) {
            if (matrix[row] and columns.inv() and 0xFF != 0) value = value and (1 shl row).inv()
        }
        return value and 0xFF
    }
}
