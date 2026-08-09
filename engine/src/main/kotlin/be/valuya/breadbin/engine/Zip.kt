package be.valuya.breadbin.engine

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** One file out of an archive. */
data class ZipEntry(val name: String, val bytes: ByteArray) {
    /** The name without any directories in front of it, which is all anybody wants to see. */
    val fileName: String get() = name.substringAfterLast('/').substringAfterLast('\\')

    // Generated equality on a class holding a ByteArray compares the array by identity, which makes
    // two entries with the same contents unequal and is never what anybody means.
    override fun equals(other: Any?) =
        other is ZipEntry && other.name == name && other.bytes.contentEquals(bytes)

    override fun hashCode() = 31 * name.hashCode() + bytes.contentHashCode()
}

/**
 * Reading a zip, because that is how everything actually arrives.
 *
 * Nothing to do with the Commodore 64, and everything to do with the fact that a ROM set comes as a
 * zip, a game comes as a zip, and an emulator that makes the user unpack them first is one they
 * have to go and find a file manager for.
 */
object Zip {

    /** A file starts with PK, or it is not a zip whatever it is called. */
    fun isZip(bytes: ByteArray) =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            (bytes[2].toInt() == 3 || bytes[2].toInt() == 5 || bytes[2].toInt() == 7)

    /**
     * Everything inside, ignoring directories and anything implausibly large.
     *
     * A zip says how big each entry is before it hands any of it over, and a hostile one can lie —
     * so this counts the bytes as they arrive rather than trusting the header, and gives up on
     * anything that would not have fitted in a Commodore 64 several times over.
     */
    fun entries(bytes: ByteArray, limit: Int = MAXIMUM_ENTRY, total: Int = MAXIMUM_TOTAL): List<ZipEntry> {
        val found = mutableListOf<ZipEntry>()
        var extracted = 0L
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { stream ->
                while (true) {
                    val entry = stream.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val content = stream.readAtMost(limit) ?: continue
                    extracted += content.size
                    if (extracted > total) return found
                    found += ZipEntry(entry.name, content)
                    if (found.size >= MAXIMUM_COUNT) return found
                }
            }
        }
        return found
    }

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            if (out.size() + read > limit) return null // too big to be anything we run
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    /** Bigger than the biggest disk image, and far smaller than a mistake. */
    private const val MAXIMUM_ENTRY = 8 * 1024 * 1024
    private const val MAXIMUM_TOTAL = 64 * 1024 * 1024
    private const val MAXIMUM_COUNT = 512
}
