package be.valuya.breadbin.engine.tape

import be.valuya.breadbin.engine.disk.Petscii

/**
 * Writes a .tap file the way a C64 wrote one, so that the emulated datasette has something real to
 * play back.
 *
 * The machine does not record bytes on tape, it records pulses of three lengths, and a byte is
 * eighteen of them: a marker pair, then a pair per bit, then a parity pair. A short pulse followed
 * by a medium one is a nought, the other way round is a one, and a long pulse starts something.
 * Everything is written twice, because tape is unreliable and the KERNAL would rather have a second
 * try than lose the lot.
 */
object TapWriter {

    private const val SHORT = 0x30
    private const val MEDIUM = 0x42
    private const val LONG = 0x56

    /** What the KERNAL puts between blocks, as opposed to before the first one. */
    private const val REPEAT_LEADER = 0x1A00

    /**
     * A complete tape holding one program: the header block that says what and where it is, then
     * the program itself, each recorded twice.
     */
    fun write(program: Program, name: String, leader: Int = 0x6A00): ByteArray {
        val pulses = ArrayList<Int>()

        val header = IntArray(192) { 0x20 }
        // Type 3 is a program that must go back exactly where it came from, which is what an
        // emulator's caller almost always means.
        header[0] = if (program.isBasic) 1 else 3
        header[1] = program.loadAddress and 0xFF
        header[2] = (program.loadAddress shr 8) and 0xFF
        header[3] = program.endAddress and 0xFF
        header[4] = (program.endAddress shr 8) and 0xFF
        val petscii = Petscii.fromAscii(name.uppercase())
        for (i in 0 until 16) header[5 + i] = petscii.getOrElse(i) { 0x20 }

        // The KERNAL writes a very long leader before the first block and a shorter one between
        // the rest; a loader that has not seen enough of it will not start looking for a block.
        writeBlock(pulses, header, leader, first = true)
        writeBlock(pulses, header, REPEAT_LEADER, first = false)
        writeBlock(pulses, program.data, REPEAT_LEADER, first = true)
        writeBlock(pulses, program.data, REPEAT_LEADER, first = false)
        // A little trailer so the last pulse is not also the end of the file.
        repeat(200) { pulses += SHORT }

        return container(pulses)
    }

    private fun writeBlock(pulses: ArrayList<Int>, payload: IntArray, leader: Int, first: Boolean) {
        repeat(leader) { pulses += SHORT }
        // The countdown tells the KERNAL a block is starting and which of the two copies it is.
        val top = if (first) 0x89 else 0x09
        for (i in 0 until 9) writeByte(pulses, top - i)

        var checksum = 0
        for (byte in payload) {
            writeByte(pulses, byte)
            checksum = checksum xor byte
        }
        writeByte(pulses, checksum)
        // End of data: a long pulse and a short one, which is a pair that means nothing else.
        pulses += LONG
        pulses += SHORT
    }

    private fun writeByte(pulses: ArrayList<Int>, value: Int) {
        pulses += LONG
        pulses += MEDIUM
        var parity = 1
        for (bit in 0 until 8) {
            if (value shr bit and 1 == 1) {
                pulses += MEDIUM
                pulses += SHORT
                parity = parity xor 1
            } else {
                pulses += SHORT
                pulses += MEDIUM
            }
        }
        if (parity == 1) {
            pulses += MEDIUM
            pulses += SHORT
        } else {
            pulses += SHORT
            pulses += MEDIUM
        }
    }

    private fun container(pulses: List<Int>): ByteArray {
        val out = ByteArray(20 + pulses.size)
        "C64-TAPE-RAW".toByteArray(Charsets.US_ASCII).copyInto(out)
        out[12] = 1 // version 1
        out[16] = (pulses.size and 0xFF).toByte()
        out[17] = ((pulses.size shr 8) and 0xFF).toByte()
        out[18] = ((pulses.size shr 16) and 0xFF).toByte()
        for (i in pulses.indices) out[20 + i] = pulses[i].toByte()
        return out
    }
}
