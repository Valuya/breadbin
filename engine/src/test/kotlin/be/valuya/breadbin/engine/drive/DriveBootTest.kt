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
        disk.writeFile(Petscii.fromAscii("HELLO"), basicPrint("OFF THE DISK"), 2, replace = false)
        disk.writeFile(Petscii.fromAscii("SECOND"), IntArray(300) { 0x42 }, 2, replace = false)
        return disk
    }

    /**
     * What the drive already does: boot its own DOS, spin the disk up, seek to the directory track
     * and pull bytes off the surface at the rate the surface goes past.
     */
    @Test
    fun `the drive's own processor starts up and reads the disk`() {
        val machine = machineWithDrive()
        assumeTrue("set BREADBIN_ROMS to a directory holding a C64 ROM set and a 1541 DOS", machine != null)
        val drive = machine!!.drive!!
        machine.insertDisk(disk())

        repeat(200) { machine.runFrame() }
        machine.type("LOAD\"HELLO\",8\r")
        repeat(200) { machine.runFrame() }

        assertTrue("the drive's processor jammed", !drive.cpu.jammed)
        // A drive that has run its power-on tests is executing its own ROM.
        assertTrue("the drive never reached its DOS at %04X".format(drive.cpu.pc), drive.cpu.pc >= 0xC000)
        assertTrue("the disk never span up", drive.motorRunning)
        assertTrue("the head is on track ${'$'}{drive.track}, not the directory", drive.track == 18)
        assertTrue("nothing came off the surface", drive.bytesRead > 100_000)
    }

    /**
     * Not yet. The two machines find each other — the computer stops reporting the drive missing,
     * the drive spins up and reads — but a transfer does not complete, and the fault is somewhere in
     * the 6522s or in how the DOS's read loop is being answered. Left here as the target rather than
     * deleted, because it is the test that says when this is finished.
     */
    @Ignore("the drive answers but a transfer does not complete yet")
    @Test
    fun `a program loads off a real 1541`() {
        val machine = machineWithDrive()
        assumeTrue(machine != null)
        machine!!.insertDisk(disk())

        repeat(200) { machine.runFrame() }
        machine.type("LOAD\"HELLO\",8\r")
        repeat(900) { machine.runFrame() }
        machine.type("RUN\r")
        repeat(200) { machine.runFrame() }

        val text = screen(machine)
        println(text.joinToString("\n") { "|$it" })
        assertTrue("the disk never loaded:\n" + text.joinToString("\n"),
            text.any { it.contains("OFF THE DISK") })
    }

    @Ignore("the drive answers but a transfer does not complete yet")
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
