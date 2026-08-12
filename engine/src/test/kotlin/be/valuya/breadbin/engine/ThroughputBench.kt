package be.valuya.breadbin.engine

import be.valuya.breadbin.engine.mem.Roms
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a test: how many seconds of Commodore 64 this manages per second of wall clock.
 *
 * This is the number that decides how long a load feels, and nothing else here measures it. A
 * machine running at one megahertz on a processor running at three thousand should be a long way
 * above real time; how far above is the question, and where the time goes is the next one.
 */
class ThroughputBench {

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

    private fun measure(label: String, frames: Int, machine: Machine) {
        repeat(frames / 4) { machine.runFrame() } // let the JIT settle
        val started = System.nanoTime()
        repeat(frames) { machine.runFrame() }
        val elapsed = (System.nanoTime() - started) / 1e9
        val machineSeconds = frames / 50.0
        println("%-28s %6.2fx real time  (%.1fs of machine in %.2fs)".format(
            label, machineSeconds / elapsed, machineSeconds, elapsed,
        ))
    }

    @Test
    fun `how fast does it run`() {
        val roms = roms()
        assumeTrue(roms != null)
        measure("whole machine", 2000, Machine(roms!!))
    }

    /**
     * A sampling profiler, because guessing at this has not worked.
     *
     * A second thread takes the emulation thread's stack every so often and counts what is on top
     * of it. That is what a profiler does, and doing it here rather than reasoning about the code
     * means the answer comes from the program actually running.
     */
    @Test
    fun `what is it actually doing`() {
        val roms = roms()
        assumeTrue(roms != null)
        val machine = Machine(roms!!)
        repeat(500) { machine.runFrame() }

        val counts = HashMap<String, Int>()
        val worker = Thread { repeat(6000) { machine.runFrame() } }
        worker.start()
        while (worker.isAlive) {
            val stack = worker.stackTrace
            // The innermost frame that is ours: below that is the JDK, above it the callers.
            stack.firstOrNull { it.className.startsWith("be.valuya") }?.let {
                val name = it.className.substringAfterLast('.') + "." + it.methodName
                counts[name] = (counts[name] ?: 0) + 1
            }
            Thread.sleep(0, 200_000)
        }
        worker.join()

        val total = counts.values.sum().coerceAtLeast(1)
        println("=== where the time goes, $total samples ===")
        counts.entries.sortedByDescending { it.value }.take(14).forEach { (name, n) ->
            println("%-40s %5.1f%%  %s".format(name, 100.0 * n / total, "#".repeat(n * 40 / total)))
        }
    }

    /** Each chip on its own, clocked the number of times a second of machine time would. */
    @Test
    fun `where the cycles go`() {
        val roms = roms()
        assumeTrue(roms != null)
        val cycles = 985_248

        fun time(label: String, work: () -> Unit) {
            repeat(cycles / 4) { work() }
            val started = System.nanoTime()
            repeat(cycles) { work() }
            val elapsed = (System.nanoTime() - started) / 1e9
            println("%-28s %7.3fs per second of machine".format(label, elapsed))
        }

        val machine = Machine(roms!!)
        repeat(200) { machine.runFrame() }

        val sid = be.valuya.breadbin.engine.sid.Sid(985_248, 48_000)
        sid.write(0x18, 0x0F)
        sid.write(0x04, 0x21)
        time("SID alone") { sid.clock() }

        val vic = machine.vic
        time("VIC alone") { vic.cycle() }

        val cia = machine.cia1
        time("one CIA alone") { cia.cycle() }

        time("CPU alone") { machine.cpu.step() }
    }
}
