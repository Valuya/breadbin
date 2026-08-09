package be.valuya.breadbin.emu

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import be.valuya.breadbin.data.MediaItem
import be.valuya.breadbin.data.Settings
import be.valuya.breadbin.engine.Machine
import be.valuya.breadbin.engine.cart.Cartridge
import be.valuya.breadbin.engine.cia.C64Key
import be.valuya.breadbin.engine.cia.JoystickPort
import be.valuya.breadbin.engine.disk.D64
import be.valuya.breadbin.engine.mem.Roms
import be.valuya.breadbin.engine.tape.MediaKind
import be.valuya.breadbin.engine.tape.Program
import be.valuya.breadbin.engine.tape.T64
import be.valuya.breadbin.engine.tape.TapImage
import java.util.concurrent.locks.LockSupport

/**
 * A running machine, and the thread it runs on.
 *
 * The emulation runs on its own thread rather than on a frame callback, because a C64 frame is not
 * a display frame: PAL is 50.12Hz and a phone is 60Hz or 90Hz or whatever it feels like. Pacing
 * against the audio card instead of the display keeps the machine running at its own speed and the
 * sound free of gaps, and the picture is simply picked up whenever the screen next draws.
 */
class EmulatorSession(
    roms: Roms,
    settings: Settings,
    private val onDiskWritten: (ByteArray) -> Unit = {},
) {
    val machine = Machine(roms, settings.model, SAMPLE_RATE)

    private val width = settings.model.width
    private val height = settings.model.height

    /** The picture, updated by the emulation thread and drawn by the UI. */
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val bitmapLock = Any()

    /** Bumped once per emulated frame so that Compose knows to draw again. */
    var frameCount by mutableIntStateOf(0)
        private set

    var paused by mutableStateOf(false)
    var warp by mutableStateOf(false)
    var soundEnabled = settings.sound

    /** What is in the drive, if anything, so that writes can be saved back. */
    private var mountedDisk: D64? = null

    private var thread: Thread? = null

    @Volatile
    private var running = false

    private var audio: AudioTrack? = null
    private val audioBuffer = ShortArray(SAMPLE_RATE / 10)

    init {
        machine.onDiskChanged = { _, disk -> onDiskWritten(disk.data.copyOf()) }
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    fun start() {
        if (running) return
        running = true
        if (soundEnabled) openAudio()
        thread = Thread(::loop, "breadbin-emulation").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        closeAudio()
    }

    private fun openAudio() {
        val minimum = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Four frames of headroom: enough that a slow frame does not click, short enough that the
        // sound does not lag behind what is on screen.
        val size = maxOf(minimum, SAMPLE_RATE / 12 * 2)
        audio = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    private fun closeAudio() {
        audio?.runCatching {
            pause()
            flush()
            release()
        }
        audio = null
    }

    fun setSound(enabled: Boolean) {
        if (enabled == soundEnabled) return
        soundEnabled = enabled
        if (enabled) openAudio() else closeAudio()
    }

    private fun loop() {
        val frameNanos = (1_000_000_000.0 / machine.model.framesPerSecond).toLong()
        var nextFrame = System.nanoTime()
        while (running) {
            if (paused) {
                LockSupport.parkNanos(16_000_000)
                nextFrame = System.nanoTime()
                continue
            }

            machine.runFrame()
            publish()

            val track = audio
            if (warp || track == null) {
                // Nothing is pacing us, so throw the samples away rather than let them pile up.
                @Suppress("ControlFlowWithEmptyBody")
                while (machine.sid.readSamples(audioBuffer, audioBuffer.size) > 0) {
                }
                if (warp) continue
                nextFrame += frameNanos
                val wait = nextFrame - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait) else nextFrame = System.nanoTime()
            } else {
                // A blocking write is the clock: the audio device drains at exactly the sample
                // rate, so the machine ends up running at exactly its own speed.
                var count = machine.sid.readSamples(audioBuffer, audioBuffer.size)
                while (count > 0 && running) {
                    val written = track.write(audioBuffer, 0, count)
                    if (written <= 0) break
                    count = machine.sid.readSamples(audioBuffer, audioBuffer.size)
                }
            }
        }
    }

    private fun publish() {
        synchronized(bitmapLock) {
            bitmap.setPixels(machine.vic.frame, 0, width, 0, 0, width, height)
        }
        frameCount++
    }

    /** Held while the UI copies the picture, so that a frame is never drawn half-written. */
    fun <T> withPicture(block: (Bitmap) -> T): T = synchronized(bitmapLock) { block(bitmap) }

    // ---- input ---------------------------------------------------------------------------------

    fun press(key: C64Key) = machine.keyboard.press(key)

    fun release(key: C64Key) = machine.keyboard.release(key)

    fun releaseAllKeys() = machine.keyboard.releaseAll()

    fun restore(pressed: Boolean) = machine.restore(pressed)

    fun joystick(port: Int, up: Boolean, down: Boolean, left: Boolean, right: Boolean, fire: Boolean) {
        machine.keyboard.setJoystick(
            if (port == 1) JoystickPort.ONE else JoystickPort.TWO,
            up, down, left, right, fire,
        )
    }

    fun type(text: String) = machine.type(text)

    fun reset() = machine.reset()

    fun resetAndUnplug() {
        machine.insertCartridge(null)
        machine.insertDisk(null)
        machine.insertTape(null)
        mountedDisk = null
        machine.reset()
    }

    // ---- media ---------------------------------------------------------------------------------

    /** Puts something in the machine. Returns a note for the user, or null if all is well. */
    fun open(item: MediaItem, autostart: Boolean): String? {
        val bytes = runCatching { item.bytes }.getOrElse { return "Could not read ${item.title}" }
        return when (item.kind) {
            MediaKind.DISK -> runCatching {
                val disk = D64(bytes)
                mountedDisk = disk
                machine.insertDisk(disk)
                if (autostart) machine.autostartDisk()
                null
            }.getOrElse { "Could not read the disk image" }

            MediaKind.TAPE -> runCatching {
                machine.insertTape(TapImage.parse(bytes, item.title))
                if (autostart) machine.type("LOAD\r")
                null
            }.getOrElse { "Could not read the tape image" }

            MediaKind.CARTRIDGE -> runCatching {
                machine.insertCartridge(Cartridge.of(bytes))
                null
            }.getOrElse { "Could not read the cartridge" }

            MediaKind.PROGRAM -> runCatching {
                // The program goes in either way; the setting only decides whether it is started.
                machine.enqueue(Program.of(bytes, item.title), run = autostart)
                null
            }.getOrElse { "Could not read the program" }

            MediaKind.ARCHIVE -> {
                val entries = T64.entries(bytes)
                if (entries.isEmpty()) {
                    "That archive is empty"
                } else {
                    machine.enqueue(entries.first(), run = autostart)
                    null
                }
            }

            MediaKind.UNKNOWN -> "Breadbin does not know what ${item.title} is"
        }
    }

    /** The programs inside a .t64, so that the user can pick one. */
    fun archiveEntries(item: MediaItem): List<Program> =
        runCatching { T64.entries(item.bytes) }.getOrDefault(emptyList())

    fun run(program: Program, autostart: Boolean = true) = machine.enqueue(program, run = autostart)

    val tape get() = machine.datasette

    fun playTape() = machine.pressPlay()

    fun stopTape() = machine.pressStop()

    fun rewindTape() = machine.rewindTape()

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
