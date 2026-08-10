package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.cart.Cartridge
import be.valuya.breadbin.engine.cia.Cia
import be.valuya.breadbin.engine.cia.CiaPorts
import be.valuya.breadbin.engine.cia.Keyboard
import be.valuya.breadbin.engine.cpu.Bus
import be.valuya.breadbin.engine.cpu.Cpu6510
import be.valuya.breadbin.engine.disk.D64
import be.valuya.breadbin.engine.disk.Iec
import be.valuya.breadbin.engine.disk.IecWire
import be.valuya.breadbin.engine.drive.Drive1541
import be.valuya.breadbin.engine.drive.IecBus
import be.valuya.breadbin.engine.mem.Memory
import be.valuya.breadbin.engine.mem.Roms
import be.valuya.breadbin.engine.sid.Sid
import be.valuya.breadbin.engine.tape.Datasette
import be.valuya.breadbin.engine.tape.Program
import be.valuya.breadbin.engine.tape.TapImage
import be.valuya.breadbin.engine.vic.VicII
import be.valuya.breadbin.engine.vic.VideoModel

/**
 * A Commodore 64: the processor, the two custom chips, the two CIAs, the memory and everything
 * plugged into it.
 *
 * The machine is driven a frame at a time by [runFrame]. Inside a frame the processor drives the
 * clock: every bus access it makes advances the video chip, the CIAs, the sound chip and the tape
 * by exactly one cycle, so nothing can drift out of step with anything else.
 */
