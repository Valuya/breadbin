package be.valuya.breadbin.engine.sid

import be.valuya.breadbin.engine.Machine
import be.valuya.breadbin.engine.disk.D64
import be.valuya.breadbin.engine.mem.Roms
import be.valuya.breadbin.engine.tape.T64
import be.valuya.breadbin.engine.tape.Program
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: a listening post. Runs a real game and prints what it asks the sound chip for.
 *
 * Point BREADBIN_ROMS at a ROM set and BREADBIN_GAME at a .d64 to run it.
 */
class SidTraceTest {

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

    @Test
    fun `what the game asks the chip for`() {
        val roms = roms()
        val game = System.getenv("BREADBIN_GAME")?.let(::File)?.takeIf { it.isFile }
        assumeTrue(roms != null && game != null)

        val machine = Machine(roms!!)
        val raw = game!!.readBytes()

        // Straight into memory rather than through the loader: what is under examination is what
        // the program writes to $D400, not how it got there.
        val program = if (game.name.endsWith(".t64", true)) {
            T64.entries(raw).first()
        } else {
            val disk = D64(raw)
            machine.insertDisk(disk)
            val entry = disk.directory().first { it.blocks > 0 }
            val bytes = disk.readFile(entry)
            Program.fromPrg(ByteArray(bytes.size) { bytes[it].toByte() }, "game")
        }
        println("loaded ${program.name} at $%04X".format(program.loadAddress) + " (${program.data.size} bytes)")

        val writes = IntArray(0x20)
        val distinct = Array(0x20) { HashSet<Int>() }
        var frames = 0
        machine.sid.monitor = { register, value ->
            writes[register]++
            if (distinct[register].size < 40) distinct[register] += value
        }
        machine.enqueue(program, run = true)

        // What actually reaches the speaker, measured in one-second windows so a tune that starts
        // late is not averaged away by the silence before it.
        val buffer = ShortArray(4096)
        val perSecond = ArrayList<Int>()
        var countedSoFar = 0
        var windowSquares = 0.0
        var windowCount = 0
        var windowPeak = 0
        val seconds = ArrayList<Triple<Int, Double, Int>>()
        while (frames++ < 1200) {
            machine.runFrame()
            while (true) {
                val got = machine.sid.readSamples(buffer, buffer.size)
                if (got == 0) break
                for (i in 0 until got) {
                    val s = buffer[i].toInt()
                    windowSquares += s.toDouble() * s
                    windowCount++
                    if (kotlin.math.abs(s) > windowPeak) windowPeak = kotlin.math.abs(s)
                }
            }
            if (frames % 50 == 0 && windowCount > 0) {
                seconds += Triple(frames / 50, Math.sqrt(windowSquares / windowCount), windowPeak)
                windowSquares = 0.0; windowCount = 0; windowPeak = 0
                perSecond += writes.sum() - countedSoFar
                countedSoFar = writes.sum()
            }
        }
        println("=== output, per second: rms and peak out of 32767 ===")
        for ((second, rms, peak) in seconds) {
            val bar = "#".repeat((peak / 1000).coerceAtMost(33))
            val registerWrites = perSecond.getOrElse(second - 1) { 0 }
            println("%3ds  %5d writes  rms %8.1f  peak %6d  %s".format(second, registerWrites, rms, peak, bar))
        }

        val shortcut = machine.kernalInternalJump
        println(
            if (shortcut == null) "no jump into the KERNAL's private half"
            else "jumped into the KERNAL at $%04X — this game wants Commodore's ROMs".format(shortcut)
        )

        println("=== SID writes over $frames frames (${frames / 50} seconds of machine time) ===")
        for (register in 0 until 0x20) {
            if (writes[register] == 0) continue
            val name = NAMES[register] ?: "\$D4%02X".format(register)
            val values = distinct[register].sorted().joinToString(" ") { "%02X".format(it) }
            println("%-22s %8d writes   %s".format(name, writes[register], values))
        }
    }

    private companion object {
        val NAMES = mapOf(
            0x04 to "voice1 control", 0x05 to "voice1 AD", 0x06 to "voice1 SR",
            0x0B to "voice2 control", 0x0C to "voice2 AD", 0x0D to "voice2 SR",
            0x12 to "voice3 control", 0x13 to "voice3 AD", 0x14 to "voice3 SR",
            0x15 to "cutoff low", 0x16 to "cutoff high",
            0x17 to "resonance/routing", 0x18 to "mode/volume",
        )
    }
}
