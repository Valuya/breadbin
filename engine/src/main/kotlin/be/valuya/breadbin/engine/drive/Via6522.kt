package be.valuya.breadbin.engine.drive

/**
 * A MOS 6522 Versatile Interface Adapter, of which a 1541 has two.
 *
 * The 6522 is a cousin of the 6526 in the C64 but not a close one: the timers behave differently,
 * the handshaking lines are real, and the drive's DOS leans on all of it. VIA 1 talks to the serial
 * bus, VIA 2 runs the disk — the motor, the stepper and the read head — so between them they are
 * the whole drive apart from the processor.
 */
class Via6522(private val ports: ViaPorts) {

    private var outputA = 0
    private var outputB = 0
    private var directionA = 0
    private var directionB = 0

    private var timer1 = 0
    private var timer1Latch = 0
    private var timer2 = 0
    private var timer2Latch = 0
    private var timer1Reload = false

    /**
     * Whether each timer has already announced its underflow. A 6522 timer does not stop when it
     * reaches zero — it keeps counting down through $FFFF — but in one-shot mode it only raises its
     * flag once, and not again until the high byte is written. A timer that raises it on every pass
     * turns a timeout the DOS is waiting on into one that has always already expired.
     */
    private var timer1Fired = false
    private var timer2Fired = false

    private var shift = 0
    private var auxiliary = 0
    private var peripheral = 0
    private var interruptFlags = 0
    private var interruptEnable = 0

    /** Level on CA1, whose edge is what most of the drive's interrupts are made of. */
    private var ca1 = false
    private var cb1 = false

    /** True while the chip is asserting its interrupt output. */
    val irq get() = interruptFlags and interruptEnable and 0x7F != 0

    /** What has happened and what the processor asked to be told about — for diagnosis. */
    val flags get() = interruptFlags
    val enabled get() = interruptEnable

    fun reset() {
        outputA = 0
        outputB = 0
        directionA = 0
        directionB = 0
        timer1 = 0
        timer1Latch = 0
        timer2 = 0
        timer2Latch = 0
        auxiliary = 0
        peripheral = 0
        interruptFlags = 0
        interruptEnable = 0
        ca1 = false
        cb1 = false
    }

    /** The level the port is driving, with undriven bits floating high. */
    val portA: Int get() = (outputA or directionA.inv()) and 0xFF
    val portB: Int get() = (outputB or directionB.inv()) and 0xFF

    fun read(register: Int): Int = when (register and 0x0F) {
        0x0 -> {
            clearInterrupt(0x18) // reading port B clears the CB1 and CB2 flags
            (ports.readPortB(this) and directionB.inv()) or (outputB and directionB)
        }
        0x1 -> {
            clearInterrupt(0x03) // and port A clears CA1 and CA2
            (ports.readPortA(this) and directionA.inv()) or (outputA and directionA)
        }
        0x2 -> directionB
        0x3 -> directionA
        0x4 -> {
            clearInterrupt(0x40) // reading the low byte acknowledges timer 1
            timer1 and 0xFF
        }
        0x5 -> (timer1 shr 8) and 0xFF
        0x6 -> timer1Latch and 0xFF
        0x7 -> (timer1Latch shr 8) and 0xFF
        0x8 -> {
            clearInterrupt(0x20)
            timer2 and 0xFF
        }
        0x9 -> (timer2 shr 8) and 0xFF
        0xA -> {
            clearInterrupt(0x04)
            shift
        }
        0xB -> auxiliary
        0xC -> peripheral
        0xD -> interruptFlags or (if (irq) 0x80 else 0)
        0xE -> interruptEnable or 0x80
        else -> (ports.readPortA(this) and directionA.inv()) or (outputA and directionA)
    }

