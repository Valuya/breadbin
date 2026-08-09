package be.valuya.breadbin.engine.tape

import be.valuya.breadbin.engine.disk.Petscii

/**
 * A program with somewhere to go: the two-byte load address that starts a .prg, and the bytes that
 * follow it.
 */
class Program(val loadAddress: Int, val data: IntArray, val name: String) {

    val endAddress get() = loadAddress + data.size

    /** BASIC programs live at $0801 and are started with RUN rather than a jump. */
    val isBasic get() = loadAddress == BASIC_START

    companion object {
        const val BASIC_START = 0x0801

        /** The PC64 header a .p00 wraps its program in: a signature, a name, and two spare bytes. */
        private const val P00_HEADER = 26
        private const val P00_SIGNATURE = "C64File"

        /**
         * Reads whichever of the two shapes a single program arrives in. A .p00 is a .prg with a
         * PC64 header glued to the front, and mistaking one for the other reads the signature as a
         * load address and the rest of the header as instructions.
         */
        fun of(bytes: ByteArray, name: String = ""): Program {
            if (!isP00(bytes)) return fromPrg(bytes, name)
            // The header carries the name the file had on the C64, which is better than the one the
            // PC's filesystem gave it.
            val embedded = IntArray(16) { bytes[8 + it].toInt() and 0xFF }
                .takeWhile { it != 0 }
                .toIntArray()
            return fromPrg(
                bytes.copyOfRange(P00_HEADER, bytes.size),
                Petscii.toAscii(embedded).trim().ifBlank { name },
            )
        }

        fun isP00(bytes: ByteArray): Boolean =
            bytes.size > P00_HEADER &&
                String(bytes, 0, P00_SIGNATURE.length, Charsets.US_ASCII) == P00_SIGNATURE &&
                bytes[7].toInt() == 0

        fun fromPrg(bytes: ByteArray, name: String = ""): Program {
            require(bytes.size >= 2) { "a .prg is at least a load address" }
            val loadAddress = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
            val data = IntArray(bytes.size - 2) { bytes[it + 2].toInt() and 0xFF }
            return Program(loadAddress, data, name)
        }
    }
}

/**
 * A .t64 archive: nominally a tape image, actually a directory of programs with their load
 * addresses, which is how most single-file releases are passed around.
 *
 * The end address in an entry is famously often wrong — plenty of tools wrote a fixed value — so
 * the real length is taken from wherever the next entry's data starts, or the end of the file.
 */
object T64 {

    // A .p00 also starts "C64", so it has to be ruled out before the three bytes are believed.
    fun isT64(bytes: ByteArray): Boolean =
        bytes.size > 64 &&
            String(bytes, 0, 3, Charsets.US_ASCII) == "C64" &&
            !Program.isP00(bytes)

    fun entries(bytes: ByteArray): List<Program> {
        if (!isT64(bytes)) return emptyList()
        val used = bytes.leShort(36).let { if (it == 0) bytes.leShort(34) else it }
        val raw = ArrayList<Triple<Int, Int, String>>() // offset, start address, name
        val declaredEnd = ArrayList<Int>()

        for (i in 0 until used) {
            val at = 64 + i * 32
            if (at + 32 > bytes.size) break
            if (bytes[at].toInt() and 0xFF == 0) continue // an unused slot
            val start = bytes.leShort(at + 2)
            val end = bytes.leShort(at + 4)
            val offset = bytes.leInt(at + 8)
            val name = String(bytes, at + 16, 16, Charsets.ISO_8859_1).trim { it <= ' ' }
            if (offset <= 0 || offset >= bytes.size) continue
            raw += Triple(offset, start, name)
            declaredEnd += end
        }

        val sorted = raw.indices.sortedBy { raw[it].first }
        val limits = HashMap<Int, Int>()
        for ((position, index) in sorted.withIndex()) {
            val next = sorted.getOrNull(position + 1)?.let { raw[it].first } ?: bytes.size
            limits[index] = next
        }

        return raw.indices.map { index ->
            val (offset, start, name) = raw[index]
            val byLimit = limits[index] ?: bytes.size
            val byDeclaration = offset + (declaredEnd[index] - start).coerceAtLeast(0)
            val end = minOf(byLimit, if (byDeclaration > offset) byDeclaration else byLimit, bytes.size)
            Program(start, IntArray(end - offset) { bytes[offset + it].toInt() and 0xFF }, name)
        }
    }

    private fun ByteArray.leShort(at: Int) =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.leInt(at: Int) = leShort(at) or (leShort(at + 2) shl 16)
}

/** What a file turned out to be, so the app can say so and the machine can act on it. */
enum class MediaKind { DISK, TAPE, CARTRIDGE, PROGRAM, ARCHIVE, UNKNOWN }

object Media {
    /**
     * Works out what a file is from its contents where it can and its name where it cannot. A .prg
     * has no signature at all — it is two bytes of address and then anything — so the extension is
     * the only evidence there is.
     */
    fun identify(bytes: ByteArray, fileName: String): MediaKind {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when {
            TapImage.isTap(bytes) -> MediaKind.TAPE
            be.valuya.breadbin.engine.cart.CrtImage.isCrt(bytes) -> MediaKind.CARTRIDGE
            Program.isP00(bytes) -> MediaKind.PROGRAM
            T64.isT64(bytes) -> MediaKind.ARCHIVE
            extension == "d64" || bytes.size in setOf(174_848, 175_531, 196_608, 197_376) -> MediaKind.DISK
            extension == "prg" || extension == "p00" -> MediaKind.PROGRAM
            extension == "bin" || extension == "rom" -> MediaKind.CARTRIDGE
            else -> MediaKind.UNKNOWN
        }
    }

    /** A display name for a program taken out of an archive. */
    fun titleOf(program: Program): String =
        program.name.ifBlank { "PROGRAM" }.let { Petscii.display(Petscii.fromAscii(it)) }
}
