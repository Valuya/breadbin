package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.cia.JoystickPort
import be.valuya.breadbin.engine.disk.D64
import be.valuya.breadbin.engine.mem.Roms
import be.valuya.breadbin.engine.tape.Program
import be.valuya.breadbin.engine.tape.T64
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: runs a game and reports which joystick port it is actually reading.
 *
 * BREADBIN_ROMS and BREADBIN_GAME, as the others.
 */
class JoystickTrace {

    private fun roms(): Roms? {
        val directory = System.getenv("BREADBIN_ROMS")?.let(::File)?.takeIf { it.isDirectory }
            ?: return null
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
        val basic = files.firstOrNull { it.name.contains("basic", true) && it.length() == 8192L }
        val kernal = files.firstOrNull { it.name.contains("kernal", true) && it.length() == 8192L }
        val character = files.firstOrNull {
            (it.name.contains("char", true) || it.name.contains("font", true)) && it.length() == 4096L
        }
        if (basic == null || kernal == null || character == null) return null
        return Roms.of(basic.readBytes(), kernal.readBytes(), character.readBytes())
    }

    private fun screen(machine: Machine): String =
        (0 until 25).joinToString("\n") { row ->
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

    @Test
    fun `which port does it read`() {
        val roms = roms()
        val game = System.getenv("BREADBIN_GAME")?.let(::File)?.takeIf { it.isFile }
        assumeTrue(roms != null && game != null)

        val machine = Machine(roms!!)
        val raw = game!!.readBytes()
        val program = if (game.name.endsWith(".t64", true)) {
            T64.entries(raw).first()
        } else {
            val disk = D64(raw)
            machine.insertDisk(disk)
            val entry = disk.directory().first { it.blocks > 0 }
            val bytes = disk.readFile(entry)
            Program.fromPrg(ByteArray(bytes.size) { bytes[it].toByte() }, "game")
        }
        machine.enqueue(program, run = true)

        // Counting the reads is the direct answer: a game polling a port reads it every frame, and
        // a game ignoring one never touches it at all.
        var portA = 0
        var portB = 0
        machine.cia1.onRead = { register ->
            if (register == 0x00) portA++
            if (register == 0x01) portB++
        }

        // driveBusy keeps the app in turbo, and turbo throws every sample away. If it never settles
        // the game is silent for ever and nobody can tell why.
        val busy = StringBuilder()
        repeat(900) { frame ->
            machine.runFrame()
            if (frame % 50 == 0) busy.append(if (machine.driveBusy) 'B' else '.')
        }
        println("driveBusy per second: $busy")
        println("=== after ${900 / 50}s ===")
        println("reads of \$DC00 (port two): $portA")
        println("reads of \$DC01 (port one, and the keyboard): $portB")
        println(screen(machine).lines().filter { it.isNotBlank() }.joinToString("\n") { "  |$it" })

        for (port in listOf(JoystickPort.TWO, JoystickPort.ONE)) {
            val before = screen(machine)
            machine.keyboard.setJoystick(port, up = false, down = false, left = false, right = false, fire = true)
            repeat(150) { machine.runFrame() }
            machine.keyboard.setJoystick(port, up = false, down = false, left = false, right = false, fire = false)
            repeat(150) { machine.runFrame() }
            val after = screen(machine)
            println("=== fire on port $port: screen ${if (before == after) "did not change" else "CHANGED"} ===")
            if (before != after) {
                println(after.lines().filter { it.isNotBlank() }.take(6).joinToString("\n") { "  |$it" })
            }
        }
    }
}
