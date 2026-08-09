package be.valuya.breadbin.engine.disk

/**
 * A .d64 disk image: the 683 sectors of a 1541 floppy, laid out track by track.
 *
 * The 1541 packs more sectors onto the outer tracks than the inner ones, which is why the geometry
 * here is a table rather than a multiplication, and why a block is addressed by track and sector
 * rather than by number.
 */
class D64(val data: ByteArray) {

    val tracks: Int = when (data.size) {
        174_848, 175_531 -> 35
        196_608, 197_376 -> 40
        // 1541 images turn up truncated often enough to be worth accepting: a short image simply
        // has fewer usable tracks.
        else -> if (data.size >= 174_848) 35 else (0 until 35).count { offsetOf(it + 1, 0) < data.size }
    }

    /** True when the image has been changed and the caller should write it back out. */
    var dirty = false
        private set

    fun read(track: Int, sector: Int): IntArray {
        val at = offsetOf(track, sector)
        return IntArray(256) { if (at + it < data.size) data[at + it].toInt() and 0xFF else 0 }
    }

    fun write(track: Int, sector: Int, block: IntArray) {
        val at = offsetOf(track, sector)
        if (at + 256 > data.size) return
        for (i in 0 until 256) data[at + i] = block[i].toByte()
        dirty = true
    }

    fun byteAt(track: Int, sector: Int, offset: Int): Int {
        val at = offsetOf(track, sector) + offset
        return if (at < data.size) data[at].toInt() and 0xFF else 0
    }

    fun markClean() {
        dirty = false
    }

    /** The disk's name and two-character ID, as they appear at the top of a directory listing. */
    fun header(): Pair<IntArray, IntArray> {
        val bam = read(DIRECTORY_TRACK, 0)
        val name = IntArray(16) { bam[0x90 + it] }
        val id = IntArray(2) { bam[0xA2 + it] }
        return name to id
    }

    fun directory(): List<DirectoryEntry> {
        val entries = mutableListOf<DirectoryEntry>()
        var track = DIRECTORY_TRACK
        var sector = 1
        val seen = mutableSetOf<Int>()
        while (track in 1..tracks && seen.add(track * 100 + sector)) {
            val block = read(track, sector)
            for (slot in 0 until 8) {
                val at = slot * 32
                val type = block[at + 2]
                if (type and 0x0F == 0) continue // a scratched or never-used slot
                entries += DirectoryEntry(
                    fileType = type and 0x0F,
                    closed = type and 0x80 != 0,
                    locked = type and 0x40 != 0,
                    track = block[at + 3],
                    sector = block[at + 4],
                    rawName = IntArray(16) { block[at + 5 + it] },
                    blocks = block[at + 30] or (block[at + 31] shl 8),
                    directoryTrack = track,
                    directorySector = sector,
                    directorySlot = slot,
                )
            }
            val nextTrack = block[0]
            val nextSector = block[1]
            if (nextTrack == 0) break
            track = nextTrack
            sector = nextSector
        }
        return entries
    }

    /** Follows a file's sector chain and returns its bytes. */
    fun readFile(entry: DirectoryEntry): IntArray {
        val out = ArrayList<Int>(entry.blocks * 254)
        var track = entry.track
        var sector = entry.sector
        val seen = mutableSetOf<Int>()
        while (track in 1..tracks && seen.add(track * 100 + sector)) {
            val block = read(track, sector)
            val nextTrack = block[0]
            val nextSector = block[1]
            // On the last block, the second link byte is how many bytes of it are real.
            val last = nextTrack == 0
            val count = if (last) (nextSector - 1).coerceIn(0, 254) else 254
            for (i in 0 until count) out += block[2 + i]
            if (last) break
            track = nextTrack
            sector = nextSector
        }
        return out.toIntArray()
    }

    fun find(pattern: IntArray, fileType: Int = -1): DirectoryEntry? =
        directory().firstOrNull { entry ->
            (fileType < 0 || entry.fileType == fileType) && entry.matches(pattern)
        }

    // ---- writing -----------------------------------------------------------------------------

