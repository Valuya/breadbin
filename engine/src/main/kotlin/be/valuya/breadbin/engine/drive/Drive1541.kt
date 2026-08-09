package be.valuya.breadbin.engine.drive

import be.valuya.breadbin.engine.cpu.Bus
import be.valuya.breadbin.engine.cpu.Cpu6510
import be.valuya.breadbin.engine.disk.D64

/**
 * A Commodore 1541, emulated as the computer it is.
 *
 * The 1541 is not a peripheral so much as a second computer that happens to have a disk in it: its
 * own 6502, two kilobytes of RAM, two 6522 VIAs and sixteen kilobytes of DOS in ROM. The C64 talks
 * to it down three wires, slowly, and that slowness is why almost every disk game replaced the
 * drive's transfer routine with its own — uploading a few hundred bytes of code into the drive's
 * RAM and running it there.
 *
 * That is the whole reason for emulating the drive rather than faking it. A fast loader is a
 * program that runs *on this processor*, bit-banging those three wires against a matching routine
 * on the C64. Nothing above the KERNAL can serve it; only the real thing can.
 *
 * The disk is modelled as the drive sees it too — a track of GCR bits going past a head — because
 * the loader is timing that as well.
 */
class Drive1541(
    private val rom: IntArray,
    private val bus: IecBus,
    val deviceNumber: Int = 8,
) {
    init {
        require(rom.size == ROM_SIZE) { "the 1541 DOS ROM is 16384 bytes, not ${rom.size}" }
    }

    private val ram = IntArray(0x0800)

    private var disk: D64? = null
    private var writeProtected = false

    /** The image in the drive, so that whoever put it there can write it back out. */
    val mountedDisk: D64? get() = disk

    /** The track under the head, in half-tracks, so that a seek can land between two. */
    private var halfTrack = 36 // track 18, where the directory is

    private var trackData = IntArray(Gcr.trackLength(18))
    private var trackDirty = false

    // The read head.
    private var bitPosition = 0
    private var shiftRegister = 0
    private var bitsIntoByte = 0
    private var syncFound = false
    private var readLatch = 0
    private var byteReady = false
    private var bitAccumulator = 0

    private var motorOn = false
    private var density = 3
    private var stepperPhase = 0

    /**
     * Whether the stepper's phase is known yet. The DOS does not announce where the head is, it
     * just starts writing phases; taking the first one it writes as "where we already are" avoids
     * inventing a step that never happened and leaving the head half a track out for ever after.
     */
    private var stepperKnown = false

    private var budget = 0

    /** Set while the drive's LED is on, which is how a game shows it is loading. */
    var ledOn = false
        private set

    /** Whether the disk is turning, and which track the head is over. */
    val motorRunning get() = motorOn
    val track get() = halfTrack / 2

    /** Bytes that have come off the disk, which is the sign of a head that is finding data. */
    var bytesRead = 0L
        private set

    /** Sync marks the head has gone over, which is the sign of a head that is finding blocks. */
    var syncsSeen = 0L
        private set

    /**
     * The drive's own memory. The DOS keeps its job queue in the first six bytes — a code of $80 or
     * more means a job the controller has not finished, and anything less is the answer it came
     * back with — so this is how to ask the drive what it thinks it is doing.
     */
    fun peek(address: Int) = ram[address and 0x07FF]


    private val via1Ports = object : ViaPorts {
        override fun readPortB(via: Via6522): Int {
            var value = 0
            if (bus.data == INPUTS_INVERTED) value = value or 0x01
            if (bus.clock == INPUTS_INVERTED) value = value or 0x04
            if (bus.atn == INPUTS_INVERTED) value = value or 0x80
            // The two jumper bits set the device number, 8 through 11.
            value = value or (((deviceNumber - 8) and 0x03) shl 5)
            return value
        }

        override fun writePortB(value: Int) {
            updateSerialOutputs(value)
        }
    }

    private val via2Ports = object : ViaPorts {
        override fun readPortA(via: Via6522) = readLatch

        override fun readPortB(via: Via6522): Int {
            var value = 0x0F
            if (!writeProtected) value = value or 0x10
            value = value or (density shl 5)
            // SYNC is active low, and it is the only way the DOS can find the start of a block.
            if (!syncFound) value = value or 0x80
            return value
        }

        override fun writePortB(value: Int) {
            stepTo(value and 0x03)
            val running = value and 0x04 != 0
            if (running != motorOn) {
                motorOn = running
                bitAccumulator = 0
            }
            ledOn = value and 0x08 != 0
            density = (value shr 5) and 0x03
        }
    }

    val via1 = Via6522(via1Ports)
    val via2 = Via6522(via2Ports)

    private val cpuBus = object : Bus {
        override fun read(address: Int): Int {
            tick()
            return readByte(address)
        }

        override fun write(address: Int, value: Int) {
            tick()
            writeByte(address, value)
        }
    }

    val cpu = Cpu6510(cpuBus)

    fun reset() {
        java.util.Arrays.fill(ram, 0)
        via1.reset()
        via2.reset()
        motorOn = false
        ledOn = false
        budget = 0
        bitPosition = 0
        shiftRegister = 0
        bitsIntoByte = 0
        syncFound = false
        byteReady = false
        syncsSeen = 0
        bytesRead = 0
        stepperKnown = false
        bus.driveClock = false
        bus.driveData = false
        cpu.reset()
    }

    fun insert(disk: D64?, writeProtected: Boolean = false) {
        flush()
        this.disk = disk
        this.writeProtected = writeProtected
        loadTrack()
    }

    /** Writes any track the drive has changed back into the image. */
    fun flush() {
        val current = disk ?: return
        if (!trackDirty) return
        Gcr.decodeTrack(trackData, halfTrack / 2, current)
        trackDirty = false
    }

    val diskChanged get() = disk?.dirty == true

    /** Runs the drive for as many of its own cycles as have gone by on the other machine. */
    fun advance(cycles: Int) {
        budget += cycles
        var guard = cycles * 4 + 16
        while (budget > 0 && guard-- > 0) {
            if (cpu.jammed) {
                budget = 0
                return
            }
            cpu.step()
        }
        if (budget > 0) budget = 0
    }

    private fun tick() {
        budget--
        via1.cycle()
        via2.cycle()
        rotate()
        cpu.irqLine = via1.irq || via2.irq
    }

    // ---- the disk ------------------------------------------------------------------------------

    private fun loadTrack() {
        val current = disk
        val track = halfTrack / 2
        trackData = if (current == null || track < 1 || track > current.tracks) {
            // No disk, or the head is off the edge of one: unformatted surface, which reads as
            // nothing the drive can make sense of.
            IntArray(Gcr.trackLength(track.coerceIn(1, 35)))
        } else {
            val bam = current.read(D64.DIRECTORY_TRACK, 0)
            Gcr.buildTrack(current, track, bam[0xA2], bam[0xA3])
        }
        bitPosition = 0
        trackDirty = false
    }

    private fun stepTo(phase: Int) {
        if (!stepperKnown) {
            stepperKnown = true
            stepperPhase = phase
            return
        }
        if (phase == stepperPhase) return
        // The stepper turns one way or the other depending on which phase comes next; two phases
        // make a half track, four make a whole one.
        val forward = (stepperPhase + 1) and 0x03 == phase
        val backward = (stepperPhase - 1) and 0x03 == phase
        stepperPhase = phase
        if (!forward && !backward) return

        flush()
        halfTrack = (halfTrack + if (forward) 1 else -1).coerceIn(2, 84)
        loadTrack()
    }

    /** Moves the disk on by however much of a bit has gone past the head. */
    private fun rotate() {
        if (!motorOn || trackData.isEmpty()) return
        // Four quarter-cycles per cycle keeps the awkward 3.25 cycles a bit of the outer tracks
        // exact rather than approximately right.
        bitAccumulator += 4
        val quartersPerBit = Gcr.cyclesPerByte(density) / 2
        while (bitAccumulator >= quartersPerBit) {
            bitAccumulator -= quartersPerBit
            stepBit()
        }
    }

    private fun stepBit() {
        val writing = via2.cb2DrivenLow
        if (writing && !writeProtected) {
            writeBit()
            return
        }

        val byteIndex = bitPosition shr 3
        val bit = (trackData[byteIndex] shr (7 - (bitPosition and 7))) and 1
        advanceBit()

        shiftRegister = ((shiftRegister shl 1) or bit) and 0x3FF
        if (shiftRegister == 0x3FF) {
            // Ten one bits cannot happen inside data, so this is a sync mark: hold the byte
            // boundary here so that the block after it comes out aligned.
            if (!syncFound) syncsSeen++
            syncFound = true
            bitsIntoByte = 0
            return
        }
        syncFound = false
        if (++bitsIntoByte < 8) return
        bitsIntoByte = 0
        readLatch = shiftRegister and 0xFF
        signalByteReady()
    }

    private fun writeBit() {
        // Writing puts the byte the DOS left in the VIA's output latch onto the disk, a bit at a
        // time, which is how formatting and saving work.
        val byteIndex = bitPosition shr 3
        val mask = 1 shl (7 - (bitPosition and 7))
        val bit = (via2.portA shr (7 - (bitPosition and 7))) and 1
        trackData[byteIndex] = if (bit != 0) {
            trackData[byteIndex] or mask
        } else {
            trackData[byteIndex] and mask.inv()
        }
        trackDirty = true
        advanceBit()
        if (++bitsIntoByte >= 8) {
            bitsIntoByte = 0
            signalByteReady()
        }
    }

    private fun advanceBit() {
        bitPosition++
        if (bitPosition >= trackData.size * 8) bitPosition = 0
    }

    /**
     * Tells the processor a byte has arrived. The 1541 wires this to the 6502's set-overflow pin as
     * well as to the VIA, because the DOS waits for it with `BVC *` — a two-cycle loop that could
     * not be written any tighter.
     */
    private fun signalByteReady() {
        byteReady = true
        bytesRead++
        via2.setCa1(false)
        via2.setCa1(true)
        // The set-overflow pin is gated by byte-ready-enable. Setting V regardless would corrupt
        // every branch-on-overflow the DOS makes while it is doing something other than reading.
        if (via2.ca2DrivenHigh) cpu.overflow = true
    }

    // ---- the serial bus ------------------------------------------------------------------------

    private fun updateSerialOutputs(portB: Int) {
        val dataOut = (portB and 0x02 != 0) == OUTPUTS_INVERTED
        val clockOut = (portB and 0x08 != 0) == OUTPUTS_INVERTED
        val acknowledge = portB and 0x10 != 0

        bus.driveClock = clockOut
        // The drive also pulls DATA down automatically whenever the computer's attention request
        // and the drive's acknowledgement disagree. That gate is what lets the computer tell,
        // without asking, whether anything out there is listening.
        bus.driveData = dataOut || (acknowledge != bus.atn)
        // CA1 sees the ATN line itself, where port B bit 7 sees it through an inverter — so the
        // two disagree, and the DOS asks for the negative edge because that is when ATN is pulled
        // low. Making CA1 follow bit 7 looks tidier and never fires.
        via1.setCa1(!bus.atn)
    }

    /** Called by the machine when the computer changes the ATN line. */
    fun onAtnChanged() {
        updateSerialOutputs(via1.portB)
    }

    // ---- memory --------------------------------------------------------------------------------

    private fun readByte(address: Int): Int {
        val a = address and 0xFFFF
        return when {
            a < 0x1800 -> ram[a and 0x07FF]
            a < 0x1C00 -> via1.read(a and 0x0F)
            a < 0x2000 -> via2.read(a and 0x0F)
            a >= 0xC000 -> rom[a - 0xC000]
            else -> (a shr 8) and 0xFF // open bus
        }
    }

    private fun writeByte(address: Int, value: Int) {
        val a = address and 0xFFFF
        when {
            a < 0x1800 -> ram[a and 0x07FF] = value and 0xFF
            a < 0x1C00 -> via1.write(a and 0x0F, value)
            a < 0x2000 -> via2.write(a and 0x0F, value)
        }
    }

    companion object {
        private const val ROM_SIZE = 0x4000

        /**
         * Whether the drive's port reads a line held low as a one. It does — the 1541 puts its
         * side through inverters too — though unlike the computer's the experiment does not
         * distinguish the two, so this is the hardware's answer rather than a measured one.
         */
        const val INPUTS_INVERTED = true

        /**
         * Whether a one in the drive's two serial output bits pulls its line down. It does, the
         * same way the computer's do; taking them the other way round leaves the drive stuck in
         * its own start-up. Measured, like the rest of these.
         */
        const val OUTPUTS_INVERTED = true

        /** The drive's crystal divided down: a shade faster than a PAL C64. */
        const val CLOCK_HZ = 1_000_000
    }
}
