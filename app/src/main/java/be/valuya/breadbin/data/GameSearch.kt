package be.valuya.breadbin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One thing that turned up in a search, before anything has been downloaded. */
data class GameResult(
    val identifier: String,
    val title: String,
    val year: String?,
)

sealed interface GameSearchResult {
    /** [total] is how many the Archive says there are altogether, not how many are in [results]. */
    data class Found(val results: List<GameResult>, val total: Int) : GameSearchResult
    data class Failed(val reason: String) : GameSearchResult
}

sealed interface GameFetchResult {
    data class Added(val items: List<MediaItem>) : GameFetchResult
    data class Failed(val reason: String) : GameFetchResult
}

/**
 * Finding games without leaving the app.
 *
 * The Internet Archive keeps a large, openly published C64 collection with a search API that needs
 * no key and no account, so that is what this asks. Nothing is mirrored or repackaged here: a
 * search is a query, and downloading is fetching the file the Archive already serves to a browser.
 *
 * Two endpoints do all of it. The search returns identifiers and titles; the metadata for an
 * identifier lists the files in it, of which usually exactly one is a disk image and the rest are
 * screenshots and bookkeeping.
 */
class GameSearch(private val library: MediaLibrary) {

    suspend fun search(terms: String, page: Int = 1): GameSearchResult = withContext(Dispatchers.IO) {
        val query = queryFor(terms)
            ?: return@withContext GameSearchResult.Failed("Type something to search for")
        val address = buildString {
            append("https://archive.org/advancedsearch.php?q=")
            append(encode(query))
            append("&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year")
            // Most downloaded first. Relevance alone puts hacks and demo versions above the game
            // everybody actually meant, because their titles are longer and match more of it.
            append("&sort%5B%5D=downloads+desc")
            append("&rows=$PAGE_SIZE&page=$page&output=json")
        }
        val body = fetch(address, SEARCH_MAXIMUM)?.toString(Charsets.UTF_8)
            ?: return@withContext GameSearchResult.Failed("Could not reach the Internet Archive")

        val parsed = runCatching { parseResults(body) to parseTotal(body) }.getOrNull()
            ?: return@withContext GameSearchResult.Failed("The Internet Archive sent something unexpected")
        GameSearchResult.Found(parsed.first, parsed.second)
    }

    /** Downloads the runnable file from one result and puts it in the library. */
    suspend fun fetchInto(result: GameResult): GameFetchResult = withContext(Dispatchers.IO) {
        val metadata = fetch("https://archive.org/metadata/${encode(result.identifier)}", SEARCH_MAXIMUM)
            ?.toString(Charsets.UTF_8)
            ?: return@withContext GameFetchResult.Failed("Could not reach the Internet Archive")

        val names = runCatching { fileNames(metadata) }.getOrNull().orEmpty()
        val chosen = chooseFile(names)
            ?: return@withContext GameFetchResult.Failed("Nothing in \"${result.title}\" is a disk, tape or cartridge")

        val bytes = fetch(
            "https://archive.org/download/${encode(result.identifier)}/${encode(chosen)}",
            DOWNLOAD_MAXIMUM,
        ) ?: return@withContext GameFetchResult.Failed("Could not download ${chosen}")

        val added = library.add(chosen, bytes)
        if (added.isEmpty()) GameFetchResult.Failed("$chosen is not something Breadbin can run")
        else GameFetchResult.Added(added)
    }

    private fun fetch(address: String, limit: Int): ByteArray? = runCatching {
        val url = URL(address)
        if (url.protocol != "https") return null
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (buffer.size() <= limit) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private const val TIMEOUT = 20_000
        const val PAGE_SIZE = 30

        /** Search answers and file listings are text and small; a huge one is a wrong answer. */
        private const val SEARCH_MAXIMUM = 2 * 1024 * 1024

        /**
         * A disk image is 175K and a five-disk archive is a few megabytes. This is far above
         * anything that is really a C64 game and far below anything that would fill a phone.
         */
        private const val DOWNLOAD_MAXIMUM = 32 * 1024 * 1024

        /**
         * The collection to search inside.
         *
         * Not the whole Archive: unscoped, "elite" and "pitfall" return films, books and the same
         * game for six other machines. This is the umbrella the C64 sub-collections sit under.
         */
        const val COLLECTION = "softwarelibrary_c64"

        /**
         * What the user typed, made safe to put in a Solr query, or null if nothing is left.
         *
         * The punctuation Solr treats as syntax is dropped rather than escaped. Escaping would be
         * more faithful, but nobody searching for a game means a boolean expression by "Pitfall!",
         * and a stray bracket returning a parse error instead of a game helps no one.
         */
        fun queryFor(terms: String): String? {
            val words = terms
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotBlank() }
            if (words.isEmpty()) return null
            // mediatype excludes the collections themselves, which otherwise come back as results
            // and cannot be downloaded because they are not files.
            return words.joinToString(" AND ", prefix = "(", postfix = ")") +
                " AND collection:$COLLECTION AND mediatype:software"
        }

        /**
         * Which file out of an item to actually download.
         *
         * An Archive item holds the game and a good deal else: screenshots, a torrent, an XML
         * description, a thumbnail. Preferring by extension in this order picks the disk image over
         * a loose program from the same item, which matters because the loose one is often just the
         * loader.
         */
        fun chooseFile(names: List<String>): String? {
            val ranked = names.mapNotNull { name ->
                val rank = PREFERENCE.indexOf(name.substringAfterLast('.', "").lowercase())
                if (rank < 0) null else rank to name
            }
            return ranked.minWithOrNull(compareBy({ it.first }, { it.second.length }))?.second
        }

        private val PREFERENCE = listOf("d64", "g64", "t64", "crt", "tap", "prg", "p00", "zip")

        /** The identifiers and titles out of a search answer. */
        fun parseResults(body: String): List<GameResult> {
            val docs = JSONObject(body).getJSONObject("response").getJSONArray("docs")
            return (0 until docs.length()).mapNotNull { i ->
                val doc = docs.optJSONObject(i) ?: return@mapNotNull null
                val identifier = doc.optString("identifier").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                // Some items have no title at all; the identifier is a readable enough stand-in.
                val title = doc.optString("title").takeIf { it.isNotBlank() } ?: identifier
                GameResult(identifier, title, doc.optString("year").takeIf { it.isNotBlank() })
            }
        }

        /** How many the Archive says match altogether, which is what says whether to ask for more. */
        fun parseTotal(body: String): Int =
            JSONObject(body).getJSONObject("response").optInt("numFound", 0)

        /**
         * One page appended to what is already there, dropping anything already seen.
         *
         * Not defensive tidying: the Archive sorts by download count and a great many items are
         * tied, so a row can sit on the boundary between two pages and be returned by both. Pages
         * 16 and 17 of a search for Boulder Dash share one. The list draws rows keyed by identifier
         * and a repeated key is a crash, so this is what stops the second page of a long search
         * taking the screen down with it.
         */
        fun merge(existing: List<GameResult>, next: List<GameResult>): List<GameResult> {
            val seen = existing.mapTo(HashSet()) { it.identifier }
            return existing + next.filter { seen.add(it.identifier) }
        }

        /** The file names out of an item's metadata. */
        fun fileNames(body: String): List<String> {
            val files = JSONObject(body).optJSONArray("files") ?: return emptyList()
            return (0 until files.length()).mapNotNull {
                files.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() }
            }
        }
    }
}