    /**
     * Writes a file, replacing one of the same name if [replace] is set. Returns null on success or
     * the DOS error to report.
     */
    fun writeFile(name: IntArray, contents: IntArray, fileType: Int, replace: Boolean): DosError? {
        val existing = find(name)
        if (existing != null && !replace) return DosError.FILE_EXISTS
        if (existing != null) freeChain(existing.track, existing.sector)

        val blocksNeeded = maxOf(1, (contents.size + 253) / 254)
        if (blocksFree() < blocksNeeded) return DosError.DISK_FULL

        var previousTrack = 0
        var previousSector = 0
        var firstTrack = 0
        var firstSector = 0
        var written = 0
        var lastTrack = 17
        var lastSector = 0

        repeat(blocksNeeded) {
            val allocated = allocateSector(lastTrack, lastSector) ?: return DosError.DISK_FULL
            lastTrack = allocated.first
            lastSector = allocated.second
            val remaining = contents.size - written
            val count = minOf(254, remaining)
            val block = IntArray(256)
            for (i in 0 until count) block[2 + i] = contents[written + i]
            block[0] = 0
            block[1] = count + 1
            write(lastTrack, lastSector, block)

            if (previousTrack == 0) {
                firstTrack = lastTrack
                firstSector = lastSector
            } else {
                val previous = read(previousTrack, previousSector)
                previous[0] = lastTrack
                previous[1] = lastSector
                write(previousTrack, previousSector, previous)
            }
            previousTrack = lastTrack
            previousSector = lastSector
            written += count
        }

        val slot = existing ?: allocateDirectorySlot() ?: return DosError.DISK_FULL
        val block = read(slot.directoryTrack, slot.directorySector)
        val at = slot.directorySlot * 32
        block[at + 2] = 0x80 or fileType
        block[at + 3] = firstTrack
        block[at + 4] = firstSector
        for (i in 0 until 16) block[at + 5 + i] = if (i < name.size) name[i] else 0xA0
        for (i in 21 until 30) block[at + i] = 0
        block[at + 30] = blocksNeeded and 0xFF
        block[at + 31] = (blocksNeeded shr 8) and 0xFF
        write(slot.directoryTrack, slot.directorySector, block)
        return null
    }

    /** Deletes every file matching the pattern, returning how many went. */
    fun scratch(pattern: IntArray): Int {
        var count = 0
        for (entry in directory()) {
            if (!entry.matches(pattern)) continue
            freeChain(entry.track, entry.sector)
            val block = read(entry.directoryTrack, entry.directorySector)
            block[entry.directorySlot * 32 + 2] = 0
            write(entry.directoryTrack, entry.directorySector, block)
            count++
        }
        return count
    }

    fun blocksFree(): Int {
        val bam = read(DIRECTORY_TRACK, 0)
        var free = 0
        for (track in 1..minOf(tracks, 35)) {
            if (track == DIRECTORY_TRACK) continue
            free += bam[4 + (track - 1) * 4]
        }
        return free
    }

    private fun allocateDirectorySlot(): DirectoryEntry? {
        var track = DIRECTORY_TRACK
        var sector = 1
        val seen = mutableSetOf<Int>()
        while (track in 1..tracks && seen.add(track * 100 + sector)) {
            val block = read(track, sector)
            for (slot in 0 until 8) {
                if (block[slot * 32 + 2] and 0x0F == 0) {
                    return DirectoryEntry(
                        fileType = 0, closed = false, locked = false, track = 0, sector = 0,
                        rawName = IntArray(16) { 0xA0 }, blocks = 0,
                        directoryTrack = track, directorySector = sector, directorySlot = slot,
                    )
                }
            }
            if (block[0] == 0) {
                // The directory is full; chain another sector onto it if track 18 has one spare.
                val next = allocateSector(track, sector) ?: return null
                block[0] = next.first
                block[1] = next.second
                write(track, sector, block)
                val fresh = IntArray(256)
                fresh[1] = 0xFF
                write(next.first, next.second, fresh)
                track = next.first
                sector = next.second
                continue
            }
            track = block[0]
            sector = block[1]
        }
        return null
    }

    /**
     * Finds and reserves the next free sector, starting from the one just used. Files are laid out
     * with a gap between consecutive blocks so that the drive has time to read one before the next
     * comes round — a real 1541 needs that, and keeping the layout makes images behave alike.
     */
    private fun allocateSector(afterTrack: Int, afterSector: Int): Pair<Int, Int>? {
        val order = trackSearchOrder(afterTrack)
        for (track in order) {
            if (track == DIRECTORY_TRACK && afterTrack != DIRECTORY_TRACK) continue
            val count = sectorsPerTrack(track)
            val start = if (track == afterTrack) (afterSector + INTERLEAVE) % count else 0
            for (i in 0 until count) {
                val sector = (start + i) % count
                if (isFree(track, sector)) {
                    setAllocated(track, sector)
                    return track to sector
                }
            }
        }
        return null
    }

    private fun trackSearchOrder(from: Int): List<Int> {
        // The 1541 works outwards from track 18 in both directions, so that a fresh disk keeps its
        // files near the directory.
        val order = mutableListOf<Int>()
        if (from in 1..tracks) order += from
        for (distance in 1..tracks) {
            val below = DIRECTORY_TRACK - distance
            val above = DIRECTORY_TRACK + distance
            if (below >= 1) order += below
            if (above <= minOf(tracks, 35)) order += above
        }
        return order.distinct()
    }

    private fun isFree(track: Int, sector: Int): Boolean {
        val bam = read(DIRECTORY_TRACK, 0)
        val at = 4 + (track - 1) * 4
        val bits = bam[at + 1 + (sector shr 3)]
        return bits and (1 shl (sector and 0x07)) != 0
    }

