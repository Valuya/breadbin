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

/** What a whole set came back as: which kinds arrived, and the name of anything that did not. */
data class RomSetResult(val loaded: List<RomKind>, val failed: List<String>)

/**
 * Fetches ROMs over the network, either from an address the user types or from the known set.
 *
 * This used to be only the first of those, on the reasoning that an app which knows an address for
 * somebody else's ROMs is nearer to handing them out than one which does not. That reasoning did
 * not survive looking at what the app already did: it linked to the exact folder these come from
 * and offered a box to paste an address into. The address was already there and already spelled
 * out. Making somebody copy it across by hand changed nothing except how long it took and who it
 * appeared to be for, and dressing an identical outcome up as caution is worse than doing the thing
 * and saying so.
 *
 * It is still the only thing in the app that touches the network, which is why the permission is
 * there at all.
 */
class RomDownload(private val store: RomStore) {

    /**
     * Fetches the whole set, carrying on past anything that fails: three missing ROMs are a
     * different problem from one, and stopping at the first would hide which.
     */
    suspend fun fetchSet(addresses: List<String> = RomStore.COMMODORE_ROM_SET): RomSetResult {
        val loaded = mutableListOf<RomKind>()
        val failed = mutableListOf<String>()
        for (address in addresses) {
            when (val result = fetch(address)) {
                is RomDownloadResult.Loaded -> loaded += result.kind
                is RomDownloadResult.Failed -> failed += address.substringAfterLast('/')
            }
        }
        // The drive's DOS arrives switched off. Turning it on swaps the drive written in Kotlin for
        // an emulated 1541 running this code, which is a change in how every disk loads — worth
        // having for fast loaders, not worth doing to somebody who pressed a button labelled "get
        // the ROMs". The row says it is off and the switch is right there.
        if (RomKind.DRIVE in loaded) store.setUsingSupplied(RomKind.DRIVE, false)
        return RomSetResult(loaded, failed)
    }

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
        // The last part of the path is a good enough name to show against the row, and knowing
        // which file a ROM came out of is most of what makes the setup screen worth reading.
        store.accept(bytes, kind, url.path.substringAfterLast('/').ifBlank { null })
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
