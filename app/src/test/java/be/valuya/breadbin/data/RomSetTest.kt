package be.valuya.breadbin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

/**
 * The addresses the download button uses.
 *
 * Nothing here goes near the network — a test that did would fail on a train. What it checks is the
 * part that can be got wrong silently and is only discovered by a user tapping the button: a
 * mistyped address, a plain-http one that Android will refuse, a file listed twice, or a set that
 * has quietly stopped covering all four ROMs.
 */
class RomSetTest {

    private val addresses = RomStore.COMMODORE_ROM_SET

    @Test
    fun `the set is the four files the machine and the drive need`() {
        val names = addresses.map { it.substringAfterLast('/') }
        assertEquals(
            listOf(
                "basic-901226-01.bin",
                "kernal-901227-03.bin",
                "chargen-901225-01.bin",
                "dos1541ii-251968-03.bin",
            ),
            names,
        )
    }

    /** The KERNAL is the one that matters most, and revision 3 is the one to want. */
    @Test
    fun `the KERNAL in the set is revision three`() {
        assertTrue(
            "the set does not name 901227-03",
            addresses.any { it.contains("kernal-901227-03") },
        )
    }

    @Test
    fun `every address is a well formed https URL`() {
        for (address in addresses) {
            val url = runCatching { URL(address) }.getOrNull()
            assertTrue("not a URL at all: $address", url != null)
            // RomDownload refuses anything else, so an http address here would be a row that can
            // never work rather than one that works insecurely.
            assertEquals("not https: $address", "https", url!!.protocol)
            assertTrue("no path: $address", url.path.length > 1)
        }
    }

    @Test
    fun `no address appears twice`() {
        assertEquals(addresses.size, addresses.toSet().size)
    }
}
