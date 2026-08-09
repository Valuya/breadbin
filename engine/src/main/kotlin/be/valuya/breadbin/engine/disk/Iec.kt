package be.valuya.breadbin.engine.disk

/**
 * A 1541 that is not there: the disk, the directory and the sixteen channels, with no processor and
 * no ROM.
 *
 * This is only half a drive. It knows what a disk contains and what to do when a channel is opened,
 * a name is sent or a byte is asked for, but it has no idea how any of that reaches the computer.
 * [IecWire] is the other half, and does the talking.
 *
 * What is missing, and cannot be added here, is a fast loader. A game that bit-bangs the serial
 * lines itself — most disk releases from about 1986 onwards do, and every cracked intro does — is
 * talking to a drive's processor, and there is no processor in here for it to talk to. Those want
 * a real 1541, which [be.valuya.breadbin.engine.drive.Drive1541] is.
 */
class Iec {

    /** Devices 8 to 11. */
    private val drives = arrayOfNulls<Drive>(4)

    /** Set when a mounted image has been written to and the app should save it back. */
    var onDiskChanged: ((device: Int, disk: D64) -> Unit)? = null

    private var listener = -1
    private var talker = -1
    private var current: Channel? = null

    /** How many bytes have been handed over, which tells serving apart from silence. */
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
        bytesServed = 0
        for (drive in drives) drive?.reset()
    }

    // ---- being addressed -----------------------------------------------------------------------

    fun present(device: Int) = device in 8..11 && drives[device - 8] != null

    /** Addresses a device to listen. False if nothing with that number is out there. */
    fun addressListener(device: Int): Boolean {
        listener = device
        talker = -1
        return present(device)
    }

    fun addressTalker(device: Int): Boolean {
        talker = device
        listener = -1
        return present(device)
    }

    /** Opens the channel a secondary address names, on whichever device was just addressed. */
    fun openChannel(secondary: Int, forWriting: Boolean): Boolean {
        val device = if (forWriting) listener else talker
        if (!present(device)) {
            current = null
            return false
        }
        current = drives[device - 8]!!.channel(device, secondary and 0xFF, forWriting)
        return true
    }

    /** Hands a byte to the open channel. False if there is no channel to hand it to. */
    fun sendToChannel(value: Int): Boolean {
        val channel = current ?: return false
        channel.write(value)
        return true
    }

    /** The next byte from the open channel, or -1 when there is nothing more. */
    fun readFromChannel(): Int {
        val byte = current?.read() ?: -1
        if (byte >= 0) bytesServed++
        return byte
    }

    /** Whether the byte just read was the last one. */
    val channelAtEnd get() = current?.atEnd() ?: true

    fun unlisten() {
        current?.close()
        current = null
        listener = -1
    }

    fun untalk() {
        current = null
        talker = -1
    }

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
