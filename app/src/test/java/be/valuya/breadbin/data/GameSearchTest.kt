package be.valuya.breadbin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Searching the Internet Archive, checked without going near it.
 *
 * The two fixtures are real answers from the two endpoints, saved as they arrived. That matters
 * more than it sounds: a parser tested against a fixture somebody made up tests the fixture, and
 * the field that turns out to be missing, or a number where a string was expected, is exactly what
 * a made-up one would not have.
 */
class GameSearchTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "no fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `the query is scoped to the C64 collection and to files`() {
        val query = GameSearch.queryFor("boulder dash")
        assertEquals("(boulder AND dash) AND collection:softwarelibrary_c64 AND mediatype:software", query)
    }

    /**
     * Punctuation is Solr syntax, and a game called "Pitfall!" or "H.E.R.O." would otherwise come
     * back as a parse error rather than as a game.
     */
    @Test
    fun `punctuation cannot break the query`() {
        // Whatever is typed, the terms come out as a parenthesised list of plain words joined by
        // AND — the brackets being the ones this puts there, not any the user did.
        val shape = Regex("""\(\w+( AND \w+)*\)""")
        for (typed in listOf("Pitfall!", "H.E.R.O.", "Ghosts 'n Goblins", "a:b", "x AND (y", "*", "\"")) {
            val query = GameSearch.queryFor(typed) ?: continue
            val terms = query.substringBefore(" AND collection:")
            assertTrue("\"$typed\" produced terms $terms", shape.matches(terms))
        }
    }

    @Test
    fun `a search with nothing in it is not a search`() {
        assertNull(GameSearch.queryFor(""))
        assertNull(GameSearch.queryFor("   "))
        assertNull(GameSearch.queryFor("!!!"))
    }

    @Test
    fun `the disk image is preferred over everything else in the item`() {
        val names = listOf(
            "__ia_thumb.jpg",
            "Game_files.xml",
            "Game_archive.torrent",
            "Game.prg",
            "Game.d64",
            "screenshot_00.png",
            "Game_meta.sqlite",
        )
        assertEquals("Game.d64", GameSearch.chooseFile(names))
    }

    /** A loose program is worth taking when there is no image, because it is all there is. */
    @Test
    fun `a program is taken when there is no disk`() {
        assertEquals("Game.prg", GameSearch.chooseFile(listOf("cover.jpg", "Game.prg", "notes.txt")))
    }

    @Test
    fun `an item with nothing runnable in it is refused rather than guessed at`() {
        assertNull(GameSearch.chooseFile(listOf("cover.jpg", "readme.txt", "thing_meta.xml")))
        assertNull(GameSearch.chooseFile(emptyList()))
    }

    @Test
    fun `a real search answer parses into results`() {
        val results = GameSearch.parseResults(fixture("archive-search.json"))
        assertTrue("nothing came out of a real answer", results.isNotEmpty())
        val first = results.first()
        assertTrue("no identifier", first.identifier.isNotBlank())
        assertTrue("no title", first.title.isNotBlank())
        // The search was for Boulder Dash, sorted by downloads, so it had better be in there.
        assertTrue(
            "no Boulder Dash in: " + results.joinToString { it.title },
            results.any { it.title.contains("Boulder Dash", ignoreCase = true) },
        )
    }

    /** Not every item has a year, and a missing field must not lose the whole result. */
    @Test
    fun `results without a year still come back`() {
        val results = GameSearch.parseResults(fixture("archive-search.json"))
        assertEquals(5, results.size)
        assertTrue("a year came back as the empty string", results.none { it.year == "" })
    }

    @Test
    fun `a real metadata answer gives up its file names and the right one is chosen`() {
        val names = GameSearch.fileNames(fixture("archive-metadata.json"))
        assertTrue("no files listed", names.isNotEmpty())
        val chosen = GameSearch.chooseFile(names)
        assertEquals("Impossible_Mission_1984_Epyx_cr_REM_t_5_REM.d64", chosen)
    }

    @Test
    fun `the total comes back so paging knows when to stop`() {
        val total = GameSearch.parseTotal(fixture("archive-search.json"))
        val shown = GameSearch.parseResults(fixture("archive-search.json")).size
        assertTrue("no total in a real answer", total > 0)
        assertTrue("the total ($total) is not more than the $shown asked for", total > shown)
    }

    /**
     * The reason paging cannot simply append.
     *
     * Results are sorted by download count and a great many items are tied, so a row can sit on the
     * boundary between two pages and be returned by both — pages 16 and 17 of a search for Boulder
     * Dash really do share one. The list draws rows keyed by identifier, and a repeated key is not
     * a cosmetic problem, it is a crash.
     */
    @Test
    fun `a row returned on two pages is only kept once`() {
        val first = listOf(result("a"), result("b"), result("c"))
        val second = listOf(result("c"), result("d"))
        val merged = GameSearch.merge(first, second)
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.identifier })
        assertEquals(
            "a merged page has a repeated key",
            merged.size,
            merged.map { it.identifier }.toSet().size,
        )
    }

    @Test
    fun `merging keeps what was already there and its order`() {
        val existing = listOf(result("a"), result("b"))
        assertEquals(existing, GameSearch.merge(existing, emptyList()))
        assertEquals(existing.map { it.identifier }, GameSearch.merge(existing, existing).map { it.identifier })
        assertEquals(listOf("x"), GameSearch.merge(emptyList(), listOf(result("x"))).map { it.identifier })
    }

    /** A page that repeats itself internally must not get through either. */
    @Test
    fun `duplicates inside a single page are dropped too`() {
        val merged = GameSearch.merge(emptyList(), listOf(result("a"), result("a"), result("b")))
        assertEquals(listOf("a", "b"), merged.map { it.identifier })
    }

    private fun result(identifier: String) = GameResult(identifier, "Title $identifier", null)

    @Test
    fun `rubbish from the network is a failure rather than a crash`() {
        for (body in listOf("", "not json", "{}", """{"response":{}}""", "[]")) {
            assertTrue(
                "parsing \"$body\" did not fail cleanly",
                runCatching { GameSearch.parseResults(body) }.let { it.isFailure || it.getOrNull()!!.isEmpty() },
            )
            assertTrue(
                "listing files of \"$body\" did not fail cleanly",
                runCatching { GameSearch.fileNames(body) }.let { it.isFailure || it.getOrNull()!!.isEmpty() },
            )
        }
    }
}
