package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.mem.Roms
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: boots every combination of the ROM sets on hand and prints what lands on the screen.
 *
 * BREADBIN_OPENROMS and BREADBIN_COMMODORE point at two directories of ROMs.
 */
class MixedRomTrace {

    private fun pick(directory: File, needle: String, size: Long): File? =
        directory.listFiles()?.firstOrNull {
            it.isFile && it.length() == size && it.name.contains(needle, true)
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

    @Test
    fun `every mix of the two sets`() {
        val open = System.getenv("BREADBIN_OPENROMS")?.let(::File)?.takeIf { it.isDirectory }
        val commodore = System.getenv("BREADBIN_COMMODORE")?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue(open != null && commodore != null)

        val basics = listOfNotNull(
            pick(open!!, "basic", 8192)?.let { "open" to it },
            pick(commodore!!, "basic", 8192)?.let { "commodore" to it },
        )
        val kernals = listOfNotNull(
            pick(open, "kernal", 8192)?.let { "open" to it },
            pick(commodore!!, "kernal", 8192)?.let { "commodore" to it },
        )
        val character = pick(open, "char", 4096) ?: pick(open, "font", 4096)!!

        for ((basicName, basicFile) in basics) {
            for ((kernalName, kernalFile) in kernals) {
                val roms = Roms.of(basicFile.readBytes(), kernalFile.readBytes(), character.readBytes())
                val machine = Machine(roms)
                repeat(500) { machine.runFrame() }
                val text = screen(machine).filter { it.isNotBlank() }
                println("=== BASIC $basicName + KERNAL $kernalName ===")
                if (text.isEmpty()) println("    (nothing on screen at all)")
                text.take(6).forEach { println("    |$it") }
            }
        }
    }
}
