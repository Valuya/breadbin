package be.valuya.breadbin.data

import be.valuya.breadbin.engine.mem.RomKind
import be.valuya.breadbin.engine.mem.Roms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** What came back from a fetch. */
sealed interface RomDownloadResult {
    data class Loaded(val kind: RomKind) : RomDownloadResult
    data class Failed(val reason: String) : RomDownloadResult
}

/**
 * Fetches a ROM from an address the user types.
 *
 * This is deliberately not a "get the ROMs" button with somewhere already in it. Breadbin does not
 * know where anybody's ROMs are and does not ship a pointer to a copy of somebody else's: what it
 * has is a way to bring in a file from a URL, the same as the file picker brings one in from
 * storage, and the address is the user's to supply and to be responsible for.
 *
 * It is also the only thing in the app that touches the network, which is why the permission is
 * there at all.
 */
class RomDownload(private val store: RomStore) {

    suspend fun fetch(address: String): RomDownloadResult = withContext(Dispatchers.IO) {
        val url = runCatching { URL(address.trim()) }.getOrNull()
            ?: return@withContext RomDownloadResult.Failed("That is not a web address")
        // Android blocks cleartext by default from Android 9 on, so an http address would go all
        // the way to a generic connection failure and tell the user nothing useful. Better to say
        // what is actually wrong with it.
        if (url.protocol != "https") {
            return@withContext RomDownloadResult.Failed("Only https addresses can be fetched")
        }

        val bytes = runCatching {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode !in 200..299) {
                    return@runCatching null
                }
                // A ROM is at most sixteen kilobytes. Reading a bounded amount means a wrong address
                // pointing at something enormous costs a moment rather than the device's storage.
                connection.inputStream.use { it.readAtMost(MAXIMUM) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return@withContext RomDownloadResult.Failed("Could not fetch that address")

        val kind = Roms.identify(bytes)
            ?: return@withContext RomDownloadResult.Failed("That file is not a C64 ROM")
        store.accept(bytes, kind)
        RomDownloadResult.Loaded(kind)
    }

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (buffer.size() <= limit) {
            val read = read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private companion object {
        const val TIMEOUT = 15_000

        /** Comfortably more than the largest of the three, and far less than a mistake. */
        const val MAXIMUM = 64 * 1024
    }
}
