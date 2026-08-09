package be.valuya.breadbin.engine.tape

/**
 * The 1530 Datasette, playing a .tap file.
 *
 * A TAP file is not a recording of data — it is a recording of the *timing* of the tape signal, a
 * list of the gaps between one falling edge and the next. Replaying it means putting those edges
 * back on CIA 1's FLAG line at the right moments and letting whatever loader is running work out
 * what they mean. That is why turbo loaders work here at all: this emulates the tape, not the
 * KERNAL's idea of what is on it.
 */
class Datasette {

    /** Pulse lengths in system cycles. */
    private var pulses = IntArray(0)
    private var index = 0
    private var countdown = 0

    /** True while a key is held down on the deck, which the machine reads as the sense line. */
    var playing = false
        private set

    /** The machine drives this from bit 5 of the processor port; the motor only runs when it is on. */
    var motorOn = false

    /** Raised for every falling edge of the tape signal. */
    var onPulse: (() -> Unit)? = null

    var name: String = ""
        private set

    val loaded get() = pulses.isNotEmpty()

    /** How far through the tape playback is, from 0 to 1. */
    val position: Double get() = if (pulses.isEmpty()) 0.0 else index.toDouble() / pulses.size

    fun load(tap: TapImage) {
        pulses = tap.pulses
        name = tap.name
        index = 0
        countdown = 0
        playing = false
    }

    fun eject() {
        pulses = IntArray(0)
        index = 0
        playing = false
    }

    fun play() {
        if (pulses.isEmpty()) return
        playing = true
        if (countdown == 0) countdown = pulses.getOrElse(index) { 0 }
    }

    fun stop() {
        playing = false
    }

    fun rewind() {
        index = 0
        countdown = 0
    }

    fun reset() {
        playing = false
        motorOn = false
        index = 0
        countdown = 0
    }

    /** One system cycle. */
    fun cycle() {
        if (!playing || !motorOn || index >= pulses.size) return
        if (countdown > 0 && --countdown > 0) return
        onPulse?.invoke()
        index++
        countdown = if (index < pulses.size) pulses[index] else 0
        if (index >= pulses.size) playing = false
    }
}

/**
 * A parsed .tap file.
 *
 * Version 0 stores every pulse as a single byte of eight cycles each and has no way to express a
 * gap longer than about two milliseconds, so a zero means "longer than that" and the exact length
 * is lost. Version 1 spells the long ones out in three further bytes, which is why version 1 files
 * load things version 0 files cannot.
 */
class TapImage(val version: Int, val pulses: IntArray, val name: String) {
    companion object {
        private const val SIGNATURE = "C64-TAPE-RAW"

        fun isTap(bytes: ByteArray): Boolean =
            bytes.size > 20 && String(bytes, 0, 12, Charsets.US_ASCII) == SIGNATURE

        fun parse(bytes: ByteArray, name: String = ""): TapImage {
            require(isTap(bytes)) { "not a .tap file" }
            val version = bytes[12].toInt() and 0xFF
            val declared = (bytes[16].toInt() and 0xFF) or
                ((bytes[17].toInt() and 0xFF) shl 8) or
                ((bytes[18].toInt() and 0xFF) shl 16) or
                ((bytes[19].toInt() and 0xFF) shl 24)
            val end = if (declared > 0) minOf(20 + declared, bytes.size) else bytes.size

            val pulses = ArrayList<Int>((end - 20).coerceAtLeast(0))
            var at = 20
            while (at < end) {
                val value = bytes[at].toInt() and 0xFF
                at++
                if (value != 0) {
                    pulses += value * 8
                    continue
                }
                if (version == 0) {
                    // The length is not recorded; the longest a byte could have expressed is the
                    // closest thing to the truth available.
                    pulses += 256 * 8
                    continue
                }
                if (at + 3 > end) break
                pulses += (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16)
                at += 3
            }
            return TapImage(version, pulses.toIntArray(), name)
        }
    }
}