class Machine(
    private val roms: Roms,
    val model: VideoModel = VideoModel.PAL,
    sampleRate: Int = 48_000,
    /**
     * The 1541's own DOS ROM. Supplying it puts a whole second computer on the end of the serial
     * bus, running that ROM against a disk modelled as magnetic flux, which is the only thing a
     * fast loader can talk to. Leaving it out still gives a drive — [IecWire] answers the same
     * protocol on the same wires — it is just one with no processor to upload code into.
     */
    driveRom: IntArray? = null,
) {
    val memory = Memory(roms)
    val vic = VicII(memory, model)
    val sid = Sid(model.clockHz, sampleRate)
    val keyboard = Keyboard()
    val datasette = Datasette()
    val iec = Iec()

    private var cia1Interrupt = false
    private var cia2Interrupt = false
    private var restoreHeld = false

    val cia1 = Cia(keyboard, model.clockHz) { asserted -> cia1Interrupt = asserted }

    /** The three wires to the drive. */
    val serialBus = IecBus()

    /** The real drive, when one is fitted. */
    val drive: Drive1541? = driveRom?.let { Drive1541(it, serialBus) }

    /**
     * The drive that is always there. With no 1541 DOS to run, this answers on the three wires
     * itself — which is what makes a disk load under a KERNAL that talks to the bus directly rather
     * than through the routines [iec] patches, the free replacement ROMs included.
     */
    val wire: IecWire? = if (drive == null) IecWire(iec, serialBus) else null

    private val serialPorts = object : CiaPorts {
        override fun readPortA(cia: Cia): Int {
            // The outputs are inverted on their way to the bus but the inputs are not: bits 6 and
            // 7 read the two lines as they stand, so a line nobody is pulling down reads as a one.
            var value = cia.portA and 0x3F
            if (serialBus.clock == SERIAL_INPUTS_INVERTED) value = value or 0x40
            if (serialBus.data == SERIAL_INPUTS_INVERTED) value = value or 0x80
            return value
        }

        override fun readPortB(cia: Cia) = cia.portB

        override fun writePortA(value: Int) {
            updateSerialLines()
        }
    }

    val cia2 = Cia(serialPorts, model.clockHz) { asserted -> cia2Interrupt = asserted }

    /**
     * Puts the computer's three serial outputs onto the bus.
     *
     * A pin only pulls a line down when it is both configured as an output and driving the level
     * that the port's inverters turn into a pull — a pin left as an input is not holding anything,
     * whatever the latch behind it happens to contain.
     */
    private fun updateSerialLines() {
        fun pulls(bit: Int) =
            cia2.directionA and bit != 0 && (cia2.dataA and bit != 0) == SERIAL_OUTPUTS_INVERTED
        serialBus.computerAtn = pulls(0x08)
        serialBus.computerClock = pulls(0x10)
        serialBus.computerData = pulls(0x20)
        drive?.onAtnChanged()
    }

    private val bus = object : Bus {
        override fun read(address: Int): Int {
            tick()
            return memory.read(address)
        }

        override fun write(address: Int, value: Int) {
            tick()
            memory.write(address, value)
        }

    }

    val cpu = Cpu6510(bus)

    /** Cycles since the machine was switched on. */
    var cycles = 0L
        private set

    private var frameComplete = false
    private val keystrokes = ArrayDeque<Int>()
    private var framesUntilKeystrokes = 0
    private var framesSinceReset = 0
    private var pendingProgram: Program? = null
    private var pendingRun = true
    private var driveClockRemainder = 0

    /**
     * True when there is a drive on the bus at all, which there now always is: either a real 1541
     * running its own DOS, or the one that answers on the wires itself.
     */
    val driveAvailable get() = drive != null || wire != null

    /**
     * Where a program jumped into the KERNAL past its front door, or null if none has.
     *
     * The KERNAL publishes a jump table at $FF81 and everything above it is private: addresses that
     * were never promised to stay where they are. A great deal of software from the period ignores
     * that and calls straight into the middle anyway — it was the fastest way to do a thing, and
     * there was only ever one KERNAL to be compatible with.
     *
     * A replacement KERNAL implements the table, because that is the published interface, and has
     * its own code everywhere else. So a game doing this runs perfectly on Commodore's ROM and
     * behaves unpredictably on any other — usually a hang, sometimes a jam, sometimes a screen of
     * nonsense. From the outside all three look like a broken emulator, and none of them is.
     *
     * Recording it makes that diagnosable: the machine can say what the program did, and the app
     * can suggest the one thing that fixes it. It is a note rather than an error — the machine
     * carries on and the jump may well have been harmless.
     */
    var kernalInternalJump: Int? = null
        private set

    /**
     * Whether a jump was into the KERNAL's private half.
     *
     * Deliberately narrow, because a false alarm here tells somebody to go and find ROMs they did
     * not need. It has to be the KERNAL actually banked in, the program has to have jumped from
     * outside ROM — the KERNAL calls itself constantly and that is its own business — and the
     * target has to miss the jump table, whose entries are the supported way in and are three bytes
     * apart because each one is a JMP.
     */
    private fun noteJump(from: Int, to: Int) {
        if (kernalInternalJump != null) return
        if (to < KERNAL_START || !memory.kernalIsVisible) return
        if (from >= KERNAL_START) return
        if (from in BASIC_START until BASIC_END && memory.basicIsVisible) return
        if (to in JUMP_TABLE_FIRST..JUMP_TABLE_LAST && (to - JUMP_TABLE_FIRST) % 3 == 0) return
        // The hardware vectors are read by the processor, never jumped to, so anything landing up
        // there is a program that has lost its way rather than one taking a shortcut.
        kernalInternalJump = to
    }

    init {
        cpu.jumpWatcher = ::noteJump
        memory.vic = vic
        memory.sid = sid
        memory.cia1 = cia1
        memory.cia2 = cia2
        memory.onPortWrite = { data, direction ->
            // Bit 5 of the processor port runs the tape motor, and it runs it when the bit is low.
            val driven = direction and 0x20 != 0
            datasette.motorOn = !driven || data and 0x20 == 0
        }
        datasette.onPulse = {
            // Each pulse is a falling edge on CIA 1's FLAG line, and every tape loader ever
            // written is really a program for measuring the gaps between them.
            cia1.setFlag(false)
            cia1.setFlag(true)
        }
        iec.onDiskChanged = { device, disk -> onDiskChanged?.invoke(device, disk) }
        vic.onFrameComplete = { frameComplete = true }
        reset()
    }

    /** Raised when a mounted disk image has been written to and should be saved back. */
    var onDiskChanged: ((device: Int, disk: D64) -> Unit)? = null

    fun reset() {
        memory.reset()
        vic.reset()
        sid.reset()
        cia1.reset()
        cia2.reset()
        lastSerialActivity = -SERIAL_QUIET
        lastSerialLevels = -1
        iec.reset()
        datasette.reset()
        memory.cartridge?.reset()
        serialBus.reset()
        drive?.reset()
        wire?.reset()
        keystrokes.clear()
        framesSinceReset = 0
        framesUntilKeystrokes = 0
        kernalInternalJump = null
        cpu.reset()
    }

    /** True once the machine has had time to get to its READY prompt. */
    val booted get() = framesSinceReset >= BOOT_FRAMES

    /** Runs until the video chip finishes a frame. */
    fun runFrame() {
        framesSinceReset++
        if (framesSinceReset == BOOT_FRAMES) {
            pendingProgram?.let { if (pendingRun) autostart(it) else inject(it) }
            pendingProgram = null
        }
        deliverKeystrokes()
        frameComplete = false
        var guard = model.cyclesPerFrame * 4
        while (!frameComplete && guard-- > 0) {
            cpu.step()
        }
    }

    private fun tick() {
        do {
            clockOnce()
        } while (vic.cpuStalled)
    }

    /**
     * Whether the drive is in the middle of something.
     *
     * A transfer is a line changing, over and over; an idle bus is a line sitting still, at
     * whichever level the two ends happened to leave it. So this counts edges rather than levels,
     * and stays true for a moment after the last one, because a transfer has gaps in it and a flag
     * that dropped in every gap would be no use to anybody.
     *
     * The app watches this so that a load — which happens at the speed a real drive managed, and so
     * takes the best part of a minute for a game — does not have to be sat through in real time.
     */
    val driveBusy get() = cycles - lastSerialActivity < SERIAL_QUIET

    private var lastSerialActivity = -SERIAL_QUIET
    private var lastSerialLevels = -1

    private fun clockOnce() {
        vic.cycle()
        cia1.cycle()
        cia2.cycle()
        sid.clock()
        datasette.cycle()
        runDrive()
        wire?.cycle()
        val levels = (if (serialBus.atn) 1 else 0) or
            (if (serialBus.clock) 2 else 0) or
            (if (serialBus.data) 4 else 0)
        if (levels != lastSerialLevels) {
            lastSerialLevels = levels
            lastSerialActivity = cycles
        }
        cycles++
        cpu.irqLine = vic.irq || cia1Interrupt
        cpu.setNmiLine(cia2Interrupt || restoreHeld)
    }

    /**
     * The drive's crystal is not the computer's: it runs a little over one and a half per cent
     * faster than a PAL C64. Fast loaders time themselves against that difference, so it is kept
     * rather than rounded away, and the drive is stepped every cycle rather than in batches — a
     * loader synchronising on a line transition would not survive being told about it late.
     */
    private fun runDrive() {
        val current = drive ?: return
        driveClockRemainder += Drive1541.CLOCK_HZ
        while (driveClockRemainder >= model.clockHz) {
            driveClockRemainder -= model.clockHz
            current.advance(1)
        }
    }

    // ---- what is plugged in --------------------------------------------------------------------

    fun insertDisk(disk: D64?, device: Int = 8) {
        val real = drive
        if (real != null && device == real.deviceNumber) {
            real.insert(disk)
            return
        }
        iec.mount(device, disk)
        wire?.reset()
    }

    /** Writes anything the real drive has changed back into its image. */
    fun flushDrive() {
        val real = drive ?: return
        real.flush()
        // The image is the drive's own, not the virtual drive's: a real drive is mounted straight
        // into the mechanism and never goes through the IEC layer at all.
        val disk = real.mountedDisk
        if (disk != null && disk.dirty) {
            onDiskChanged?.invoke(real.deviceNumber, disk)
            disk.markClean()
        }
    }

    fun insertTape(tape: TapImage?) {
        if (tape == null) datasette.eject() else datasette.load(tape)
        memory.cassetteSense = !datasette.playing
    }

    fun pressPlay() {
        datasette.play()
        memory.cassetteSense = false
    }

    fun pressStop() {
        datasette.stop()
        memory.cassetteSense = true
    }

    fun rewindTape() = datasette.rewind()

    /**
     * Plugs a cartridge in. A cartridge changes what the processor sees at reset, so the machine is
     * restarted: that is also what happens if you plug one into a real C64 while it is running,
     * only less reliably.
     */
    fun insertCartridge(cartridge: Cartridge?) {
        memory.cartridge?.onLinesChanged = null
        cartridge?.onLinesChanged = { memory.refreshBanking() }
        memory.cartridge = cartridge
        reset()
    }

    /** The RESTORE key, which is wired straight to the processor's NMI. */
    fun restore(pressed: Boolean) {
        restoreHeld = pressed
    }

    // ---- getting a program in ------------------------------------------------------------------

    /**
     * Writes a program into memory and, for a BASIC one, fixes up the pointers so that RUN and LIST
     * find it. This is the shortcut every emulator offers: no tape, no drive, the bytes simply
     * appear where a loader would have put them.
     */
    fun inject(program: Program) {
        for (i in program.data.indices) {
            memory.poke(program.loadAddress + i, program.data[i])
        }
        if (!program.isBasic) return
        val end = program.endAddress
        // Variables start where the program ends; leaving these pointing at the empty program is
        // what makes an injected listing vanish on the first assignment.
        memory.poke(0x2D, end and 0xFF)
        memory.poke(0x2E, (end shr 8) and 0xFF)
        memory.poke(0x2F, end and 0xFF)
        memory.poke(0x30, (end shr 8) and 0xFF)
        memory.poke(0x31, end and 0xFF)
        memory.poke(0x32, (end shr 8) and 0xFF)
    }

    /**
     * Injects a program and starts it: RUN for BASIC, and a SYS to the load address for anything
     * else, which is the convention almost every machine-code release follows.
     */
    fun autostart(program: Program) {
        inject(program)
        type(if (program.isBasic) "RUN\r" else "SYS ${program.loadAddress}\r")
    }

    /**
     * Queues a program to be injected once the machine has finished starting up. Injecting one
     * before that is pointless: the KERNAL's memory test writes over everything on its way to the
     * READY prompt.
     *
     * With [run] false the program is put in memory and left there, so that it can be listed or
     * started by hand — which is what somebody who turned the automatic start off asked for.
     */
    fun enqueue(program: Program, run: Boolean = true) {
        if (booted) {
            if (run) autostart(program) else inject(program)
            return
        }
        pendingProgram = program
        pendingRun = run
    }

    /** Loads the first program from the disk in drive 8 the way a person would. */
    fun autostartDisk() {
        type("LOAD\"*\",8,1\r")
        // The second command has to wait for the load to finish, so it goes in after a pause; the
        // keyboard buffer is only pushed when the machine has emptied it.
        type("RUN\r")
    }

    /**
     * Puts text into the KERNAL's keyboard buffer, a few characters at a time as the machine reads
     * them. Going through the buffer rather than the key matrix means the timing does not have to
     * be guessed at, and a program that is busy simply gets the characters later.
     */
    fun type(text: String) {
        for (character in text) {
            keystrokes += when (character) {
                '\r', '\n' -> 0x0D
                in 'a'..'z' -> character.uppercaseChar().code
                else -> character.code
            }
        }
    }

    fun clearTypeAhead() = keystrokes.clear()

    private fun deliverKeystrokes() {
        if (keystrokes.isEmpty() || !booted) return
        if (framesUntilKeystrokes > 0) {
            framesUntilKeystrokes--
            return
        }
        // $c6 is how many characters are waiting; only add more once the machine has taken them.
        if (memory.peek(0xC6) != 0) return
        var count = 0
        while (count < 8 && keystrokes.isNotEmpty()) {
            memory.poke(0x0277 + count, keystrokes.removeFirst())
            count++
            if (memory.peek(0x0277 + count - 1) == 0x0D) break
        }
        memory.poke(0xC6, count)
        // A RETURN normally sets something running, so hold off before typing the next line.
        if (count > 0 && memory.peek(0x0277 + count - 1) == 0x0D) framesUntilKeystrokes = KEYSTROKE_PAUSE
    }

    private companion object {
        /**
         * Whether a one in the port's three output bits means "pull the line down".
         *
         * It does, and the two input bits beside them are *not* inverted — which is not symmetry
         * anybody would guess at. Both were settled by experiment rather than by reasoning: with
         * the outputs the wrong way round the drive never sees an attention edge at all, and with
         * the inputs the wrong way round the computer reports the drive missing however well it
         * answers. Either mistake looks like the other from the outside.
         */
        const val SERIAL_OUTPUTS_INVERTED = true
        const val SERIAL_INPUTS_INVERTED = false

        /**
         * How long the bus has to stay untouched before the drive counts as idle. A fifth of a
         * second is longer than any gap inside a transfer and shorter than anybody would notice.
         */
        const val SERIAL_QUIET = 200_000L

        /** Frames to wait after a RETURN before typing more, roughly two seconds. */
        const val KEYSTROKE_PAUSE = 100

        /**
         * How long the machine takes to get from reset to READY. Two and a half seconds is longer
         * than it needs, and being early is worse than being late: the KERNAL's memory test would
         * wipe an injected program out from under it.
         */
        const val BOOT_FRAMES = 125

        const val BASIC_START = 0xA000

        /**
         * BASIC ends at $BFFF, not at the KERNAL. What lies between is $C000..$CFFF, which is
         * plain RAM with nothing over it and so exactly where a machine-code program puts itself —
         * treating it as ROM meant the one place this most needed to watch was the one place it
         * ignored.
         */
        const val BASIC_END = 0xC000
        const val KERNAL_START = 0xE000

        /**
         * The KERNAL's published entry points: sixty-odd JMPs, three bytes each, from OPEN at
         * $FF81 to the last of them. Everything above is Commodore's own business and everything
         * below is the routines themselves.
         */
        const val JUMP_TABLE_FIRST = 0xFF81
        const val JUMP_TABLE_LAST = 0xFFF3
    }
}
