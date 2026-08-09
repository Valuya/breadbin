package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.cart.Cartridge
import be.valuya.breadbin.engine.cart.CrtImage
import be.valuya.breadbin.engine.cart.OceanType1
import be.valuya.breadbin.engine.tape.Datasette
import be.valuya.breadbin.engine.tape.MediaKind
import be.valuya.breadbin.engine.tape.Media
import be.valuya.breadbin.engine.tape.Program
import be.valuya.breadbin.engine.tape.T64
import be.valuya.breadbin.engine.tape.TapImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {

    // ---- tapes -------------------------------------------------------------------------------

    private fun tap(version: Int, body: ByteArray): ByteArray {
        val header = ByteArray(20)
        "C64-TAPE-RAW".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[12] = version.toByte()
        header[16] = (body.size and 0xFF).toByteArray()
        header[17] = ((body.size shr 8) and 0xFF).toByteArray()
        header[18] = ((body.size shr 16) and 0xFF).toByteArray()
        return header + body
    }

    private fun Int.toByteArray() = this.toByte()

    @Test
    fun `a version zero pulse is eight cycles per unit`() {
        val image = TapImage.parse(tap(0, byteArrayOf(0x10, 0x20, 0x30)))
        assertEquals(listOf(0x10 * 8, 0x20 * 8, 0x30 * 8), image.pulses.toList())
    }

    @Test
    fun `a version one long pulse spells its length out`() {
        val body = byteArrayOf(0x30, 0x00, 0x11, 0x22, 0x03, 0x40)
        val image = TapImage.parse(tap(1, body))
        // The three bytes after the zero are the length in cycles, least significant first.
        assertEquals(listOf(0x30 * 8, 0x032211, 0x40 * 8), image.pulses.toList())
    }

    @Test
    fun `the datasette only moves when a key is down and the motor is on`() {
        val deck = Datasette()
        deck.load(TapImage.parse(tap(1, byteArrayOf(0x02, 0x02))))
        var pulses = 0
        deck.onPulse = { pulses++ }

        repeat(100) { deck.cycle() }
        assertEquals("a stopped deck should not move", 0, pulses)

        deck.play()
        repeat(100) { deck.cycle() }
        assertEquals("the motor is off, so nothing should have moved", 0, pulses)

        deck.motorOn = true
        repeat(0x02 * 8) { deck.cycle() }
        assertEquals(1, pulses)
        repeat(0x02 * 8) { deck.cycle() }
        assertEquals(2, pulses)
    }

    @Test
    fun `the deck stops at the end of the tape`() {
        val deck = Datasette()
        deck.load(TapImage.parse(tap(1, byteArrayOf(0x01, 0x01))))
        deck.motorOn = true
        deck.play()
        repeat(1000) { deck.cycle() }
        assertTrue(!deck.playing)
        assertEquals(1.0, deck.position, 0.001)
    }

    // ---- archives ----------------------------------------------------------------------------

    @Test
    fun `a t64 entry is found and clipped to the data that is really there`() {
        val payload = ByteArray(100) { (it and 0xFF).toByte() }
        val file = ByteArray(64 + 32 + payload.size)
        "C64S tape image file".toByteArray(Charsets.US_ASCII).copyInto(file)
        file[36] = 1 // one entry used
        file[64] = 1 // a normal file
        file[65] = 0x82.toByte() // PRG
        file[66] = 0x00; file[67] = 0x10 // start $1000
        // An end address that claims far more than the file holds, which is common and wrong.
        file[68] = 0x00; file[69] = 0xF0.toByte()
        file[72] = (96).toByte() // data offset
        "TESTFILE        ".toByteArray(Charsets.US_ASCII).copyInto(file, 80)
        payload.copyInto(file, 96)

        val entries = T64.entries(file)
        assertEquals(1, entries.size)
        assertEquals(0x1000, entries[0].loadAddress)
        assertEquals(payload.size, entries[0].data.size)
        assertEquals("TESTFILE", entries[0].name)
    }

    @Test
    fun `a prg is a load address and then the program`() {
        val program = Program.fromPrg(byteArrayOf(0x01, 0x08, 0x11, 0x22))
        assertEquals(0x0801, program.loadAddress)
        assertTrue(program.isBasic)
        assertEquals(0x0803, program.endAddress)
    }

    // ---- cartridges --------------------------------------------------------------------------

    private fun crt(type: Int, exrom: Int, game: Int, banks: List<Pair<Int, Int>>): ByteArray {
        val header = ByteArray(0x40)
        "C64 CARTRIDGE   ".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[0x13] = 0x40 // header length
        header[0x17] = type.toByte()
        header[0x18] = exrom.toByte()
        header[0x19] = game.toByte()
        "TEST".toByteArray(Charsets.US_ASCII).copyInto(header, 0x20)

        var out = header
        for ((bank, fill) in banks) {
            val packet = ByteArray(0x10 + 0x2000)
            "CHIP".toByteArray(Charsets.US_ASCII).copyInto(packet)
            packet[6] = 0x20 // packet length $2010
            packet[7] = 0x10
            packet[11] = bank.toByte()
            packet[12] = 0x80.toByte() // load address $8000
            packet[14] = 0x20 // image size $2000
            for (i in 0 until 0x2000) packet[0x10 + i] = fill.toByte()
            out += packet
        }
        return out
    }

    @Test
    fun `a plain cartridge pulls EXROM low and shows its ROM`() {
        val cartridge = Cartridge.of(crt(type = 0, exrom = 0, game = 1, banks = listOf(0 to 0xAA)))
        assertEquals(false, cartridge.exrom)
        assertEquals(true, cartridge.game)
        assertEquals(0xAA, cartridge.readRoml(0x8000))
    }

    @Test
    fun `an Ocean cartridge pages on a write to DE00`() {
        val cartridge = Cartridge.of(
            crt(type = 5, exrom = 0, game = 1, banks = listOf(0 to 0x11, 1 to 0x22, 2 to 0x33))
        )
        assertTrue(cartridge is OceanType1)
        assertEquals(0x11, cartridge.readRoml(0x8000))
        cartridge.writeIo1(0xDE00, 2)
        assertEquals(0x33, cartridge.readRoml(0x8000))
        cartridge.writeIo1(0xDE00, 1)
        assertEquals(0x22, cartridge.readRoml(0x9FFF))
    }

    @Test
    fun `a Magic Desk cartridge can unmap itself`() {
        val cartridge = Cartridge.of(crt(type = 19, exrom = 0, game = 1, banks = listOf(0 to 0x11, 1 to 0x22)))
        var lineChanges = 0
        cartridge.onLinesChanged = { lineChanges++ }
        cartridge.writeIo1(0xDE00, 1)
        assertEquals(0x22, cartridge.readRoml(0x8000))
        cartridge.writeIo1(0xDE00, 0x80)
        assertTrue("the cartridge should have unmapped itself", cartridge.exrom)
        assertEquals(1, lineChanges)
    }

    @Test
    fun `a cartridge in the machine takes over the reset vector`() {
        val machine = Machine(TestRoms.of())
        val image = crt(type = 0, exrom = 0, game = 1, banks = listOf(0 to 0x5A))
        machine.insertCartridge(Cartridge.of(image))
        assertEquals(0x5A, machine.memory.read(0x8000))
        machine.insertCartridge(null)
        assertTrue(machine.memory.read(0x8000) != 0x5A)
    }

    // ---- identification ----------------------------------------------------------------------

    @Test
    fun `files are recognised by what is in them`() {
        assertEquals(MediaKind.TAPE, Media.identify(tap(1, byteArrayOf(1)), "game.tap"))
        assertEquals(
            MediaKind.CARTRIDGE,
            Media.identify(crt(0, 0, 1, listOf(0 to 0)), "game.crt"),
        )
        assertEquals(MediaKind.DISK, Media.identify(ByteArray(174_848), "game.d64"))
        assertEquals(MediaKind.PROGRAM, Media.identify(byteArrayOf(0x01, 0x08), "game.prg"))
    }
}
