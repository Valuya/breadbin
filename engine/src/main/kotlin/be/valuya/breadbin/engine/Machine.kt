package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.cart.Cartridge
import be.valuya.breadbin.engine.cia.Cia
import be.valuya.breadbin.engine.cia.CiaPorts
import be.valuya.breadbin.engine.cia.Keyboard
import be.valuya.breadbin.engine.cpu.Bus
import be.valuya.breadbin.engine.cpu.Cpu6510
import be.valuya.breadbin.engine.disk.D64
import be.valuya.breadbin.engine.disk.Iec
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
) {
    val memory = Memory(roms)
    val vic = VicII(memory, model)
    val sid = Sid(model.clockHz, sampleRate)
    val keyboard = Keyboard()
    val datasette = Datasette()
    val iec = Iec(memory)

    private var cia1Interrupt = false
    private var cia2Interrupt = false
    private var restoreHeld = false

    val cia1 = Cia(keyboard, model.clockHz) { asserted -> cia1Interrupt = asserted }

    private val serialPorts = object : CiaPorts {
        // Nothing is wired to the serial bus or the user port: the drive is emulated above the
        // KERNAL rather than below it, so these lines only need to read back sanely.
        override fun readPortA(cia: Cia) = cia.portA
        override fun readPortB(cia: Cia) = cia.portB
    }

    val cia2 = Cia(serialPorts, model.clockHz) { asserted -> cia2Interrupt = asserted }

    private val bus = object : Bus {
        override fun read(address: Int): Int {
            tick()
            return memory.read(address)
        }

        override fun write(address: Int, value: Int) {
            tick()
            memory.write(address, value)
        }

        override fun trap(cpu: Cpu6510, address: Int) = iec.onTrap(cpu, address)
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

    /** True when the virtual drive could be patched into this KERNAL. */
    var virtualDriveAvailable = false
        private set

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
        virtualDriveAvailable = iec.install(roms)
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
            pendingProgram?.let { autostart(it) }
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
        cycles++
        cpu.irqLine = vic.irq || cia1Interrupt
        cpu.setNmiLine(cia2Interrupt || restoreHeld)
    }

    // ---- what is plugged in --------------------------------------------------------------------

    fun insertDisk(disk: D64?, device: Int = 8) {
        iec.mount(device, disk)
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
     */
    fun enqueue(program: Program) {
        if (booted) autostart(program) else pendingProgram = program
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
