package be.valuya.breadbin.engine.probe

import be.valuya.breadbin.engine.Machine
import be.valuya.breadbin.engine.mem.Roms
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SpeedProbe {
    @Test fun howFast() {
        assumeTrue(System.getenv("BREADBIN_SPEED") != null)
        val dir = File(System.getenv("BREADBIN_ROMS") ?: return)
        val f = dir.listFiles()!!.filter { it.isFile }
        val m = Machine(Roms.of(
            f.first { it.name.contains("basic") && it.length() == 8192L }.readBytes(),
            f.first { it.name.contains("kernal") && it.length() == 8192L }.readBytes(),
            f.first { it.name.contains("char") && it.length() == 4096L }.readBytes()))
        repeat(200) { m.runFrame() }
        val frames = 3000
        val start = System.nanoTime()
        repeat(frames) { m.runFrame() }
        val seconds = (System.nanoTime() - start) / 1e9
        println("SPEED: %d frames in %.2fs = %.0f fps = %.1fx real time".format(
            frames, seconds, frames / seconds, frames / seconds / 50.125))
    }
}
