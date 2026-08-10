package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.mem.RomKind
import be.valuya.breadbin.engine.mem.Roms
import be.valuya.breadbin.engine.tape.Media
import be.valuya.breadbin.engine.tape.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry as JavaZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reading archives, which is how ROMs and games actually turn up.
 *
 * The important cases are not the happy one. An archive is somebody else's file: it can hold
 * directories, things that are not ours, and — if it is hostile — entries that claim to be small
 * and are not.
 */
class ZipTest {

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { stream ->
            for ((name, bytes) in entries) {
                stream.putNextEntry(JavaZipEntry(name))
                stream.write(bytes)
                stream.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a zip is recognised and anything else is not`() {
        assertTrue(Zip.isZip(zip("a.txt" to ByteArray(4))))
        assertFalse(Zip.isZip(ByteArray(1000)))
        assertFalse(Zip.isZip("PK not really".toByteArray()))
        assertFalse(Zip.isZip(ByteArray(2)))
    }

    @Test
    fun `everything inside comes back with its contents`() {
        val archive = zip("one.prg" to byteArrayOf(1, 2, 3), "two.prg" to byteArrayOf(4, 5))
        val entries = Zip.entries(archive)
        assertEquals(listOf("one.prg", "two.prg"), entries.map { it.name })
        assertEquals(listOf(1, 2, 3), entries[0].bytes.map { it.toInt() })
    }

    @Test
    fun `a name with directories in it keeps only the file`() {
        val entries = Zip.entries(zip("games/boulder/dash.d64" to ByteArray(4)))
        assertEquals("dash.d64", entries.single().fileName)
    }

    @Test
    fun `an entry too big to be anything we run is left out`() {
        val entries = Zip.entries(zip("huge.bin" to ByteArray(4096)), limit = 1024)
        assertTrue("a 4K entry came back under a 1K limit", entries.isEmpty())
    }

    @Test
    fun `the total is bounded as well, however many entries there are`() {
        val many = (0 until 40).map { "file$it.bin" to ByteArray(1024) }.toTypedArray()
        val entries = Zip.entries(zip(*many), total = 8 * 1024)
        assertTrue("unpacked ${entries.size} entries past an eight-kilobyte budget", entries.size <= 8)
    }

    @Test
    fun `something that is not a zip at all comes back empty rather than throwing`() {
        assertEquals(emptyList<ZipEntry>(), Zip.entries(ByteArray(500) { it.toByte() }))
    }

    @Test
    fun `two entries with the same contents are equal`() {
        assertEquals(ZipEntry("a", byteArrayOf(1, 2)), ZipEntry("a", byteArrayOf(1, 2)))
    }

    /**
     * The whole point of the feature: a ROM set as downloaded, with several revisions of each and
     * a readme, sorted out without the user having to unpack anything.
     */
    @Test
    fun `a ROM set in an archive is picked apart correctly`() {
        val archive = zip(
            "readme.txt" to "where these came from".toByteArray(),
            "roms/basic.901226-01.bin" to basic(),
            "roms/kernal.901227-03.bin" to kernal(),
            "roms/characters.901225-01.bin" to characterSet(),
            "roms/1541.dos" to driveRom(),
        )
        val kinds = Zip.entries(archive).mapNotNull { Roms.identify(it.bytes) }
        assertEquals(
            setOf(RomKind.BASIC, RomKind.KERNAL, RomKind.CHARACTER, RomKind.DRIVE),
            kinds.toSet(),
        )
    }

    // ROMs have to look like themselves now, so the fixtures do too.
    private fun kernal() = ByteArray(Roms.KERNAL_SIZE).also {
        for (entry in 0xFF81..0xFFEA step 3) it[entry - 0xE000] = 0x4C
        it[0x1FFC] = 0xE2.toByte()
        it[0x1FFD] = 0xFC.toByte()
    }

    private fun basic() = ByteArray(Roms.BASIC_SIZE).also {
        it[0] = 0x94.toByte(); it[1] = 0xE3.toByte(); it[2] = 0x7B; it[3] = 0xE3.toByte()
    }

    private fun characterSet() = ByteArray(Roms.CHARACTER_SIZE) { (it * 37).toByte() }

    /** Sixteen kilobytes that add up the way the drive insists on. */
    private fun driveRom(): ByteArray {
        val rom = ByteArray(Roms.DRIVE_SIZE)
        for (v in 0..255) {
            rom[0xFEE6 - 0xC000] = v.toByte()
            for (w in 0..255) {
                rom[0] = w.toByte()
                if (Roms.driveRomPassesSelfTest(rom)) return rom
            }
        }
        error("could not build a drive ROM that passes its own test")
    }

    @Test
    fun `a games archive gives up its games and ignores the rest`() {
        val archive = zip(
            "cover.jpg" to ByteArray(100),
            "GAME.d64" to ByteArray(174_848),
            "notes.txt" to "hi".toByteArray(),
        )
        val runnable = Zip.entries(archive)
            .map { Media.identify(it.bytes, it.fileName) }
            .filter { it != MediaKind.UNKNOWN }
        assertEquals(listOf(MediaKind.DISK), runnable)
    }
}
