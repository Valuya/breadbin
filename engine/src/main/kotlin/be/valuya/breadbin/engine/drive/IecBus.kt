package be.valuya.breadbin.engine.drive

/**
 * The serial bus between the computer and the drive.
 *
 * Three wires — ATN, CLK and DATA — each pulled up by a resistor and pulled down by anyone who
 * wants to. Nobody ever drives a line high: a device either pulls it low or lets go, so the line is
 * low if *any* device is pulling it. That wired-AND is the whole protocol, and it is why both ends
 * can talk on the same wire without shouting over each other.
 *
 * Everything here is in terms of pulling low, because that is the only thing a device can do. A
 * line being "true" means somebody is holding it down.
 */
class IecBus {

    // What the computer is pulling down.
    var computerAtn = false
    var computerClock = false
    var computerData = false

    // What the drive is pulling down — whichever drive that is. A real 1541 and the one written
    // in Kotlin both hang off these two, and only ever one of them at a time.
    var deviceClock = false
    var deviceData = false

    val atn get() = computerAtn
    val clock get() = computerClock || deviceClock
    val data get() = computerData || deviceData

    fun reset() {
        computerAtn = false
        computerClock = false
        computerData = false
        deviceClock = false
        deviceData = false
    }
}
