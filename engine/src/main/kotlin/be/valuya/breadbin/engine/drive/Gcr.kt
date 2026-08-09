package be.valuya.breadbin.engine.drive

import be.valuya.breadbin.engine.disk.D64

/**
 * Group Coded Recording: how a 1541 puts bytes on a disk.
 *
 * The drive's read electronics cannot cope with long runs of the same bit, so every four bits are
 * written as five chosen never to give more than two zeroes in a row. That leaves the all-ones
 * pattern unused by data, which is what makes SYNC possible — ten or more one bits in a row cannot
 * occur inside real data, so the drive can find the start of a block by looking for them.
 *
 * A .d64 is a file of decoded sectors, so playing one back through a real drive means encoding it:
 * building the whole track, headers, gaps and all, exactly as a formatted disk would hold it.
 */
object Gcr {

    /** Four bits in, five bits out. */
    private val ENCODE = intArrayOf(
        0x0A, 0x0B, 0x12, 0x13, 0x0E, 0x0F, 0x16, 0x17,
        0x09, 0x19, 0x1A, 0x1B, 0x0D, 0x1D, 0x1E, 0x15,
    )

    private val DECODE = IntArray(32) { -1 }.also { table ->
        for (nybble in 0 until 16) table[ENCODE[nybble]] = nybble
    }

    /** How many GCR bytes a track holds, which is set by how fast the disk goes past the head. */
    fun trackLength(track: Int) = when {
        track <= 17 -> 7692
        track <= 24 -> 7142
        track <= 30 -> 6666
        else -> 6250
    }

    /** The recording density the drive selects for a track, 3 on the outside down to 0 inside. */
    fun densityOf(track: Int) = when {
        track <= 17 -> 3
        track <= 24 -> 2
        track <= 30 -> 1
        else -> 0
    }

    /** Drive cycles between one byte arriving under the head and the next. */
    fun cyclesPerByte(density: Int) = when (density) {
        3 -> 26
        2 -> 28
        1 -> 30
        else -> 32
    }

    /** Encodes four bytes as the five they are written as. */
    fun encodeQuad(source: IntArray, at: Int, destination: IntArray, to: Int) {
        var bits = 0L
        for (i in 0 until 4) {
            val byte = if (at + i < source.size) source[at + i] else 0
            bits = (bits shl 5) or ENCODE[(byte shr 4) and 0x0F].toLong()
            bits = (bits shl 5) or ENCODE[byte and 0x0F].toLong()
        }
        for (i in 0 until 5) {
            destination[to + i] = ((bits shr ((4 - i) * 8)) and 0xFF).toInt()
        }
    }

    /** Decodes five GCR bytes back to the four they stand for, or null if they are not valid GCR. */
    fun decodeQuint(source: IntArray, at: Int, destination: IntArray, to: Int): Boolean {
        var bits = 0L
        for (i in 0 until 5) bits = (bits shl 8) or (source[(at + i) % source.size].toLong() and 0xFF)
        for (i in 0 until 4) {
            val high = DECODE[((bits shr (35 - i * 10)) and 0x1F).toInt()]
            val low = DECODE[((bits shr (30 - i * 10)) and 0x1F).toInt()]
            if (high < 0 || low < 0) return false
            destination[to + i] = (high shl 4) or low
        }
        return true
    }

    /**
     * Builds one whole track as the drive would find it: for every sector a sync mark, a header
     * saying which sector it is, a gap, another sync, the data with its checksum, and a tail gap
     * long enough that the sectors fill the track.
     */
    fun buildTrack(disk: D64, track: Int, id1: Int, id2: Int): IntArray {
        val sectors = D64.sectorsPerTrack(track)
        val length = trackLength(track)
        val out = IntArray(length) { GAP }
        val tail = (length - sectors * SECTOR_LENGTH) / sectors

        var at = 0
        for (sector in 0 until sectors) {
            repeat(SYNC_LENGTH) { out[at++] = 0xFF }

            val header = IntArray(8)
            header[0] = 0x08
            header[1] = sector xor track xor id2 xor id1
            header[2] = sector
            header[3] = track
            header[4] = id2
            header[5] = id1
            header[6] = 0x0F
            header[7] = 0x0F
            encodeQuad(header, 0, out, at); at += 5
            encodeQuad(header, 4, out, at); at += 5

            repeat(HEADER_GAP) { out[at++] = GAP }
            repeat(SYNC_LENGTH) { out[at++] = 0xFF }

            val block = disk.read(track, sector)
            val data = IntArray(260)
            data[0] = 0x07
            var checksum = 0
            for (i in 0 until 256) {
                data[1 + i] = block[i]
                checksum = checksum xor block[i]
            }
            data[257] = checksum
            for (i in 0 until 65) {
                encodeQuad(data, i * 4, out, at)
                at += 5
            }

            repeat(tail) { out[at++] = GAP }
        }
        return out
    }

    /**
     * Reads a whole track back out of its GCR and into the image, which is how a game's saves
     * survive being written by the drive itself rather than by the emulator.
     *
     * Anything that does not decode is left alone: a track carrying copy protection, or one the
     * drive was halfway through writing, should not overwrite good sectors with rubbish.
     */
    fun decodeTrack(gcr: IntArray, track: Int, disk: D64): Int {
        var written = 0
        val sectors = D64.sectorsPerTrack(track)
        var at = 0
        while (at < gcr.size) {
            // Find a sync mark, then whatever block follows it.
            if (gcr[at] != 0xFF) {
                at++
                continue
            }
            while (at < gcr.size && gcr[at] == 0xFF) at++
            if (at >= gcr.size) break

            val header = IntArray(8)
            if (!decodeQuint(gcr, at, header, 0) || !decodeQuint(gcr, at + 5, header, 4)) continue
            if (header[0] != 0x08) continue
            val sector = header[2]
            if (header[3] != track || sector >= sectors) continue
            // The header carries its own checksum, and without checking it a header that decoded
            // as valid GCR but was half-written could put a good data block on top of a good
            // sector — which is exactly the damage this decoder exists to avoid.
            if (header[1] != (sector xor header[3] xor header[4] xor header[5])) continue

            // The data block is behind the next sync mark along.
            var search = at + 10
            val limit = search + HEADER_GAP + SYNC_LENGTH + 8
            while (search < limit && gcr[search % gcr.size] != 0xFF) search++
            if (search >= limit) continue
            while (gcr[search % gcr.size] == 0xFF) search++

            val data = IntArray(260)
            var ok = true
            for (i in 0 until 65) {
                if (!decodeQuint(gcr, search + i * 5, data, i * 4)) {
                    ok = false
                    break
                }
            }
            if (!ok || data[0] != 0x07) continue

            var checksum = 0
            for (i in 0 until 256) checksum = checksum xor data[1 + i]
            if (checksum != data[257]) continue

            disk.write(track, sector, IntArray(256) { data[1 + it] })
            written++
            at = search
        }
        return written
    }

    /** What the drive writes between blocks, and what a freshly formatted track is full of. */
    private const val GAP = 0x55

    private const val SYNC_LENGTH = 5
    private const val HEADER_GAP = 9

    /** Sync, header, gap, sync, data — before the tail gap that pads the track out. */
    private const val SECTOR_LENGTH = SYNC_LENGTH + 10 + HEADER_GAP + SYNC_LENGTH + 325
}
