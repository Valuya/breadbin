package be.valuya.breadbin.engine.sid

/**
 * The MOS 6581 sound chip: three oscillators, an envelope on each, and one analogue filter they
 * can be routed through.
 *
 * The oscillators and envelopes are modelled the way the chip works — a 24-bit phase accumulator
 * per voice, a 23-bit noise shift register clocked off bit 19 of it, and an envelope counter with
 * the chip's own rate table and its exponential decay — because music routines drive them hard
 * enough that approximating any of it is audible. The filter is a two-integrator state variable
 * design rather than a model of the real analogue one, which is the honest limit here: filter
 * sweeps sound right, but they do not sound like your particular 6581.
 *
 * Everything runs at the system clock and is decimated to the output rate at the end.
 */
class Sid(private val clockHz: Int, private val sampleRate: Int) {

    private val voices = Array(3) { Voice() }

    private var filterCutoff = 0
    private var filterResonance = 0
    private var filterRouting = 0
    private var filterMode = 0
    private var volume = 15
    private var voice3Off = false

    private var lastWrite = 0

    // state variable filter
    private var lowPass = 0.0
    private var bandPass = 0.0

    private var cyclesPerSample = clockHz.toDouble() / sampleRate
    private var sampleAccumulator = 0.0
    private var sampleSum = 0.0
    private var sampleCount = 0

    private val output = ShortArray(OUTPUT_BUFFER)
    private var writeIndex = 0
    private var readIndex = 0

    fun reset() {
        for (voice in voices) voice.reset()
        filterCutoff = 0
        filterResonance = 0
        filterRouting = 0
        filterMode = 0
        volume = 15
        voice3Off = false
        lowPass = 0.0
        bandPass = 0.0
        writeIndex = 0
        readIndex = 0
        sampleAccumulator = 0.0
        sampleSum = 0.0
        sampleCount = 0
    }

    fun read(register: Int): Int = when (register and 0x1F) {
        0x19, 0x1A -> 0xFF // the paddle inputs, with nothing plugged into them
        0x1B -> (voices[2].waveformOutput(voices[1]) shr 4) and 0xFF
        0x1C -> voices[2].envelope
        // Everything else is write-only and returns whatever was last written to any register,
        // because that is what is still on the internal bus.
        else -> lastWrite
    }

    fun write(register: Int, value: Int) {
        val v = value and 0xFF
        lastWrite = v
        val register = register and 0x1F
        if (register < 0x15) {
            val voice = voices[register / 7]
            when (register % 7) {
                0 -> voice.frequency = (voice.frequency and 0xFF00) or v
                1 -> voice.frequency = (voice.frequency and 0x00FF) or (v shl 8)
                2 -> voice.pulseWidth = (voice.pulseWidth and 0x0F00) or v
                3 -> voice.pulseWidth = (voice.pulseWidth and 0x00FF) or ((v and 0x0F) shl 8)
                4 -> voice.setControl(v)
                5 -> {
                    voice.attack = (v shr 4) and 0x0F
                    voice.decay = v and 0x0F
                }
                6 -> {
                    voice.sustain = (v shr 4) and 0x0F
                    voice.release = v and 0x0F
                }
            }
            return
        }
        when (register) {
            0x15 -> filterCutoff = (filterCutoff and 0x7F8) or (v and 0x07)
            0x16 -> filterCutoff = (filterCutoff and 0x007) or (v shl 3)
            0x17 -> {
                filterRouting = v and 0x0F
                filterResonance = (v shr 4) and 0x0F
            }
            0x18 -> {
                volume = v and 0x0F
                filterMode = (v shr 4) and 0x07
                voice3Off = v and 0x80 != 0
            }
        }
    }

    /** One system cycle. */
    fun clock() {
        voices[0].clock(voices[2])
        voices[1].clock(voices[0])
        voices[2].clock(voices[1])
        voices[0].clockEnvelope()
        voices[1].clockEnvelope()
        voices[2].clockEnvelope()

        sampleSum += mix()
        sampleCount++
        sampleAccumulator += 1.0
        if (sampleAccumulator >= cyclesPerSample) {
            sampleAccumulator -= cyclesPerSample
            emit(sampleSum / sampleCount)
            sampleSum = 0.0
            sampleCount = 0
        }
    }

    /**
     * One sample of the chip's output, before decimation: the three voices split between the
     * filtered and unfiltered paths, then the master volume.
     */
    private fun mix(): Double {
        var direct = 0.0
        var filtered = 0.0
        for (i in 0 until 2 + 1) {
            if (i == 2 && voice3Off && filterRouting and 0x04 == 0) continue
            val sample = voices[i].output(voices[(i + 2) % 3])
            if (filterRouting and (1 shl i) != 0) filtered += sample else direct += sample
        }

        // A two-integrator loop: one integrator gives band-pass, the second low-pass, and what is
        // left over is high-pass.
        val w0 = cutoffFrequency() * 2.0 * Math.PI / clockHz
        val q = 1.0 / (0.707 + filterResonance / 15.0 * 1.8)
        val highPass = bandPass * q - lowPass - filtered
        bandPass += w0 * highPass
        lowPass += w0 * bandPass
        bandPass = bandPass.coerceIn(-4_000_000.0, 4_000_000.0)
        lowPass = lowPass.coerceIn(-4_000_000.0, 4_000_000.0)

        var out = direct
        if (filterMode and 0x01 != 0) out += lowPass
        if (filterMode and 0x02 != 0) out += bandPass
        if (filterMode and 0x04 != 0) out += highPass
        return out * volume / 15.0
    }

    /**
     * The 6581's cutoff is famously not linear in the register value and varies between chips.
     * This curve is the shape most people remember: barely moving at the bottom of the range and
     * opening quickly near the top.
     */
    private fun cutoffFrequency(): Double {
        val normalised = filterCutoff / 2047.0
        return 30.0 + 11_970.0 * normalised * normalised
    }

    private fun emit(sample: Double) {
        // Three voices at full envelope through the filter can reach a long way outside 16 bits;
        // clipping is what the real chip does too, just more gracefully.
        val scaled = (sample * VOLUME_SCALE).toInt().coerceIn(-32768, 32767)
        val next = (writeIndex + 1) % OUTPUT_BUFFER
        if (next == readIndex) return // the consumer is behind; dropping is better than blocking
        output[writeIndex] = scaled.toShort()
        writeIndex = next
    }

    /** Number of samples waiting to be played. */
    fun available(): Int = (writeIndex - readIndex + OUTPUT_BUFFER) % OUTPUT_BUFFER

    /**
     * Copies out up to [count] samples, returning how many there were. A short read means the
     * emulation has not caught up, and the caller should pad with silence rather than stall.
     */
    fun readSamples(destination: ShortArray, count: Int): Int {
        var written = 0
        while (written < count && readIndex != writeIndex) {
            destination[written++] = output[readIndex]
            readIndex = (readIndex + 1) % OUTPUT_BUFFER
        }
        return written
    }

    private companion object {
        const val OUTPUT_BUFFER = 65536

        /** Maps the chip's internal scale onto 16-bit samples with room left for the filter. */
        const val VOLUME_SCALE = 0.0009
    }
}