    private fun setAllocated(track: Int, sector: Int) {
        val bam = read(DIRECTORY_TRACK, 0)
        val at = 4 + (track - 1) * 4
        val byte = at + 1 + (sector shr 3)
        if (bam[byte] and (1 shl (sector and 0x07)) != 0) {
            bam[byte] = bam[byte] and (1 shl (sector and 0x07)).inv()
            bam[at] = (bam[at] - 1).coerceAtLeast(0)
            write(DIRECTORY_TRACK, 0, bam)
        }
    }

    private fun setFree(track: Int, sector: Int) {
        val bam = read(DIRECTORY_TRACK, 0)
        val at = 4 + (track - 1) * 4
        val byte = at + 1 + (sector shr 3)
        if (bam[byte] and (1 shl (sector and 0x07)) == 0) {
            bam[byte] = bam[byte] or (1 shl (sector and 0x07))
            bam[at] = bam[at] + 1
            write(DIRECTORY_TRACK, 0, bam)
        }
    }

    private fun freeChain(startTrack: Int, startSector: Int) {
        var track = startTrack
        var sector = startSector
        val seen = mutableSetOf<Int>()
        while (track in 1..tracks && seen.add(track * 100 + sector)) {
            val block = read(track, sector)
            setFree(track, sector)
            if (block[0] == 0) break
            track = block[0]
            sector = block[1]
        }
    }

    companion object {
        const val DIRECTORY_TRACK = 18

        /** How far ahead the next block of a file is placed, in sectors. */
        private const val INTERLEAVE = 10

        fun sectorsPerTrack(track: Int) = when {
            track <= 17 -> 21
            track <= 24 -> 19
            track <= 30 -> 18
            else -> 17
        }

        fun offsetOf(track: Int, sector: Int): Int {
            var offset = 0
            for (t in 1 until track) offset += sectorsPerTrack(t) * 256
            return offset + sector * 256
        }

        /** A blank formatted disk, for when the app is asked to make a new one. */
        fun blank(name: IntArray, id: IntArray): D64 {
            val disk = D64(ByteArray(174_848))
            val bam = IntArray(256)
            bam[0] = DIRECTORY_TRACK
            bam[1] = 1
            bam[2] = 0x41 // DOS version 'A'
            for (track in 1..35) {
                val at = 4 + (track - 1) * 4
                val sectors = sectorsPerTrack(track)
                bam[at] = sectors
                var remaining = sectors
                for (byte in 0 until 3) {
                    val bits = minOf(remaining, 8)
                    bam[at + 1 + byte] = (1 shl bits) - 1
                    remaining -= bits
                }
            }
            for (i in 0 until 16) bam[0x90 + i] = if (i < name.size) name[i] else 0xA0
            bam[0xA0] = 0xA0
            bam[0xA1] = 0xA0
            bam[0xA2] = if (id.isNotEmpty()) id[0] else 0x30
            bam[0xA3] = if (id.size > 1) id[1] else 0x30
            bam[0xA4] = 0xA0
            bam[0xA5] = 0x32 // "2A", the DOS type
            bam[0xA6] = 0x41
            for (i in 0xA7..0xAA) bam[i] = 0xA0
            disk.write(DIRECTORY_TRACK, 0, bam)

            val directory = IntArray(256)
            directory[1] = 0xFF
            disk.write(DIRECTORY_TRACK, 1, directory)

            // The BAM and the first directory sector are in use from the moment the disk exists.
            disk.setAllocated(DIRECTORY_TRACK, 0)
            disk.setAllocated(DIRECTORY_TRACK, 1)
            disk.markClean()
            return disk
        }
    }
}

/** One slot of a disk's directory. */
class DirectoryEntry(
    val fileType: Int,
    val closed: Boolean,
    val locked: Boolean,
    val track: Int,
    val sector: Int,
    val rawName: IntArray,
    val blocks: Int,
    val directoryTrack: Int,
    val directorySector: Int,
    val directorySlot: Int,
) {
    /** The name with the padding stripped, still in PETSCII. */
    val name: IntArray get() = rawName.takeWhile { it != 0xA0 }.toIntArray()

    /** CBM DOS matching: `*` takes the rest of the name and `?` takes one character. */
    fun matches(pattern: IntArray): Boolean {
        val actual = name
        for (i in pattern.indices) {
            val wanted = pattern[i]
            if (wanted == '*'.code) return true
            if (i >= actual.size) return false
            if (wanted == '?'.code) continue
            if (wanted != actual[i]) return false
        }
        return pattern.size == actual.size
    }

    val typeName: String get() = when (fileType) {
        0 -> "DEL"
        1 -> "SEQ"
        2 -> "PRG"
        3 -> "USR"
        4 -> "REL"
        else -> "???"
    }
}

/** The errors the drive reports on its command channel. */
enum class DosError(val code: Int, val message: String) {
    OK(0, "OK"),
    FILE_NOT_FOUND(62, "FILE NOT FOUND"),
    FILE_EXISTS(63, "FILE EXISTS"),
    DISK_FULL(72, "DISK FULL"),
    WRITE_PROTECT(26, "WRITE PROTECT ON"),
    SYNTAX_ERROR(30, "SYNTAX ERROR"),
    DRIVE_NOT_READY(74, "DRIVE NOT READY"),
}