    fun write(register: Int, value: Int) {
        val v = value and 0xFF
        when (register and 0x0F) {
            0x0 -> {
                clearInterrupt(0x18)
                outputB = v
                ports.writePortB(portB)
            }
            0x1 -> {
                clearInterrupt(0x03)
                outputA = v
                ports.writePortA(portA)
            }
            0x2 -> {
                directionB = v
                ports.writePortB(portB)
            }
            0x3 -> {
                directionA = v
                ports.writePortA(portA)
            }
            0x4 -> timer1Latch = (timer1Latch and 0xFF00) or v
            0x5 -> {
                timer1Latch = (timer1Latch and 0x00FF) or (v shl 8)
                timer1 = timer1Latch
                timer1Reload = true
                timer1Fired = false
                clearInterrupt(0x40)
            }
            0x6 -> timer1Latch = (timer1Latch and 0xFF00) or v
            0x7 -> {
                timer1Latch = (timer1Latch and 0x00FF) or (v shl 8)
                clearInterrupt(0x40)
            }
            0x8 -> timer2Latch = (timer2Latch and 0xFF00) or v
            0x9 -> {
                timer2 = (timer2Latch and 0x00FF) or (v shl 8)
                timer2Fired = false
                clearInterrupt(0x20)
            }
            0xA -> {
                clearInterrupt(0x04)
                shift = v
            }
            0xB -> auxiliary = v
            0xC -> peripheral = v
            0xD -> {
                interruptFlags = interruptFlags and v.inv() and 0x7F
            }
            0xE -> {
                interruptEnable = if (v and 0x80 != 0) {
                    interruptEnable or (v and 0x7F)
                } else {
                    interruptEnable and (v and 0x7F).inv()
                }
            }
            0xF -> {
                outputA = v
                ports.writePortA(portA)
            }
        }
    }

    /** One drive cycle. */
    fun cycle() {
        // Timer 1 free-running reloads itself from the latch and flags every time round; in
        // one-shot it flags once and then just keeps counting.
        if (timer1 == 0) {
            if (auxiliary and 0x40 != 0) {
                timer1 = timer1Latch
                if (timer1Reload) raiseInterrupt(0x40)
            } else {
                timer1 = 0xFFFF
                if (timer1Reload && !timer1Fired) {
                    timer1Fired = true
                    raiseInterrupt(0x40)
                }
            }
        } else {
            timer1--
        }

        // Timer 2 in the drive only ever counts cycles, and only ever flags once per load.
        if (auxiliary and 0x20 == 0) {
            if (timer2 == 0) {
                timer2 = 0xFFFF
                if (!timer2Fired) {
                    timer2Fired = true
                    raiseInterrupt(0x20)
                }
            } else {
                timer2--
            }
        }
    }

    /**
     * Drives CA1. The drive's byte-ready line lives here on VIA 2, and the serial bus's ATN line on
     * VIA 1; both are edge triggered, and which edge is chosen by the peripheral control register.
     */
    fun setCa1(level: Boolean) {
        val rising = peripheral and 0x01 != 0
        if (level != ca1 && level == rising) raiseInterrupt(0x02)
        ca1 = level
    }

    fun setCb1(level: Boolean) {
        val rising = peripheral and 0x10 != 0
        if (level != cb1 && level == rising) raiseInterrupt(0x10)
        cb1 = level
    }

    /**
     * Whether CB2 is being driven low. VIA 2 uses it to choose between reading and writing the
     * disk, so getting this wrong is not a subtle bug: the drive erases the track it meant to read.
     *
     * CB2's mode is the top three bits of the peripheral control register, not one of them. Only
     * 1xx is an output at all, and of those only 110 drives it low — which means a register of zero,
     * the state after a reset, leaves CB2 an input and the head reading.
     */
    val cb2DrivenLow: Boolean get() = (peripheral and 0xE0) == 0xC0

    /**
     * Whether CA2 is being driven high. On VIA 2 that is the byte-ready-enable line, which gates
     * the disk's byte-ready signal into the processor's set-overflow pin: with it low, a byte
     * arriving under the head must not touch the V flag, because the DOS is using V for something
     * else at the time.
     */
    val ca2DrivenHigh: Boolean get() = (peripheral and 0x0E) == 0x0E

    private fun raiseInterrupt(bit: Int) {
        interruptFlags = interruptFlags or bit
    }

    private fun clearInterrupt(bits: Int) {
        interruptFlags = interruptFlags and bits.inv()
    }
}

/** What is wired to a VIA's ports; the chip cannot know. */
interface ViaPorts {
    fun readPortA(via: Via6522): Int = 0xFF
    fun readPortB(via: Via6522): Int = 0xFF
    fun writePortA(value: Int) {}
    fun writePortB(value: Int) {}
}
