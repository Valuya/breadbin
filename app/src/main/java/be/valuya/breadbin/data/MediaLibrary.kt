package be.valuya.breadbin.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import be.valuya.breadbin.engine.tape.Media
import be.valuya.breadbin.engine.Zip
import be.valuya.breadbin.engine.tape.MediaKind
import java.io.File

/** One thing in the library, and the file it came from. */
data class MediaItem(
    val file: File,
    val title: String,
    val kind: MediaKind,
) {
    val bytes: ByteArray get() = file.readBytes()
}

/**
 * The disks, tapes, cartridges and programs the user has added.
 *
 * Files are copied in rather than referenced. That costs some storage and buys two things: the
 * library still works after a reboot has expired the picker's permissions, and a game that saves
 * to its disk has somewhere to save to.
 */
class MediaLibrary(private val context: Context) {

    private val directory = File(context.filesDir, "media").apply { mkdirs() }

    fun list(): List<MediaItem> =
        directory.listFiles()
            ?.filter { it.isFile }
            ?.mapNotNull { file ->
                val kind = runCatching { Media.identify(file.readBytes(), file.name) }
                    .getOrDefault(MediaKind.UNKNOWN)
                if (kind == MediaKind.UNKNOWN) null else MediaItem(file, titleOf(file.name), kind)
            }
            ?.sortedBy { it.title.lowercase() }
            .orEmpty()

    /** Copies a picked file in. Returns the item, or null if it is not something we can run. */
    fun add(uri: Uri): MediaItem? = addAll(uri).firstOrNull()

    /**
     * The same, for a file that might be a zip.
     *
     * Games arrive in archives far more often than they arrive loose, and an emulator that makes
     * you unpack one first is an emulator you have to go and find a file manager for. Everything
     * inside that Breadbin can run is copied in and everything else is ignored.
     */
    fun addAll(uri: Uri): List<MediaItem> {
        val name = displayName(uri) ?: return emptyList()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return emptyList()
        return add(name, bytes)
    }

    /**
     * The same, for bytes that arrived some way other than the picker — a download, say. Zips are
     * unpacked here too, because a game fetched from an archive is in one as often as a game
     * picked off storage is.
     */
    fun add(name: String, bytes: ByteArray): List<MediaItem> {
        if (Zip.isZip(bytes)) {
            return Zip.entries(bytes).mapNotNull { store(it.fileName, it.bytes) }
        }
        return listOfNotNull(store(name, bytes))
    }

    private fun store(name: String, bytes: ByteArray): MediaItem? {
        val kind = Media.identify(bytes, name)
        if (kind == MediaKind.UNKNOWN) return null

        var target = File(directory, sanitise(name))
        var attempt = 1
        while (target.exists() && !target.readBytes().contentEquals(bytes)) {
            target = File(directory, sanitise(name, " ($attempt)"))
            attempt++
        }
        target.writeBytes(bytes)
        return MediaItem(target, titleOf(target.name), kind)
    }

    fun remove(item: MediaItem) {
        item.file.delete()
    }

    fun find(fileName: String): MediaItem? = list().firstOrNull { it.file.name == fileName }

    /** Writes a changed disk image back where it came from, so a saved game is still there later. */
    fun save(item: MediaItem, bytes: ByteArray) {
        item.file.writeBytes(bytes)
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) return cursor.getString(column)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitise(name: String, suffix: String = ""): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").take(80)
        if (suffix.isEmpty()) return cleaned
        val extension = cleaned.substringAfterLast('.', "")
        val stem = cleaned.substringBeforeLast('.')
        return if (extension.isEmpty()) "$stem$suffix" else "$stem$suffix.$extension"
    }

    private fun titleOf(fileName: String) = fileName.substringBeforeLast('.').replace('_', ' ')
}
