package be.valuya.breadbin.engine.cia

/**
 * A MOS 6526 Complex Interface Adapter. The C64 has two: the first drives the keyboard, the
 * joysticks and the interrupt that BASIC uses to blink the cursor, the second drives the serial
 * bus, the user port and the VIC's 16K bank — and its interrupt is wired to NMI instead of IRQ.
 *
 * Almost every game reprograms timer A of CIA 1 to get an interrupt at the rate it wants, so the
 * timers matter rather more than the ports do.
 */
class Cia(
    private val ports: CiaPorts,
    private val clockHz: Int,
    /** Raised and lowered as the chip's interrupt output changes. */
    private val onInterrupt: (Boolean) -> Unit,
) {
    private var portAData = 0
    private var portBData = 0
    private var portADirection = 0
    private var portBDirection = 0

    private var timerALatch = 0xFFFF
    private var timerBLatch = 0xFFFF
    private var timerA = 0xFFFF
    private var timerB = 0xFFFF
    private var controlA = 0
    private var controlB = 0

    private var interruptLatch = 0
    private var interruptMask = 0
    private var interruptOut = false

    private var serialData = 0

    // Time of day, kept as the four registers the chip exposes.
    private var todTenths = 0
    private var todSeconds = 0
    private var todMinutes = 0
    private var todHours = 1
    private var todLatched = false
    private var todLatchedValue = 0
    private var todHalted = true
    private var todAlarm = 0
    private var todCycleCounter = 0

    private var flagLine = true

    /** The effective level on port A: driven bits come from the latch, the rest float high. */
    val portA: Int get() = (portAData or portADirection.inv()) and 0xFF

    val portB: Int get() = (portBData or portBDirection.inv()) and 0xFF

    fun reset() {
        portAData = 0
        portBData = 0
        portADirection = 0
        portBDirection = 0
        timerALatch = 0xFFFF
        timerBLatch = 0xFFFF
        timerA = 0xFFFF
        timerB = 0xFFFF
        controlA = 0
        controlB = 0
        interruptLatch = 0
        interruptMask = 0
        setInterrupt(false)
        todHalted = true
        todTenths = 0
        todSeconds = 0
        todMinutes = 0
        todHours = 1
        todCycleCounter = 0
        flagLine = true
    }

    /**
     * The datasette and the user port pull this low; the high-to-low edge is what raises the
     * interrupt, which is exactly how tape loaders time themselves.
     */
    fun setFlag(level: Boolean) {
        if (flagLine && !level) raiseInterrupt(0x10)
        flagLine = level
    }

    fun read(register: Int): Int = when (register and 0x0F) {
        0x0 -> ports.readPortA(this)
        0x1 -> ports.readPortB(this)
        0x2 -> portADirection
        0x3 -> portBDirection
        0x4 -> timerA and 0xFF
        0x5 -> (timerA shr 8) and 0xFF
        0x6 -> timerB and 0xFF
        0x7 -> (timerB shr 8) and 0xFF
        // Reading the tenths releases a latch taken by reading the hours, so that a program that
        // reads all four registers gets one coherent time rather than four samples of a moving one.
        0x8 -> {
            val tenths = if (todLatched) todLatchedValue and 0x0F else todTenths
            todLatched = false
            tenths
        }
        0x9 -> if (todLatched) (todLatchedValue shr 8) and 0x7F else todSeconds
        0xA -> if (todLatched) (todLatchedValue shr 16) and 0x7F else todMinutes
        0xB -> {
            if (!todLatched) {
                todLatched = true
                todLatchedValue = todTenths or (todSeconds shl 8) or (todMinutes shl 16) or (todHours shl 24)
            }
            (todLatchedValue ushr 24) and 0xFF
        }
        0xC -> serialData
        0xD -> {
            val value = interruptLatch or (if (interruptOut) 0x80 else 0)
            interruptLatch = 0
            setInterrupt(false)
            value
        }
        0xE -> controlA
        0xF -> controlB
        else -> 0xFF
    }

    fun write(register: Int, value: Int) {
        val v = value and 0xFF
        when (register and 0x0F) {
            0x0 -> {
                portAData = v
                ports.writePortA(portA)
            }
            0x1 -> {
                portBData = v
                ports.writePortB(portB)
            }
            0x2 -> {
                portADirection = v
                ports.writePortA(portA)
            }
            0x3 -> {
                portBDirection = v
                ports.writePortB(portB)
            }
            0x4 -> timerALatch = (timerALatch and 0xFF00) or v
            0x5 -> {
                timerALatch = (timerALatch and 0x00FF) or (v shl 8)
                // Writing the high byte of a stopped timer loads it, which is how a program sets a
                // period without having to start and stop the timer around it.
                if (controlA and 0x01 == 0) timerA = timerALatch
            }
            0x6 -> timerBLatch = (timerBLatch and 0xFF00) or v
            0x7 -> {
                timerBLatch = (timerBLatch and 0x00FF) or (v shl 8)
                if (controlB and 0x01 == 0) timerB = timerBLatch
            }
            0x8 -> if (controlB and 0x80 != 0) {
                todAlarm = (todAlarm and 0xFFFFFF00.toInt()) or (v and 0x0F)
            } else {
                todTenths = v and 0x0F
                todHalted = false
            }
            0x9 -> if (controlB and 0x80 != 0) {
                todAlarm = (todAlarm and 0xFFFF00FF.toInt()) or ((v and 0x7F) shl 8)
            } else {
                todSeconds = v and 0x7F
            }
            0xA -> if (controlB and 0x80 != 0) {
                todAlarm = (todAlarm and 0xFF00FFFF.toInt()) or ((v and 0x7F) shl 16)
            } else {
                todMinutes = v and 0x7F
            }
            0xB -> if (controlB and 0x80 != 0) {
                todAlarm = (todAlarm and 0x00FFFFFF) or ((v and 0x9F) shl 24)
            } else {
                todHours = v and 0x9F
                // Writing the hours stops the clock until the tenths are written, so that setting
                // the time cannot be caught halfway.
                todHalted = true
            }
            0xC -> {
                serialData = v
                if (controlA and 0x40 != 0) raiseInterrupt(0x08)
            }
            0xD -> {
                interruptMask = if (v and 0x80 != 0) {
                    interruptMask or (v and 0x1F)
                } else {
                    interruptMask and (v and 0x1F).inv()
                }
                updateInterrupt()
            }
            0xE -> {
                controlA = v and 0xEF
                if (v and 0x10 != 0) timerA = timerALatch
            }
            0xF -> {
                controlB = v and 0xEF
                if (v and 0x10 != 0) timerB = timerBLatch
            }
        }
    }

    /** One system cycle. */
    fun cycle() {
        // A timer loaded with N runs for N+1 cycles: it counts down to zero and underflows on the
        // cycle after that, which is why the KERNAL's 60Hz latch is one less than the period.
        var timerAUnderflowed = false
        if (controlA and 0x01 != 0 && controlA and 0x20 == 0) {
            if (timerA == 0) {
                timerAUnderflowed = true
                timerA = timerALatch
                if (controlA and 0x08 != 0) controlA = controlA and 0x01.inv()
                raiseInterrupt(0x01)
            } else {
                timerA--
            }
        }

        if (controlB and 0x01 != 0) {
            val counts = when ((controlB shr 5) and 0x03) {
                0 -> true                  // every cycle
                2, 3 -> timerAUnderflowed  // chained to timer A
                else -> false              // driven by CNT, which nothing in a stock C64 drives
            }
            if (counts) {
                if (timerB == 0) {
                    timerB = timerBLatch
                    if (controlB and 0x08 != 0) controlB = controlB and 0x01.inv()
                    raiseInterrupt(0x02)
                } else {
                    timerB--
                }
            }
        }

        cycleTimeOfDay()
    }

    private fun cycleTimeOfDay() {
        if (todHalted) return
        // The chip counts a mains input, 50Hz or 60Hz depending on bit 7 of control A.
        val ticksPerTenth = if (controlA and 0x80 != 0) clockHz / 60 * 6 / 10 else clockHz / 50 * 5 / 10
        if (++todCycleCounter < ticksPerTenth) return
        todCycleCounter = 0

        if (++todTenths < 10) {
            checkAlarm()
            return
        }
        todTenths = 0
        var seconds = fromBcd(todSeconds) + 1
        if (seconds < 60) {
            todSeconds = toBcd(seconds)
            checkAlarm()
            return
        }
        todSeconds = 0
        var minutes = fromBcd(todMinutes) + 1
        if (minutes < 60) {
            todMinutes = toBcd(minutes)
            checkAlarm()
            return
        }
        todMinutes = 0
        val pm = todHours and 0x80
        var hours = fromBcd(todHours and 0x1F) + 1
        if (hours > 12) hours = 1
        todHours = pm or toBcd(hours)
        // Twelve o'clock flips AM and PM, and does it going into 12 rather than out of it, which
        // is a genuine quirk of the chip rather than a mistake here.
        if (hours == 12) todHours = todHours xor 0x80
        checkAlarm()
    }

    private fun checkAlarm() {
        val now = todTenths or (todSeconds shl 8) or (todMinutes shl 16) or (todHours shl 24)
        if (now == todAlarm) raiseInterrupt(0x04)
    }

    private fun fromBcd(value: Int) = (value shr 4) * 10 + (value and 0x0F)

    private fun toBcd(value: Int) = ((value / 10) shl 4) or (value % 10)

    private fun raiseInterrupt(bit: Int) {
        interruptLatch = interruptLatch or bit
        updateInterrupt()
    }

    private fun updateInterrupt() {
        setInterrupt(interruptLatch and interruptMask and 0x1F != 0)
    }

    private fun setInterrupt(asserted: Boolean) {
        if (asserted != interruptOut) {
            interruptOut = asserted
            onInterrupt(asserted)
        }
    }
}

/**
 * What is wired to a CIA's two 8-bit ports. The chip cannot know: on CIA 1 reading port B means
 * scanning the keyboard against whatever port A is driving, and on CIA 2 port A is half of the
 * serial bus.
 */
interface CiaPorts {
    fun readPortA(cia: Cia): Int
    fun readPortB(cia: Cia): Int
    fun writePortA(value: Int) {}
    fun writePortB(value: Int) {}
}
