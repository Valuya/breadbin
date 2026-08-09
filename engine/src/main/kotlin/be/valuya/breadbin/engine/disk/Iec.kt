package be.valuya.breadbin.engine.disk

import be.valuya.breadbin.engine.cpu.Cpu6510
import be.valuya.breadbin.engine.mem.Memory
import be.valuya.breadbin.engine.mem.Roms

/**
 * A 1541 that is not there.
 *
 * Rather than emulating the drive's own 6502 and its DOS ROM, this intercepts the KERNAL's serial
 * bus routines — TALK, LISTEN, ACPTR, CIOUT and the rest — and answers them from a .d64 image. The
 * addresses of those routines are read out of the KERNAL's jump table at $FF81, so this works with
 * a patched or replacement KERNAL as long as the jump table is where it has always been, and it
 * quietly does nothing if the table does not look right.
 *
 * What this buys is speed and simplicity: loading is instant and no drive ROM is needed. What it
 * costs is fast loaders. A game that bit-bangs the serial lines itself — most disk releases from
 * about 1986 onwards do, and every cracked intro does — is talking to a drive that is not
 * listening, and will sit there. Those want a real 1541, which this is not.
 */
class Iec(private val memory: Memory) {

    /** Devices 8 to 11. */
    private val drives = arrayOfNulls<Drive>(4)

    /** Set when a mounted image has been written to and the app should save it back. */
    var onDiskChanged: ((device: Int, disk: D64) -> Unit)? = null

    private var listener = -1
    private var talker = -1
    private var current: Channel? = null

    /**
     * How many times the patched routines have been reached. A KERNAL that drives the serial lines
     * itself rather than through its own routines — Open ROMs and every fast loader do — never
     * reaches them, and this staying at zero is how that is told apart from a disk with nothing on
     * it.
     */
    var trapCount = 0
        private set

    /**
     * How many bytes have been handed to the machine through the patched routines. A KERNAL that
     * reaches LISTEN and then drives the lines itself never gets past zero here, which is how the
     * app can tell "this KERNAL cannot use the drive" from "that disk has nothing on it".
     */
    var bytesServed = 0L
        private set

    fun mount(device: Int, disk: D64?) {
        if (device !in 8..11) return
        drives[device - 8] = disk?.let { Drive(it) }
    }

    fun disk(device: Int): D64? = drives.getOrNull(device - 8)?.disk

    fun reset() {
        listener = -1
        talker = -1
        current = null
        trapCount = 0
        bytesServed = 0
        for (drive in drives) drive?.reset()
    }

    /**
     * Puts a trap opcode at the start of each serial routine. Returns false when the KERNAL's jump
     * table is not what it should be, in which case nothing has been patched.
     */
    fun install(roms: Roms): Boolean {
        val targets = mutableMapOf<Int, Routine>()
        for ((vector, routine) in VECTORS) {
            val at = vector - 0xE000
            if (roms.kernal[at] != 0x4C) return false // not a JMP, so not the table we expect
            val target = roms.kernal[at + 1] or (roms.kernal[at + 2] shl 8)
            if (target < 0xE000) return false // the routine is not in the KERNAL at all
            targets[target] = routine
        }
        for ((target, routine) in targets) {
            roms.kernal[target - 0xE000] = TRAP_OPCODE
            routines[target] = routine
        }
        return true
    }

    private val routines = mutableMapOf<Int, Routine>()

    /** Called by the CPU when it hits one of the patched routines. */
    fun onTrap(cpu: Cpu6510, address: Int): Boolean {
        val routine = routines[address] ?: return false
        trapCount++
        when (routine) {
            Routine.LISTEN -> {
                listener = cpu.a and 0x1F
                if (!present(listener)) fail()
            }
            Routine.TALK -> {
                talker = cpu.a and 0x1F
                if (!present(talker)) fail()
            }
            Routine.SECOND -> secondary(listener, cpu.a, forWriting = true)
            Routine.TKSA -> secondary(talker, cpu.a, forWriting = false)
            Routine.CIOUT -> current?.write(cpu.a and 0xFF) ?: fail()
            Routine.ACPTR -> cpu.a = accept()
            Routine.UNLSN -> {
                current?.close()
                current = null
                listener = -1
            }
            Routine.UNTLK -> {
                current = null
                talker = -1
            }
        }
        cpu.returnFromSubroutine()
        return true
    }

    private fun present(device: Int) = device in 8..11 && drives[device - 8] != null

    /** Tells the KERNAL nothing answered, which becomes DEVICE NOT PRESENT. */
    private fun fail() {
        status = status or 0x80
        current = null
    }

    private var status: Int
        get() = memory.peek(0x90)
        set(value) = memory.poke(0x90, value)

    private fun secondary(device: Int, value: Int, forWriting: Boolean) {
        if (!present(device)) {
            fail()
            return
        }
        status = 0
        val drive = drives[device - 8]!!
        current = drive.channel(device, value and 0xFF, forWriting)
    }

