package be.valuya.breadbin.engine.drive

import be.valuya.breadbin.engine.Machine
import be.valuya.breadbin.engine.disk.D64
import be.valuya.breadbin.engine.disk.Petscii
import be.valuya.breadbin.engine.mem.Roms
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A logic analyser across the three wires, run by hand rather than asserted on.
 *
 * Watching the bus alone never settled which end was wrong, because a deadlock looks the same from
 * either side. This prints who was executing what at each transition, with the drive's routines
 * named from the DOS listing, so that "the drive stopped" becomes "the drive is at acp00a waiting
 * for clock".
 */
class BusTrace {

    /**
     * Routine names, read from a `dos.lbl` sitting beside the ROMs if there is one — the DOS source
     * reconstruction emits one — and otherwise the handful that matter most.
     */
    private val labels: java.util.SortedMap<Int, String> = readLabels() ?: sortedMapOf(
        0xE853 to "atnirq", 0xE85B to "atnsrv", 0xE87B to "atns15", 0xE884 to "atn30",
        0xE8D2 to "atn95", 0xE8D7 to "atns20", 0xE909 to "talk", 0xE90F to "talk1",
        0xE916 to "tlk05", 0xE925 to "talk2", 0xE94B to "noeoi", 0xE95C to "isr01",
        0xE963 to "isr02", 0xE987 to "isr04", 0xE99C to "dathi", 0xE9A5 to "datlow",
        0xE9AE to "clklow", 0xE9B7 to "clkhi", 0xE9C0 to "debnc", 0xE9C9 to "acptr",
        0xE9CD to "acp00a", 0xE9DF to "acp00", 0xEA0B to "acp03", 0xEA1A to "acp03a",
        0xEA2E to "listen", 0xEA4E to "ilerr", 0xEA59 to "tstatn", 0xEBE7 to "idle",
    )

    private fun readLabels(): java.util.SortedMap<Int, String>? {
        val file = System.getenv("BREADBIN_ROMS")?.let { File(it, "dos.lbl") }?.takeIf { it.isFile }
            ?: return null
        val found = sortedMapOf<Int, String>()
        val pattern = Regex("""al 0*([0-9A-Fa-f]+) \.(\S+)""")
        for (line in file.readLines()) {
            val match = pattern.find(line.trim()) ?: continue
            val address = match.groupValues[1].toInt(16)
            if (address >= 0xC000) found.putIfAbsent(address, match.groupValues[2])
        }
        return found.takeIf { it.isNotEmpty() }
    }

    private fun name(pc: Int): String {
        val entry = labels.headMap(pc + 1).entries.lastOrNull() ?: return "%04X".format(pc)
        return if (pc - entry.key > 64) "%04X".format(pc) else "${entry.value}+${pc - entry.key}"
    }

    private fun machineWithDrive(): Machine? {
        val directory = System.getenv("BREADBIN_ROMS")?.let(::File)?.takeIf { it.isDirectory } ?: return null
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
        val basic = files.firstOrNull { it.name.contains("basic", true) && it.length() == 8192L } ?: return null
        val kernal = files.firstOrNull { it.name.contains("kernal", true) && it.length() == 8192L } ?: return null
        val character = files.firstOrNull {
            (it.name.contains("char", true) || it.name.contains("font", true)) && it.length() == 4096L
        } ?: return null
        val dos = files.firstOrNull { it.length() == 16384L } ?: return null
        val bytes = dos.readBytes()
        return Machine(
            Roms.of(basic.readBytes(), kernal.readBytes(), character.readBytes()),
            driveRom = IntArray(bytes.size) { bytes[it].toInt() and 0xFF },
        )
    }

    @Test
    fun `trace the bus through a load`() {
        val machine = machineWithDrive()
        assumeTrue("set BREADBIN_TRACE to run this", System.getenv("BREADBIN_TRACE") != null)
        assumeTrue("set BREADBIN_ROMS to a directory with a C64 ROM set and a 1541 DOS", machine != null)
        val bus = machine!!.serialBus
        val drive = machine.drive!!

        val disk = D64.blank(Petscii.fromAscii("REAL DRIVE"), Petscii.fromAscii("01"))
        disk.writeFile(Petscii.fromAscii("HELLO"), IntArray(16) { 0x20 }, 2, replace = false)
        machine.insertDisk(disk)

        repeat(200) { machine.runFrame() }
        machine.type("LOAD\"HELLO\",8\r")
        repeat(2) { machine.runFrame() }

        var last = ""
        var lines = 0
        var quiet = 0
        val cyclesPerFrame = machine.model.cyclesPerFrame
        outer@ for (frame in 0 until 400) {
            val until = machine.cycles + cyclesPerFrame
            while (machine.cycles < until) {
                machine.cpu.step()
                val now = "${bus.atn}${bus.clock}${bus.data}"
                if (now == last) {
                    quiet++
                    continue
                }
                last = now
                quiet = 0
                println(
                    "%9d atn=%s clk=%s dat=%s  c64=%04X  drive=%-14s".format(
                        machine.cycles,
                        if (bus.atn) "L" else "-",
                        if (bus.clock) "L" else "-",
                        if (bus.data) "L" else "-",
                        machine.cpu.pc,
                        name(drive.cpu.pc),
                    )
                )
                if (++lines > 220) break@outer
            }
            if (quiet > 400_000) {
                println("--- still for $quiet cycles: c64=%04X drive=%s".format(machine.cpu.pc, name(drive.cpu.pc)))
                break@outer
            }
        }
        println("ended: c64=%04X drive=%s".format(machine.cpu.pc, name(drive.cpu.pc)))
    }
}
