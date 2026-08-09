package be.valuya.breadbin.engine.disk

import be.valuya.breadbin.engine.Machine
import be.valuya.breadbin.engine.mem.Roms
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WireTrace {
    @Test fun trace() {
        assumeTrue(System.getenv("BREADBIN_TRACE") != null)
        val dir = File(System.getenv("BREADBIN_ROMS") ?: return)
        val f = dir.listFiles()!!.filter { it.isFile }
        val m = Machine(Roms.of(
            f.first { it.name.contains("basic") && it.length()==8192L }.readBytes(),
            f.first { it.name.contains("kernal") && it.length()==8192L }.readBytes(),
            f.first { it.name.contains("char") && it.length()==4096L }.readBytes()))
        val disk = D64.blank(Petscii.fromAscii("TEST"), Petscii.fromAscii("01"))
        disk.writeFile(Petscii.fromAscii("HELLO"), intArrayOf(0x01,0x08) + IntArray(20){0x20}, 2, false)
        m.insertDisk(disk)
        m.wire!!.onStep = { println("    step: $it") }
        repeat(200) { m.runFrame() }
        m.type("LOAD\"HELLO\",8\r")
        repeat(2) { m.runFrame() }
        val bus = m.serialBus
        var last = ""; var lines = 0; var quiet = 0
        outer@ for (frame in 0 until 300) {
            val until = m.cycles + m.model.cyclesPerFrame
            while (m.cycles < until) {
                m.cpu.step()
                val now = "${bus.atn}${bus.clock}${bus.data}${bus.deviceClock}${bus.deviceData}"
                if (now == last) { quiet++; continue }
                if (m.wire!!.bytesTransferred < 15) { last = now; continue }
                last = now; quiet = 0
                println("%9d atn=%s clk=%s dat=%s | dev drives clk=%s dat=%s | c64=%04X bytes=%d".format(
                    m.cycles, if (bus.atn) "L" else "-", if (bus.clock) "L" else "-", if (bus.data) "L" else "-",
                    bus.deviceClock, bus.deviceData, m.cpu.pc, m.wire!!.bytesTransferred))
                if (++lines > 45) break@outer
            }
            if (quiet > 30_000) {
                val seen = sortedMapOf<Int, Int>()
                repeat(4000) { m.cpu.step(); seen[m.cpu.pc] = (seen[m.cpu.pc] ?: 0) + 1 }
                println("loop: " + seen.entries.sortedByDescending { it.value }.take(14)
                    .joinToString(" ") { "%04X:%d".format(it.key, it.value) })
            }
            if (quiet > 300_000) { println("    zp: A3=%02X 90=%02X 94=%02X stack=%s".format(m.memory.peek(0xA3), m.memory.peek(0x90), m.memory.peek(0x94), (1..6).joinToString(" ") { "%02X".format(m.memory.peek(0x0100 + ((m.cpu.sp + it) and 0xFF))) }))
                println("--- still: c64=%04X bytes=%d atn=%s clk=%s dat=%s | computer pulls clk=%s dat=%s | dev pulls clk=%s dat=%s".format(m.cpu.pc, m.wire!!.bytesTransferred, bus.atn, bus.clock, bus.data, bus.computerClock, bus.computerData, bus.deviceClock, bus.deviceData)); break@outer }
        }
        println("end c64=%04X bytes=%d".format(m.cpu.pc, m.wire!!.bytesTransferred))
    }
}
