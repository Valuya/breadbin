package be.valuya.breadbin.data

import android.content.Context
import android.net.Uri
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

    fun has(kind: RomKind) = fileFor(kind).length() == kind.size.toLong()

    /** True when the user has supplied all three of the ROMs the machine itself needs. */
    val complete get() = RomKind.entries.filter { it.required }.all { has(it) }

    val source get() = if (complete) RomSource.SUPPLIED else RomSource.BUNDLED

    /**
     * Stores a file the user picked, working out from its contents which of the three it is.
     * Returns what it turned out to be, or null if it was not a ROM at all.
     */
    fun accept(uri: Uri): RomKind? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val kind = Roms.identify(bytes) ?: return null
        accept(bytes, kind)
        return kind
    }

    /** Stores bytes that have already been identified, whatever they arrived by. */
    fun accept(bytes: ByteArray, kind: RomKind) {
        fileFor(kind).writeBytes(bytes)
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
            basic = asset("basic.rom"),
            kernal = asset("kernal.rom"),
            character = asset("chargen.rom"),
        )
    }.getOrNull()

    private fun asset(name: String) =
        context.assets.open("$BUNDLED_DIRECTORY/$name").use { it.readBytes() }

    /** Throws away Commodore's ROMs and falls back to the free ones. */
    fun clear() {
        for (kind in RomKind.entries) fileFor(kind).delete()
    }

    companion object {
        private const val BUNDLED_DIRECTORY = "openroms"

        /**
         * Where to send somebody who wants Commodore's ROMs. VICE is the desktop emulator, and it
         * ships them; the app opens this in a browser rather than fetching anything itself.
         */
        const val WHERE_TO_GET_ROMS = "https://vice-emu.sourceforge.io/index.html#download"
    }
}