    private fun accept(): Int {
        val channel = current
        if (channel == null) {
            status = status or 0x02 // a read that timed out, which is how a missing file reads
            return 0
        }
        val byte = channel.read()
        if (byte < 0) {
            status = status or 0x02
            return 0
        }
        bytesServed++
        if (channel.atEnd()) status = status or 0x40 // EOI, the last byte of the file
        return byte
    }

    private enum class Routine { LISTEN, TALK, SECOND, TKSA, CIOUT, ACPTR, UNLSN, UNTLK }

    /**
     * One drive, and the sixteen channels a program can have open on it at once.
     */
    private inner class Drive(val disk: D64) {
        private val channels = arrayOfNulls<Channel>(16)
        private var lastError = DosError.OK
        private var scratched = 0

        fun reset() {
            java.util.Arrays.fill(channels, null)
            lastError = DosError.OK
        }

        fun channel(device: Int, secondary: Int, forWriting: Boolean): Channel {
            val number = secondary and 0x0F
            return when (secondary and 0xF0) {
                0xF0 -> Channel(this, device, number, ChannelMode.OPENING).also { channels[number] = it }
                0xE0 -> {
                    channels[number]?.commit()
                    channels[number] = null
                    Channel(this, device, number, ChannelMode.CLOSED)
                }
                else -> channels[number] ?: openImplicit(device, number, forWriting)
            }
        }

        private fun openImplicit(device: Int, number: Int, forWriting: Boolean): Channel {
            // A data transfer on a channel nobody opened: the command channel is the only one that
            // makes sense, and it is the one the KERNAL uses to read the drive status.
            val channel = Channel(this, device, number, if (forWriting) ChannelMode.WRITE else ChannelMode.READ)
            if (number == 15) channel.loadStatus(lastError, scratched)
            channels[number] = channel
            return channel
        }

        fun statusMessage(): IntArray = Petscii.fromAscii(
            "%02d,%s,%02d,%02d\r".format(lastError.code, lastError.message, scratched, 0)
        )

        fun setError(error: DosError, scratchedCount: Int = 0) {
            lastError = error
            scratched = scratchedCount
        }

        fun clearError() {
            lastError = DosError.OK
            scratched = 0
        }

        fun changed() {
            if (disk.dirty) {
                onDiskChanged?.invoke(8 + drives.indexOfFirst { it?.disk === disk }, disk)
                disk.markClean()
            }
        }
    }

    /**
     * A channel in one of its three lives: collecting a file name after an OPEN, feeding bytes to
     * the machine, or collecting bytes from it.
     */
    private inner class Channel(
        private val drive: Drive,
        private val device: Int,
        private val number: Int,
        private var mode: ChannelMode,
    ) {
        private var buffer = IntArray(0)
        private var position = 0
        private val collected = ArrayList<Int>()
        private var name = IntArray(0)
        private var fileType = 2 // PRG unless the name says otherwise
        private var replace = false
        private var failed = false

        fun loadStatus(error: DosError, scratched: Int) {
            buffer = drive.statusMessage()
            position = 0
            mode = ChannelMode.READ
        }

        fun write(value: Int) {
            when (mode) {
                ChannelMode.OPENING -> collected += value
                ChannelMode.WRITE -> collected += value
                ChannelMode.READ -> Unit
                ChannelMode.CLOSED -> Unit
            }
        }

        fun read(): Int {
            if (mode != ChannelMode.READ) return -1
            if (position >= buffer.size) return -1
            return buffer[position++]
        }

        fun atEnd(): Boolean = mode != ChannelMode.READ || position >= buffer.size

        /** Called on UNLSN or UNTLK: an OPEN's name is complete, or another burst of data is. */
        fun close() {
            when (mode) {
                ChannelMode.OPENING -> open(collected.toIntArray())
                // Written data is committed at every UNLSN rather than waiting for a CLOSE that a
                // program is under no obligation to send. A file written in several bursts — which
                // is what PRINT# does — is rewritten whole each time.
                ChannelMode.WRITE -> commit()
                else -> Unit
            }
        }

        fun commit() {
            if (mode != ChannelMode.WRITE || failed) return
            val error = drive.disk.writeFile(name, collected.toIntArray(), fileType, replace)
            if (error != null) {
                failed = true
                drive.setError(error)
                return
            }
            replace = true
            drive.setError(DosError.OK)
            drive.changed()
        }

        private fun open(raw: IntArray) {
            if (number == 15) {
                command(raw)
                return
            }
            var specification = raw
            // The trailing ",P,W" style suffixes say what kind of file and which direction.
            var writing = number == 1
            val parts = split(specification)
            specification = parts.firstOrNull() ?: IntArray(0)
            for (part in parts.drop(1)) {
                when (part.firstOrNull()?.let { Petscii.toAscii(it).uppercaseChar() }) {
                    'W' -> writing = true
                    'R' -> writing = false
                    'A' -> writing = true
                    'P' -> fileType = 2
                    'S' -> fileType = 1
                    'U' -> fileType = 3
                    'L' -> fileType = 4
                }
            }

            if (specification.isNotEmpty() && specification[0] == '@'.code) {
                replace = true
                specification = specification.drop(1).toIntArray()
            }
            // A leading drive number ("0:NAME") means nothing to a single-drive unit.
            val colon = specification.indexOf(':'.code)
            if (colon >= 0) specification = specification.drop(colon + 1).toIntArray()

            name = specification
            if (writing) {
                mode = ChannelMode.WRITE
                collected.clear()
                if (name.isEmpty()) {
                    failed = true
                    drive.setError(DosError.SYNTAX_ERROR)
                }
                return
            }

            mode = ChannelMode.READ
            position = 0
            if (name.size == 1 && name[0] == '$'.code) {
                buffer = DirectoryListing.of(drive.disk)
                drive.clearError()
                return
            }
            val entry = drive.disk.find(name)
            if (entry == null) {
                buffer = IntArray(0)
                drive.setError(DosError.FILE_NOT_FOUND)
                return
            }
            buffer = drive.disk.readFile(entry)
            drive.clearError()
        }

        private fun command(raw: IntArray) {
            val text = Petscii.toAscii(raw).uppercase().trim()
            when {
                text.startsWith("S") && text.contains(':') -> {
                    val pattern = Petscii.fromAscii(text.substringAfter(':'))
                    val count = drive.disk.scratch(pattern)
                    drive.setError(DosError.OK, count)
                    drive.changed()
                }
                text.startsWith("I") || text.startsWith("V") -> drive.clearError()
                text.startsWith("N") -> {
                    // NEW formats the disk, which for an image means replacing it wholesale; that
                    // is destructive enough that it is refused rather than done by surprise.
                    drive.setError(DosError.WRITE_PROTECT)
                }
                text.isEmpty() -> drive.clearError()
                else -> drive.setError(DosError.SYNTAX_ERROR)
            }
            mode = ChannelMode.CLOSED
        }

        private fun split(raw: IntArray): List<IntArray> {
            val parts = mutableListOf<IntArray>()
            var start = 0
            for (i in raw.indices) {
                if (raw[i] == ','.code) {
                    parts += raw.copyOfRange(start, i)
                    start = i + 1
                }
            }
            parts += raw.copyOfRange(start, raw.size)
            return parts
        }
    }

