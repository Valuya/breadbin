package be.valuya.breadbin.data

import android.content.Context
import android.net.Uri
import be.valuya.breadbin.engine.Zip
import be.valuya.breadbin.engine.mem.RomKind
import be.valuya.breadbin.engine.mem.Roms
import java.io.File

/** Which set of ROMs the machine is running on. */
enum class RomSource {
    /** The free replacement ROMs shipped with the app, so that it works out of the box. */
    BUNDLED,

    /** Commodore's own, which the user supplied. */
    SUPPLIED,
}

/**
 * Where the machine's ROMs come from.
 *
 * Breadbin ships the MEGA65 project's Open ROMs, which are free and are not Commodore's, so it can
 * boot the moment it is installed. They are not as compatible as the originals — most visibly, they
 * drive the serial bus themselves rather than through the KERNAL routines the emulated drive
 * answers, so disks do not load under them — and the app says so rather than leaving it to be
 * discovered.
 *
 * Commodore's own ROMs, if the user has them, are stored in the app's own storage rather than
 * referenced where they were picked from: a content URI granted by the file picker does not survive
 * a reboot, and an emulator that forgets how to start every few days is not much use.
 */
class RomStore(private val context: Context) {

    private val directory = File(context.filesDir, "roms").apply { mkdirs() }

    private fun fileFor(kind: RomKind) = File(directory, "${kind.name.lowercase()}.rom")

    /** Where the name of the file it came from is kept, so the user can see what they gave us. */
    private fun labelFor(kind: RomKind) = File(directory, "${kind.name.lowercase()}.from")

    fun has(kind: RomKind) = fileFor(kind).length() == kind.size.toLong()

    /** True when the user has supplied all three of the ROMs the machine itself needs. */
    val complete get() = RomKind.entries.filter { it.required }.all { has(it) }

    val source get() = if (complete) RomSource.SUPPLIED else RomSource.BUNDLED

    /**
     * Stores a file the user picked, working out from its contents which of the three it is.
     * Returns what it turned out to be, or null if it was not a ROM at all.
     */
    fun accept(uri: Uri): RomKind? = acceptAll(uri).lastOrNull()

    /**
     * The same, for a file that might be a zip.
     *
     * ROMs are downloaded as a set, in one archive, and asking somebody to unpack it first means
     * asking them to go and find a file manager. Everything inside that looks like a ROM is taken
     * and everything else is ignored, so pointing this at a general-purpose archive is harmless.
     */
    fun acceptAll(uri: Uri): List<RomKind> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return emptyList()
        if (Zip.isZip(bytes)) {
            val taken = mutableListOf<RomKind>()
            for (entry in Zip.entries(bytes)) {
                val kind = Roms.identify(entry.bytes) ?: continue
                // A set usually holds several revisions of the same ROM. Later ones win, which for
                // an alphabetically ordered archive means the newest revision of each.
                accept(entry.bytes, kind, entry.fileName)
                taken += kind
            }
            return taken
        }
        val kind = Roms.identify(bytes) ?: return emptyList()
        accept(bytes, kind, uri.lastPathSegment?.substringAfterLast('/'))
        return listOf(kind)
    }

    /** Stores bytes that have already been identified, whatever they arrived by. */
    fun accept(bytes: ByteArray, kind: RomKind, from: String? = null) {
        fileFor(kind).writeBytes(bytes)
        if (from != null) labelFor(kind).writeText(from) else labelFor(kind).delete()
    }

    /**
     * What is actually loaded for a kind, in words: which revision where the ROM says so, a
     * checksum where it does not, and the name of the file it came out of.
     *
     * This exists because "it does not work" and "you are running a different KERNAL than you
     * think" are indistinguishable from the outside, and the second one is common.
     */
    fun describe(kind: RomKind): String? {
        if (!has(kind)) {
            if (kind == RomKind.DRIVE) return null
            val bundled = runCatching { asset(BUNDLED_FILES.getValue(kind)) }.getOrNull() ?: return null
            return "Open ROMs (bundled) · " + Roms.describe(kind, bundled)
        }
        val bytes = runCatching { fileFor(kind).readBytes() }.getOrNull() ?: return null
        val from = runCatching { labelFor(kind).readText() }.getOrNull()
        return Roms.describe(kind, bytes) + (from?.let { " · $it" } ?: "")
    }

    /** The ROMs to run: Commodore's if they are all here, otherwise the free ones. */
    fun load(): Roms? = if (complete) loadSupplied() else loadBundled()

    private fun loadSupplied(): Roms? = runCatching {
        Roms.of(
            basic = fileFor(RomKind.BASIC).readBytes(),
            kernal = fileFor(RomKind.KERNAL).readBytes(),
            character = fileFor(RomKind.CHARACTER).readBytes(),
        )
    }.getOrNull()

    /**
     * The 1541's DOS, if the user supplied it. Its presence is what decides whether the drive is a
     * real emulated 1541 or the one written in Kotlin.
     */
    fun loadDrive(): IntArray? = runCatching {
        val bytes = fileFor(RomKind.DRIVE).takeIf { it.length() == RomKind.DRIVE.size.toLong() }
            ?.readBytes() ?: return null
        IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
    }.getOrNull()

    fun loadBundled(): Roms? = runCatching {
        Roms.of(
            basic = asset(BUNDLED_FILES.getValue(RomKind.BASIC)),
            kernal = asset(BUNDLED_FILES.getValue(RomKind.KERNAL)),
            character = asset(BUNDLED_FILES.getValue(RomKind.CHARACTER)),
        )
    }.getOrNull()

    private fun asset(name: String) =
        context.assets.open("$BUNDLED_DIRECTORY/$name").use { it.readBytes() }

    /** Throws away Commodore's ROMs and falls back to the free ones. */
    fun clear() {
        for (kind in RomKind.entries) {
            fileFor(kind).delete()
            labelFor(kind).delete()
        }
    }

    companion object {
        private const val BUNDLED_DIRECTORY = "openroms"

        private val BUNDLED_FILES = mapOf(
            RomKind.BASIC to "basic.rom",
            RomKind.KERNAL to "kernal.rom",
            RomKind.CHARACTER to "chargen.rom",
        )

        /**
         * Where to send somebody who wants Commodore's ROMs. VICE is the desktop emulator, and it
         * ships them; the app opens this in a browser rather than fetching anything itself.
         */
        const val WHERE_TO_GET_ROMS = "https://vice-emu.sourceforge.io/index.html#download"
    }
}
