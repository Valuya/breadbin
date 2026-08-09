package be.valuya.breadbin.engine.disk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class D64Test {

    private fun blank() = D64.blank(Petscii.fromAscii("TEST DISK"), Petscii.fromAscii("01"))

    @Test
    fun `a blank disk has the free block count of a formatted floppy`() {
        // 683 sectors, less the whole of track 18 bar the two the directory is using.
        assertEquals(664, blank().blocksFree())
    }

    @Test
    fun `the geometry matches a 1541`() {
        assertEquals(21, D64.sectorsPerTrack(1))
        assertEquals(19, D64.sectorsPerTrack(18))
        assertEquals(18, D64.sectorsPerTrack(25))
        assertEquals(17, D64.sectorsPerTrack(35))
        assertEquals(174_848, D64.offsetOf(36, 0))
    }

    @Test
    fun `a file survives a round trip through the sector chain`() {
        val disk = blank()
        val contents = IntArray(1500) { it and 0xFF }
        assertNull(disk.writeFile(Petscii.fromAscii("HELLO"), contents, fileType = 2, replace = false))

        val entry = disk.find(Petscii.fromAscii("HELLO"))!!
        assertEquals("PRG", entry.typeName)
        assertEquals(6, entry.blocks) // 1500 bytes over 254 per block
        assertArrayEquals(contents, disk.readFile(entry))
        assertEquals(664 - 6, disk.blocksFree())
    }

    @Test
    fun `a file that exactly fills its last block does not gain a byte`() {
        val disk = blank()
        val contents = IntArray(254) { 0xAA }
        disk.writeFile(Petscii.fromAscii("EXACT"), contents, fileType = 2, replace = false)
        assertArrayEquals(contents, disk.readFile(disk.find(Petscii.fromAscii("EXACT"))!!))
    }

    @Test
    fun `writing over a file needs saying so`() {
        val disk = blank()
        val name = Petscii.fromAscii("ONCE")
        assertNull(disk.writeFile(name, IntArray(10), 2, replace = false))
        assertEquals(DosError.FILE_EXISTS, disk.writeFile(name, IntArray(10), 2, replace = false))
        assertNull(disk.writeFile(name, IntArray(600), 2, replace = true))
        assertEquals(600, disk.readFile(disk.find(name)!!).size)
        // The blocks of the replaced file must have gone back to the free pool rather than leaking.
        assertEquals(664 - 3, disk.blocksFree())
    }

    @Test
    fun `scratching frees the blocks and the directory slot`() {
        val disk = blank()
        disk.writeFile(Petscii.fromAscii("GONE"), IntArray(500), 2, replace = false)
        assertEquals(1, disk.scratch(Petscii.fromAscii("GONE")))
        assertEquals(664, disk.blocksFree())
        assertTrue(disk.directory().isEmpty())
    }

    @Test
    fun `wildcards match the way CBM DOS matches`() {
        val disk = blank()
        disk.writeFile(Petscii.fromAscii("GAME"), IntArray(4), 2, replace = false)
        disk.writeFile(Petscii.fromAscii("GAMEPART2"), IntArray(4), 2, replace = false)
        assertEquals("GAME", Petscii.display(disk.find(Petscii.fromAscii("GAME"))!!.name))
        assertEquals("GAME", Petscii.display(disk.find(Petscii.fromAscii("GA*"))!!.name))
        assertEquals("GAME", Petscii.display(disk.find(Petscii.fromAscii("G?ME"))!!.name))
        assertNull(disk.find(Petscii.fromAscii("GAM")))
    }

    @Test
    fun `more files than one directory sector holds still fit`() {
        val disk = blank()
        repeat(20) { disk.writeFile(Petscii.fromAscii("FILE$it"), IntArray(2), 2, replace = false) }
        assertEquals(20, disk.directory().size)
        assertEquals("FILE19", Petscii.display(disk.find(Petscii.fromAscii("FILE19"))!!.name))
    }

    @Test
    fun `the directory reads back as a BASIC program`() {
        val disk = blank()
        disk.writeFile(Petscii.fromAscii("HELLO"), IntArray(300), 2, replace = false)
        val listing = DirectoryListing.of(disk)

        assertEquals(0x01, listing[0])
        assertEquals(0x04, listing[1]) // loads to $0401
        val text = Petscii.toAscii(listing).uppercase()
        assertTrue(text, text.contains("TEST DISK"))
        assertTrue(text, text.contains("HELLO"))
        assertTrue(text, text.contains("PRG"))
        assertTrue(text, text.contains("BLOCKS FREE."))
        // A BASIC program ends with a null link pointer.
        assertEquals(0, listing[listing.size - 1])
        assertEquals(0, listing[listing.size - 2])
    }
}
