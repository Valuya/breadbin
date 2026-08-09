package be.valuya.breadbin.engine.drive

import be.valuya.breadbin.engine.Machine
import be.valuya.breadbin.engine.disk.D64
import be.valuya.breadbin.engine.disk.Petscii
import be.valuya.breadbin.engine.mem.Roms
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * Two whole computers talking to each other down three wires.
 *
 * This is the test the real drive exists for. Nothing above the KERNAL is involved: the C64 runs
 * its own serial routines, the 1541 runs its own DOS, and the bytes come off a track of GCR that
 * was built the way a drive would have written it. If this passes, a fast loader has something real
 * to talk to.
 *
 * It needs both ROM sets, which cannot live in this repository, so it is skipped without them:
 * `BREADBIN_ROMS` for the C64's three, and a 16K 1541 DOS in the same directory.
 */
class DriveBootTest {

    private fun romDirectory(): File? =
        System.getenv("BREADBIN_ROMS")?.let(::File)?.takeIf { it.isDirectory }

    private fun computerRoms(directory: File): Roms? {
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
        val basic = files.firstOrNull { it.name.contains("basic", true) && it.length() == 8192L }
        val kernal = files.firstOrNull { it.name.contains("kernal", true) && it.length() == 8192L }
        val character = files.firstOrNull {
            (it.name.contains("char", true) || it.name.contains("font", true)) && it.length() == 4096L
        }
        if (basic == null || kernal == null || character == null) return null
        return Roms.of(basic.readBytes(), kernal.readBytes(), character.readBytes())
    }

    /** Any sixteen-kilobyte file in the ROM directory is taken to be the drive's DOS. */
    private fun driveRom(directory: File): IntArray? {
        val file = directory.listFiles()?.firstOrNull { it.isFile && it.length() == 16384L } ?: return null
        val bytes = file.readBytes()
        return IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
    }

    private fun screen(machine: Machine): List<String> =
        (0 until 25).map { row ->
            (0 until 40).joinToString("") { column ->
                val code = machine.memory.peek(0x0400 + row * 40 + column) and 0x7F
                when (code) {
                    0 -> "@"
                    in 1..26 -> ('A' + code - 1).toString()
                    in 32..63 -> code.toChar().toString()
                    else -> " "
                }
            }.trimEnd()
        }

    private fun machineWithDrive(): Machine? {
        val directory = romDirectory() ?: return null
        val roms = computerRoms(directory) ?: return null
        val dos = driveRom(directory) ?: return null
        return Machine(roms, driveRom = dos)
    }

    private fun disk(): D64 {
        val disk = D64.blank(Petscii.fromAscii("REAL DRIVE"), Petscii.fromAscii("01"))
        disk.writeFile(Petscii.fromAscii("HELLO"), programFile("OFF THE DISK"), 2, replace = false)
        disk.writeFile(Petscii.fromAscii("SECOND"), IntArray(300) { 0x42 }, 2, replace = false)
        return disk
    }

    /**
     * A program as it sits in a disk file: the two-byte load address first, then the bytes. Leaving
     * it off does not fail loudly — LOAD without a secondary address puts the file at the start of
     * BASIC whatever it says, so the first two bytes get eaten as an address that is then thrown
     * away, and everything after them lands two bytes early.
     */
    private fun programFile(text: String): IntArray = intArrayOf(0x01, 0x08) + basicPrint(text)

    /**
     * A drive on its own, with nothing asked of it.
     *
     * The interesting part of this is what is *not* happening. A 1541 that fails its power-on tests
     * does not sulk quietly: it blinks its LED for ever in a loop that never touches the serial bus,
     * and if the motor happened to be running when the test failed it goes on running. So a drive
     * that reads its whole track over and over looks busy and productive and is in fact dead, which
     * is exactly what an earlier version of this test asserted was working.
     */
    @Test
    fun `the drive powers up healthy and settles into its idle loop`() {
        val machine = machineWithDrive()
        assumeTrue("set BREADBIN_ROMS to a directory holding a C64 ROM set and a 1541 DOS", machine != null)
        val drive = machine!!.drive!!
        machine.insertDisk(disk())

        repeat(200) { machine.runFrame() }

        assertTrue("the drive's processor jammed", !drive.cpu.jammed)
        assertTrue("the drive never reached its DOS at %04X".format(drive.cpu.pc), drive.cpu.pc >= 0xC000)
        // An idle 1541 has stopped: motor off, LED off, and nowhere near the error blink.
        assertTrue("the disk is still turning with nothing to do", !drive.motorRunning)
        assertTrue("the LED is on, which a 1541 does to report a fault", !drive.ledOn)
        assertTrue(
            "the drive is in its power-on error loop at %04X".format(drive.cpu.pc),
            drive.cpu.pc !in 0xEA6E..0xEA9F,
        )
    }

    /** Two whole computers, three wires, and a file at the end of it. */
    @Test
    fun `a program loads off a real 1541`() {
        val machine = machineWithDrive()
        assumeTrue(machine != null)
        machine!!.insertDisk(disk())
        val drive = machine.drive!!

        repeat(200) { machine.runFrame() }
        machine.type("LOAD\"HELLO\",8\r")
        repeat(900) { machine.runFrame() }
        machine.type("RUN\r")
        repeat(200) { machine.runFrame() }

        val text = screen(machine)
        println(text.joinToString("\n") { "|$it" })
        assertTrue("the disk never loaded:\n" + text.joinToString("\n"),
            text.any { it.contains("OFF THE DISK") })
        // The bytes came off the surface rather than out of the image behind the drive's back.
        assertTrue("nothing was read off the disk", drive.bytesRead > 0)
    }

    @Test
    fun `the directory comes off the disk`() {
        val machine = machineWithDrive()
        assumeTrue(machine != null)
        machine!!.insertDisk(disk())

        repeat(200) { machine.runFrame() }
        machine.type("LOAD\"$\",8\r")
        repeat(900) { machine.runFrame() }
        machine.type("LIST\r")
        repeat(200) { machine.runFrame() }

        val text = screen(machine)
        println(text.joinToString("\n") { "|$it" })
        assertTrue("no directory:\n" + text.joinToString("\n"),
            text.any { it.contains("HELLO") } && text.any { it.contains("REAL DRIVE") })
    }

    private fun basicPrint(text: String): IntArray {
        val body = ArrayList<Int>()
        body += 0x99
        body += '"'.code
        for (character in text) body += Petscii.fromAscii(character)
        body += '"'.code

        val out = ArrayList<Int>()
        val nextLine = 0x0801 + 4 + body.size + 1
        out += nextLine and 0xFF
        out += (nextLine shr 8) and 0xFF
        out += 10
        out += 0
        out += body
        out += 0
        out += 0
        out += 0
        return out.toIntArray()
    }
}
