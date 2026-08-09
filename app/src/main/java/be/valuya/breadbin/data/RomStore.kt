package be.valuya.breadbin.data

import android.content.Context
import android.net.Uri
import be.valuya.breadbin.engine.mem.RomKind
import be.valuya.breadbin.engine.mem.Roms
import java.io.File

/**
 * Where the three Commodore ROMs live once the user has supplied them.
 *
 * They are kept in the app's own storage rather than referenced where they were picked from: a
 * content URI granted by the file picker does not survive a reboot, and an emulator that forgets
 * how to start every few days is not much use.
 */
class RomStore(private val context: Context) {

    private val directory = File(context.filesDir, "roms").apply { mkdirs() }

    private fun fileFor(kind: RomKind) = File(directory, "${kind.name.lowercase()}.rom")

    fun has(kind: RomKind) = fileFor(kind).length() == expectedSize(kind)

    val complete get() = RomKind.entries.all { has(it) }

    /**
     * Stores a file the user picked, working out from its contents which of the three it is.
     * Returns what it turned out to be, or null if it was not a ROM at all.
     */
    fun accept(uri: Uri): RomKind? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val kind = Roms.identify(bytes) ?: return null
        fileFor(kind).writeBytes(bytes)
        return kind
    }

    fun load(): Roms? {
        if (!complete) return null
        return runCatching {
            Roms.of(
                basic = fileFor(RomKind.BASIC).readBytes(),
                kernal = fileFor(RomKind.KERNAL).readBytes(),
                character = fileFor(RomKind.CHARACTER).readBytes(),
            )
        }.getOrNull()
    }

    fun clear() {
        for (kind in RomKind.entries) fileFor(kind).delete()
    }

    private fun expectedSize(kind: RomKind) = when (kind) {
        RomKind.BASIC -> Roms.BASIC_SIZE.toLong()
        RomKind.KERNAL -> Roms.KERNAL_SIZE.toLong()
        RomKind.CHARACTER -> Roms.CHARACTER_SIZE.toLong()
    }
}