    private companion object {
        /** An illegal opcode the processor hands back to us. */
        const val TRAP_OPCODE = 0x02

        /**
         * The KERNAL jump table entries for the serial bus. Reading the targets out of the table
         * rather than hard-coding them means a KERNAL that has been relocated internally still
         * works, and one that has been replaced wholesale is detected instead of crashed into.
         */
        val VECTORS = listOf(
            0xFF93 to Routine.SECOND,
            0xFF96 to Routine.TKSA,
            0xFFA5 to Routine.ACPTR,
            0xFFA8 to Routine.CIOUT,
            0xFFAB to Routine.UNTLK,
            0xFFAE to Routine.UNLSN,
            0xFFB1 to Routine.LISTEN,
            0xFFB4 to Routine.TALK,
        )
    }
}

/** Which of its three lives a channel is in. */
private enum class ChannelMode {
    /** Collecting the file name that follows an OPEN. */
    OPENING,
    READ,
    WRITE,
    CLOSED,
}

/**
 * The directory of a disk, as the BASIC program a C64 expects to get back from LOAD"$",8.
 *
 * There is no directory command on this machine: the drive hands over a program whose line numbers
 * happen to be block counts, and LIST prints it.
 */
object DirectoryListing {
    fun of(disk: D64): IntArray {
        val out = ArrayList<Int>()
        out += 0x01 // the load address, $0401, which LOAD"$" relocates to the start of BASIC
        out += 0x04

        val (name, id) = disk.header()
        line(out, 0) {
            it += 0x12 // reverse on, which is how the disk name is shown
            it += '"'.code
            for (i in 0 until 16) it += name.getOrElse(i) { 0xA0 }
            it += '"'.code
            it += ' '.code
            it += id.getOrElse(0) { 0x20 }
            it += id.getOrElse(1) { 0x20 }
            it += ' '.code
            it += '2'.code
            it += 'A'.code
        }

        for (entry in disk.directory()) {
            line(out, entry.blocks) {
                val digits = entry.blocks.toString().length
                repeat(maxOf(1, 4 - digits)) { _ -> it += ' '.code }
                it += '"'.code
                val stripped = entry.name
                for (code in stripped) it += code
                it += '"'.code
                repeat(16 - stripped.size) { _ -> it += ' '.code }
                it += ' '.code
                for (character in entry.typeName) it += Petscii.fromAscii(character)
                if (!entry.closed) it += '*'.code
                if (entry.locked) it += '<'.code
            }
        }

        line(out, disk.blocksFree()) {
            for (character in "BLOCKS FREE.") it += Petscii.fromAscii(character)
        }

        out += 0x00
        out += 0x00
        return out.toIntArray()
    }

    private inline fun line(out: ArrayList<Int>, number: Int, body: (ArrayList<Int>) -> Unit) {
        // The link pointer only has to be non-zero for LIST to keep going; the machine rebuilds
        // the real pointers when it relocates the program.
        out += 0x01
        out += 0x01
        out += number and 0xFF
        out += (number shr 8) and 0xFF
        body(out)
        out += 0x00
    }
}
