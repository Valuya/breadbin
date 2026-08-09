package be.valuya.breadbin.engine.sid

/**
 * One of the SID's three voices: a phase accumulator driving four waveform generators, and an
 * envelope generator that multiplies the result.
 *
 * Selecting more than one waveform at once is not something the chip was designed to do, and what
 * comes out is the bitwise AND of the selected waveforms passing through the same output lines.
 * Approximating it as exactly that AND is what most emulators settle on, and it is close enough
 * that the tunes which rely on it still sound like themselves.
 */
class Voice {
    var frequency = 0
    var pulseWidth = 0
    var attack = 0
    var decay = 0
    var sustain = 0
    var release = 0

    private var control = 0
    private var accumulator = 0
    private var previousAccumulator = 0
    private var noiseShift = 0x7FFFF8

    var envelope = 0
        private set

    private var state = EnvelopeState.RELEASE
    private var rateCounter = 0
    private var ratePeriod = RATE[0]
    private var exponentialCounter = 0
    private var exponentialPeriod = 1
    private var holdZero = true

    private val gate get() = control and 0x01 != 0
    private val sync get() = control and 0x02 != 0
    private val ringModulation get() = control and 0x04 != 0
    private val test get() = control and 0x08 != 0

    fun reset() {
        control = 0
        accumulator = 0
        noiseShift = 0x7FFFF8
        envelope = 0
        state = EnvelopeState.RELEASE
        holdZero = true
        rateCounter = 0
        exponentialCounter = 0
    }

    fun setControl(value: Int) {
        val wasGated = gate
        val wasTesting = test
        control = value and 0xFF

        if (!wasGated && gate) {
            // The attack always restarts from wherever the envelope happens to be, which is why
            // retriggering a still-sounding note does not click.
            state = EnvelopeState.ATTACK
            ratePeriod = RATE[attack]
            holdZero = false
        } else if (wasGated && !gate) {
            state = EnvelopeState.RELEASE
            ratePeriod = RATE[release]
        }

        if (test && !wasTesting) {
            accumulator = 0
            noiseShift = 0x7FFFFF
        }
    }

    /** One system cycle of the oscillator. [source] is the voice this one syncs and rings against. */
    fun clock(source: Voice) {
        if (test) {
            accumulator = 0
            return
        }
        previousAccumulator = accumulator
        accumulator = (accumulator + frequency) and 0xFFFFFF

        // Hard sync resets this oscillator when the one before it starts a new cycle.
        if (sync && source.startedCycle()) accumulator = 0

        // The noise register is shifted by bit 19 of the accumulator rising, so the noise pitch
        // follows the frequency register like the other waveforms do.
        if (previousAccumulator and 0x080000 == 0 && accumulator and 0x080000 != 0) {
            val feedback = ((noiseShift shr 22) xor (noiseShift shr 17)) and 0x01
            noiseShift = ((noiseShift shl 1) or feedback) and 0x7FFFFF
        }
    }

    private fun startedCycle() = previousAccumulator and 0x800000 == 0 && accumulator and 0x800000 != 0

    fun clockEnvelope() {
        if (++rateCounter < ratePeriod) return
        rateCounter = 0

        // Attack is linear; decay and release fall off exponentially, which the chip fakes by only
        // stepping the counter every so many rate periods as the level drops.
        if (state != EnvelopeState.ATTACK && ++exponentialCounter < exponentialPeriod) return
        exponentialCounter = 0
        if (holdZero) return

        when (state) {
            EnvelopeState.ATTACK -> {
                envelope++
                if (envelope >= 0xFF) {
                    envelope = 0xFF
                    state = EnvelopeState.DECAY_SUSTAIN
                    ratePeriod = RATE[decay]
                }
            }
            EnvelopeState.DECAY_SUSTAIN -> {
                val level = sustain * 0x11
                if (envelope > level) envelope--
            }
            EnvelopeState.RELEASE -> if (envelope > 0) envelope--
        }
        if (envelope == 0) holdZero = true
        exponentialPeriod = exponentialPeriodFor(envelope)
    }

    private fun exponentialPeriodFor(level: Int) = when {
        level >= 0xFF -> 1
        level >= 0x5D -> 2
        level >= 0x36 -> 4
        level >= 0x1A -> 8
        level >= 0x0E -> 16
        level >= 0x06 -> 30
        else -> 1
    }

    /** The 12-bit waveform, before the envelope. */
    fun waveformOutput(source: Voice): Int {
        val waveform = (control shr 4) and 0x0F
        if (waveform == 0) return 0
        var value = 0xFFF
        if (waveform and 0x01 != 0) value = value and triangle(source)
        if (waveform and 0x02 != 0) value = value and sawtooth()
        if (waveform and 0x04 != 0) value = value and pulse()
        if (waveform and 0x08 != 0) value = value and noise()
        return value
    }

    /**
     * The voice's contribution to the mix, centred on zero.
     *
     * A waveform runs from 0 to $fff, so the middle of it has to come off before it is worth
     * anything as a signal — but only when there is a waveform at all. Selecting none is a silent
     * voice, and subtracting the centre from a silence turns it into a large constant offset that
     * moves with the envelope: a thump on every note, from a voice that is not playing one.
     */
    fun output(source: Voice): Double {
        if ((control shr 4) and 0x0F == 0) return 0.0
        return (waveformOutput(source) - 0x800).toDouble() * envelope
    }

    private fun triangle(source: Voice): Int {
        // Ring modulation replaces this oscillator's own sign bit with the exclusive-or of the two,
        // which folds the triangle into the metallic sound C64 music uses for bells and drums.
        val msb = if (ringModulation) (accumulator xor source.accumulator) else accumulator
        val shifted = if (msb and 0x800000 != 0) accumulator.inv() else accumulator
        return (shifted shr 11) and 0xFFF
    }

    private fun sawtooth() = (accumulator shr 12) and 0xFFF

    private fun pulse(): Int =
        if (test || (accumulator shr 12) and 0xFFF >= pulseWidth) 0xFFF else 0x000

    private fun noise(): Int =
        ((noiseShift and 0x400000) shr 11) or
            ((noiseShift and 0x100000) shr 10) or
            ((noiseShift and 0x010000) shr 7) or
            ((noiseShift and 0x002000) shr 5) or
            ((noiseShift and 0x000800) shr 4) or
            ((noiseShift and 0x000080) shr 1) or
            ((noiseShift and 0x000010) shl 1) or
            ((noiseShift and 0x000004) shl 2)

    private enum class EnvelopeState { ATTACK, DECAY_SUSTAIN, RELEASE }

    private companion object {
        /**
         * How many system cycles one step of the envelope takes, for each of the sixteen settings
         * of attack, decay and release. These are the chip's own numbers.
         */
        val RATE = intArrayOf(
            9, 32, 63, 95, 149, 220, 267, 313,
            392, 977, 1954, 3126, 3907, 11719, 19531, 31251,
        )
    }
}
