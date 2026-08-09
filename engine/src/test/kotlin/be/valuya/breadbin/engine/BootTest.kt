package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.disk.D64
import be.valuya.breadbin.engine.disk.Petscii
import be.valuya.breadbin.engine.mem.Roms
import be.valuya.breadbin.engine.tape.Program
import be.valuya.breadbin.engine.tape.TapImage
import be.valuya.breadbin.engine.tape.TapWriter
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Switches a whole machine on and reads the screen.
 *
 * This is the test that answers "does it work" rather than "is this part right", and it needs a
 * real ROM set to do it, which cannot live in this repository. Point `BREADBIN_ROMS` at a directory
 * holding a BASIC, a KERNAL and a character ROM and it runs; without one it is skipped.
 *
 * A free set that works for this is the MEGA65 project's Open ROMs, which are not Commodore's and
 * can be downloaded without any of the usual awkwardness.
 */
class BootTest {

    private fun roms(): Roms? {
        val directory = System.getenv("BREADBIN_ROMS")?.let(::File) ?: return null
        if (!directory.isDirectory) return null
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
        val basic = files.firstOrNull { it.name.contains("basic", true) && it.length() == 8192L }
        val kernal = files.firstOrNull { it.name.contains("kernal", true) && it.length() == 8192L }
        val character = files.firstOrNull {
            (it.name.contains("char", true) || it.name.contains("font", true)) && it.length() == 4096L
        }
        if (basic == null || kernal == null || character == null) return null
        return Roms.of(basic.readBytes(), kernal.readBytes(), character.readBytes())
    }

    /** The forty by twenty-five characters of screen memory, as text. */
    private fun screen(machine: Machine): List<String> =
        (0 until 25).map { row ->
            (0 until 40).joinToString("") { column ->
                fromScreenCode(machine.memory.peek(0x0400 + row * 40 + column))
            }.trimEnd()
        }

    private fun fromScreenCode(code: Int): String = when (code and 0x7F) {
        0 -> "@"
        in 1..26 -> ('A' + (code and 0x7F) - 1).toString()
        in 32..63 -> (code and 0x7F).toChar().toString()
        else -> " "
    }

    @Test
    fun `the machine boots to a prompt`() {
        val roms = roms()
        assumeTrue("set BREADBIN_ROMS to a directory holding a ROM set to run this", roms != null)
        val machine = Machine(roms!!)

        repeat(200) { machine.runFrame() }
        val text = screen(machine)
        println(text.joinToString("\n") { "|$it" })

        assertTrue(
            "the screen is blank after two hundred frames",
            text.any { it.isNotBlank() },
        )
        // Every C64 BASIC says this before it will take a command, whoever wrote the ROM.
        assertTrue(
            "no READY prompt:\n${text.joinToString("\n")}",
            text.any { it.contains("READY") },
        )
    }

    @Test
    fun `a BASIC program typed at the prompt runs`() {
        val roms = roms()
        assumeTrue(roms != null)
        val machine = Machine(roms!!)

        repeat(200) { machine.runFrame() }
        machine.type("PRINT\"HELLO WORLD\"\r")
        repeat(200) { machine.runFrame() }

        val text = screen(machine)
        println(text.joinToString("\n") { "|$it" })
        assertTrue("the machine never printed it:\n${text.joinToString("\n")}",
            text.any { it.contains("HELLO WORLD") })
    }

    @Test
    fun `a program loads off a disk through the drive`() {
        val roms = roms()
        assumeTrue(roms != null)
        val machine = Machine(roms!!)
        assumeTrue("this KERNAL cannot be patched for the drive", machine.virtualDriveAvailable)

        // A BASIC program that prints something recognisable: 10 PRINT"IT LOADED"
        val program = basicPrint("IT LOADED")
        val disk = D64.blank(Petscii.fromAscii("TEST"), Petscii.fromAscii("01"))
        disk.writeFile(Petscii.fromAscii("HELLO"), program, fileType = 2, replace = false)
        machine.insertDisk(disk)

        repeat(200) { machine.runFrame() }
        machine.type("LOAD\"HELLO\",8\r")
        repeat(300) { machine.runFrame() }

        // A KERNAL that drives the serial lines itself never calls the routines the drive is
        // patched into, and no amount of emulating a 1541 above the KERNAL will help it.
        assumeTrue(
            "this KERNAL drives the serial lines itself instead of going through its own routines, " +
                "so an emulated drive that sits above the KERNAL cannot serve it",
            machine.iec.bytesServed > 0,
        )

        machine.type("RUN\r")
        repeat(200) { machine.runFrame() }

        val text = screen(machine)
        println(text.joinToString("\n") { "|$it" })
        assertTrue("the program never loaded:\n${text.joinToString("\n")}",
            text.any { it.contains("IT LOADED") })
    }

    @Test
    fun `a program loads off a tape, pulse by pulse`() {
        val roms = roms()
        assumeTrue(roms != null)
        val machine = Machine(roms!!)

        val program = Program(0x0801, basicPrint("OFF THE TAPE"), "HELLO")
        machine.insertTape(TapImage.parse(TapWriter.write(program, "HELLO")))

        repeat(200) { machine.runFrame() }
        machine.type("LOAD\"HELLO\",1\r")
        repeat(30) { machine.runFrame() }
        machine.pressPlay()
        // Long enough for the leader, both copies of the header and both of the data.
        repeat(4000) { machine.runFrame() }
        machine.type("RUN\r")
        repeat(200) { machine.runFrame() }

        val text = screen(machine)
        println(text.joinToString("\n") { "|$it" })
        assertTrue(
            "the tape never loaded:\n" + text.joinToString("\n"),
            text.any { it.contains("OFF THE TAPE") },
        )
    }

    /** `10 PRINT"<text>"`, tokenised, as it would sit in memory from $0801. */
    private fun basicPrint(text: String): IntArray {
        val body = ArrayList<Int>()
        body += 0x99 // the token for PRINT
        body += '"'.code
        for (character in text) body += Petscii.fromAscii(character)
        body += '"'.code

        val out = ArrayList<Int>()
        val lineLength = 4 + body.size + 1
        val nextLine = 0x0801 + lineLength
        out += nextLine and 0xFF
        out += (nextLine shr 8) and 0xFF
        out += 10 // the line number
        out += 0
        out += body
        out += 0    // end of the line
        out += 0    // no next line
        out += 0
        return out.toIntArray()
    }
}
