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

    init {
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
        iec.reset()
        datasette.reset()
        memory.cartridge?.reset()
        serialBus.reset()
        drive?.reset()
        wire?.reset()
        keystrokes.clear()
        framesSinceReset = 0
        framesUntilKeystrokes = 0
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

    private fun clockOnce() {
        vic.cycle()
        cia1.cycle()
        cia2.cycle()
        sid.clock()
        datasette.cycle()
        runDrive()
        wire?.cycle()
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

        /** Frames to wait after a RETURN before typing more, roughly two seconds. */
        const val KEYSTROKE_PAUSE = 100

        /**
         * How long the machine takes to get from reset to READY. Two and a half seconds is longer
         * than it needs, and being early is worse than being late: the KERNAL's memory test would
         * wipe an injected program out from under it.
         */
        const val BOOT_FRAMES = 125
    }
}
