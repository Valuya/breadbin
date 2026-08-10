package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.cia.C64Key
import be.valuya.breadbin.engine.cia.JoystickPort
import be.valuya.breadbin.engine.disk.D64
import be.valuya.breadbin.engine.mem.Roms
import be.valuya.breadbin.engine.tape.Program
import be.valuya.breadbin.engine.tape.T64
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: runs a game and reports what it sees when the joystick moves.
 *
 * BREADBIN_ROMS and BREADBIN_GAME, as the others. BREADBIN_VIA_DRIVE loads through the drive rather
 * than injecting, which is what the app does and takes minutes of machine time.
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
        }.lines().filter { it.isNotBlank() }.joinToString("\n") { "  |$it" }

    @Test
    fun `what the game sees when the stick moves`() {
        val roms = roms()
        val game = System.getenv("BREADBIN_GAME")?.let(::File)?.takeIf { it.isFile }
        assumeTrue(roms != null && game != null)

        val machine = Machine(roms!!)
        val raw = game!!.readBytes()
        val viaDrive = System.getenv("BREADBIN_VIA_DRIVE") != null
        val program = if (game.name.endsWith(".t64", true)) {
            T64.entries(raw).first()
        } else {
            val disk = D64(raw)
            machine.insertDisk(disk)
            val entry = disk.directory().first { it.blocks > 0 }
            Program.fromPrg(disk.readFile(entry).let { b -> ByteArray(b.size) { b[it].toByte() } }, "game")
        }
        if (viaDrive) machine.autostartDisk() else machine.enqueue(program, run = true)

        // Whether the app would be running flat out. Turbo is what makes a load bearable, and it is
        // driven entirely by this, so a load that is slow is a load where this is false.
        val busy = StringBuilder()
        val settle = System.getenv("BREADBIN_FRAMES")?.toIntOrNull() ?: 1000
        repeat(settle) { frame ->
            machine.runFrame()
            if (frame % 50 == 0) busy.append(if (machine.driveBusy) 'B' else '.')
            if (frame % 1000 == 999) {
                println("--- at ${(frame + 1) / 50}s, driveBusy=${machine.driveBusy} ---")
                println(screen(machine).lines().take(4).joinToString("\n"))
            }
        }
        println("driveBusy, one character per second: $busy")
        println("=== screen after ${settle / 50}s ===")
        println(screen(machine))

        // Past the menu, which wants a key rather than the stick.
        machine.keyboard.press(C64Key.F1)
        repeat(10) { machine.runFrame() }
        machine.keyboard.release(C64Key.F1)
        repeat(200) { machine.runFrame() }
        println("=== after F1 ===")
        println(screen(machine))

        for (port in listOf(JoystickPort.TWO, JoystickPort.ONE)) {
            val before = screen(machine)
            // What the program actually reads while the button is held. If the bit never goes low
            // the joystick is not reaching it; if it does and nothing happens, it is looking
            // somewhere else entirely.
            val seenA = LinkedHashSet<Int>()
            val seenB = LinkedHashSet<Int>()
            machine.cia1.onReadValue = { register, value ->
                if (register == 0x00 && seenA.size < 8) seenA += value
                if (register == 0x01 && seenB.size < 8) seenB += value
            }
            machine.keyboard.setJoystick(port, up = false, down = false, left = false, right = false, fire = true)
            repeat(300) { machine.runFrame() }
            machine.keyboard.setJoystick(port, up = false, down = false, left = false, right = false, fire = false)
            machine.cia1.onReadValue = null
            repeat(100) { machine.runFrame() }

            println("=== fire held on port $port ===")
            println("  \$DC00 read as: " + seenA.joinToString(" ") { "%02X".format(it) })
            println("  \$DC01 read as: " + seenB.joinToString(" ") { "%02X".format(it) })
            println("  screen " + if (before == screen(machine)) "did not change" else "CHANGED")
            if (before != screen(machine)) println(screen(machine))
        }
    }
}
