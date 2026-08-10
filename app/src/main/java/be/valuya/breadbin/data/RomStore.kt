package be.valuya.breadbin.data

import android.content.Context
import android.net.Uri
import be.valuya.breadbin.engine.Zip
import be.valuya.breadbin.engine.mem.RomKind
import be.valuya.breadbin.engine.mem.Roms
import be.valuya.breadbin.engine.mem.romInUse
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

    /** Its presence means "keep this one but do not use it", which is the off position. */
    private fun disabledFor(kind: RomKind) = File(directory, "${kind.name.lowercase()}.off")

    /**
     * Whether the supplied ROM of this kind is the one in use, as opposed to the free one.
     *
     * Each is switched separately because each fails separately. A KERNAL that halts a game and a
     * character set from the wrong country are different problems, and being able to put one back
     * without losing the other is the difference between narrowing a fault down and starting over.
     */
    fun usingSupplied(kind: RomKind) = has(kind) && !disabledFor(kind).exists()

    fun setUsingSupplied(kind: RomKind, use: Boolean) {
        if (use) disabledFor(kind).delete() else disabledFor(kind).writeText("")
    }

    /**
     * Whether a usable ROM of this kind is stored.
     *
     * Checked by reading it, not by its size on disk. An earlier version of the identification went
     * on size alone and filed all sorts of things in the wrong places; those files are still there
     * on anybody's phone who imported them, and a store that trusts its own past decisions would go
     * on running a C128 MMU as BASIC for ever.
     */
    fun has(kind: RomKind) = stored(kind) != null

    private fun stored(kind: RomKind): ByteArray? {
        val file = fileFor(kind)
        if (file.length() != kind.size.toLong()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return bytes.takeIf { Roms.identify(it) == kind }
    }

    /** True when all three of the ROMs the machine itself needs are the user's own, and in use. */
    val complete get() = RomKind.entries.filter { it.required }.all { inUse(it) == RomSource.SUPPLIED }

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
        // Something the user has just gone and found is something they want used.
        disabledFor(kind).delete()
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
        val from = runCatching { labelFor(kind).readText() }.getOrNull()
        if (fileFor(kind).length() == kind.size.toLong() && stored(kind) == null) {
            return "Not a valid ${kind.name.lowercase()} ROM, ignored" + (from?.let { " · $it" } ?: "")
        }
        // Switched on, but held back because its other half is missing. Saying nothing here is how
        // a machine ends up booting to a blank screen with every switch looking correct.
        if (usingSupplied(kind) && inUse(kind) != RomSource.SUPPLIED) {
            val other = if (kind == RomKind.BASIC) "KERNAL" else "BASIC"
            return "Not in use — BASIC and the KERNAL must come from the same set, and no valid " +
                "$other was supplied. Running the bundled pair." + (from?.let { " · $it" } ?: "")
        }
        if (usingSupplied(kind)) {
            return Roms.describe(kind, stored(kind)!!) + (from?.let { " · $it" } ?: "")
        }
        if (kind == RomKind.DRIVE) {
            return if (has(kind)) "Off — no 1541, disks load through the built-in drive" else null
        }
        val bundled = runCatching { asset(BUNDLED_FILES.getValue(kind)) }.getOrNull() ?: return null
        return "Open ROMs (bundled) · " + Roms.describe(kind, bundled)
    }

    /**
     * Where each ROM is actually coming from, which is not always what its switch says.
     *
     * BASIC and the KERNAL are not two ROMs, they are one program in two chips: the KERNAL finishes
     * its reset with `JMP ($A000)` and the two call into each other at fixed addresses for the rest
     * of the machine's life. The free set is a rewrite, so one of each is not a half-working
     * machine, it is a machine that never reaches a prompt. Taking one and leaving the other is
     * therefore not a choice this can offer, however reasonable the two separate switches look:
     * unless both are the user's, both are the bundled ones.
     *
     * The character set has no such problem — it is glyphs, nothing calls into it — and the drive
     * ROM is a different computer's altogether. Those two stay independent.
     */
    fun inUse(kind: RomKind): RomSource =
        if (romInUse(kind, ::usingSupplied)) RomSource.SUPPLIED else RomSource.BUNDLED

    /** The ROMs to run. */
    fun load(): Roms? = runCatching {
        Roms.of(
            basic = chosen(RomKind.BASIC),
            kernal = chosen(RomKind.KERNAL),
            character = chosen(RomKind.CHARACTER),
        )
    }.getOrNull()

    private fun chosen(kind: RomKind): ByteArray =
        (if (inUse(kind) == RomSource.SUPPLIED) stored(kind) else null)
            ?: asset(BUNDLED_FILES.getValue(kind))

    /**
     * The 1541's DOS, if the user supplied it. Its presence is what decides whether the drive is a
     * real emulated 1541 or the one written in Kotlin.
     */
    fun loadDrive(): IntArray? = runCatching {
        val bytes = (if (usingSupplied(RomKind.DRIVE)) stored(RomKind.DRIVE) else null) ?: return null
        // A drive whose ROM does not add up never reaches its serial routines: it blinks its LED
        // for ever and every load says SEARCHING until the user gives up. Better no real drive at
        // all, which leaves the one written in Kotlin answering the bus perfectly well.
        if (!Roms.driveRomPassesSelfTest(bytes)) return null
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
            disabledFor(kind).delete()
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
