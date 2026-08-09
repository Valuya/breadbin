package be.valuya.breadbin.engine.disk

import be.valuya.breadbin.engine.drive.IecBus

/**
 * The same drive, answering on the wires.
 *
 * [Iec] serves a disk by intercepting the KERNAL's serial routines, which is fast and needs no drive
 * ROM but only works for a KERNAL that calls them. Commodore's does; the free replacement ROMs the
 * app ships do not — they drive the three lines themselves — so under those a disk would never load
 * at all, and asking the user to find Commodore's ROMs before they can open a .d64 is not much of
 * an emulator.
 *
 * So this does the protocol properly, bit by bit and edge by edge, as a device sitting on the bus.
 * It works with any KERNAL because it makes no assumptions about one, and it is the whole reason
 * disks work out of the box. What it is not is a 1541: there is no 6502 in here and no disk surface,
 * so a game that uploads its own transfer routine into the drive has nothing to upload it into.
 * Those want [be.valuya.breadbin.engine.drive.Drive1541].
 *
 * The protocol is the one in the 1541's own DOS listing, from the other end:
 *
 *  - Everything is wired-AND and active low. A device pulls a line down or lets go; nobody drives
 *    one high, so the line is low if anybody at all is holding it.
 *  - The computer asks for attention by pulling ATN. Every device answers by pulling DATA, which is
 *    how the computer finds out whether anything is out there before it has said a word.
 *  - Under attention the computer sends a command byte: who should listen, who should talk, and
 *    which channel.
 *  - A byte goes over as eight bits, least significant first. The talker puts a bit on DATA and
 *    releases CLK; the listener reads it on that edge; the talker pulls CLK down again. DATA
 *    released means a one.
 *  - The end of a file is not a byte, it is a silence: the talker simply does not pull CLK back
 *    down, and after a couple of hundred microseconds the listener takes the hint and acknowledges.
 *
 * It is written as a coroutine rather than a state machine with a switch in it, so that the code
 * above reads in the order it happens. Each `yield` is one system cycle, and the sequencer only
 * resumes when whatever it was waiting for is true — so an idle bus costs one predicate a cycle.
 */
class IecWire(private val iec: Iec, private val bus: IecBus) {

    private var cycles = 0L
    private var waiting: (() -> Boolean)? = null
    private var steps = iterator { protocol() }

    /** Called with a description of every step taken, for the trace in the tests. */
    var onStep: ((String) -> Unit)? = null

    private fun step(message: String) { onStep?.invoke(message) }

    /** Bytes that have gone over the wires, which is how a test tells serving from silence. */
    var bytesTransferred = 0L
        private set

    fun reset() {
        waiting = null
        steps = iterator { protocol() }
        bytesTransferred = 0
        bus.deviceClock = false
        bus.deviceData = false
    }

    /** One system cycle. */
    fun cycle() {
        cycles++
        val condition = waiting
        if (condition != null) {
            if (!condition()) return
            waiting = null
        }
        if (steps.hasNext()) steps.next()
    }

    // ---- the sequencer's two primitives --------------------------------------------------------

    private suspend fun kotlin.sequences.SequenceScope<Unit>.until(condition: () -> Boolean) {
        if (condition()) return
        waiting = condition
        yield(Unit)
    }

    private suspend fun kotlin.sequences.SequenceScope<Unit>.pause(cycles: Int) {
        val deadline = this@IecWire.cycles + cycles
        until { this@IecWire.cycles >= deadline }
    }

    /**
     * Waits for something, but not for ever. Returns whether it happened.
     *
     * Several of the listener's replies are courtesies rather than requirements — a computer that
     * never sends one is not broken, it just does not bother — so waiting for them without a way
     * out turns a working transfer into a hang.
     */
    private suspend fun kotlin.sequences.SequenceScope<Unit>.untilOr(
        limit: Int,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = cycles + limit
        until { condition() || cycles >= deadline }
        return condition()
    }

    private fun pullClock(low: Boolean) {
        bus.deviceClock = low
    }

    private fun pullData(low: Boolean) {
        bus.deviceData = low
    }

    private fun release() {
        bus.deviceClock = false
        bus.deviceData = false
    }

    // ---- the protocol --------------------------------------------------------------------------

    private suspend fun kotlin.sequences.SequenceScope<Unit>.protocol() {
        while (true) {
            release()
            iec.unlisten()
            iec.untalk()
            until { bus.atn }

            // Answer at once. The computer decides whether anything is plugged in from how quickly
            // this happens, long before it has said which device it wants.
            pullData(true)
            var listening = false
            var talking = false

            while (bus.atn) {
                val command = receive(underAttention = true) ?: break
                step("command %02X".format(command))
                when {
                    command == UNLISTEN -> {
                        iec.unlisten()
                        listening = false
                    }
                    command == UNTALK -> {
                        iec.untalk()
                        talking = false
                    }
                    command in LISTEN_BASE..LISTEN_BASE + 30 -> {
                        listening = iec.addressListener(command - LISTEN_BASE)
                        talking = false
                        if (!listening) break
                    }
                    command in TALK_BASE..TALK_BASE + 30 -> {
                        talking = iec.addressTalker(command - TALK_BASE)
                        listening = false
                        if (!talking) break
                    }
                    command >= SECONDARY_BASE ->
                        if (listening || talking) iec.openChannel(command, forWriting = listening)
                }
            }

            if (!listening && !talking) {
                // Not for us, or nothing here to answer with. Letting go of everything is what
                // turns into DEVICE NOT PRESENT at the other end, which is the truth.
                release()
                until { !bus.atn }
                continue
            }

            until { !bus.atn }
            if (talking) {
                // Turn the bus round: the computer was driving CLK, now we are.
                pullData(false)
                pullClock(true)
                pause(TURNAROUND)
                send()
            } else {
                // Keep holding DATA down and take bytes until the computer says stop.
                while (!bus.atn) {
                    val byte = receive(underAttention = false) ?: break
                    step("data in %02X".format(byte))
                    iec.sendToChannel(byte)
                    bytesTransferred++
                }
                if (bus.atn) continue
            }
        }
    }

    /**
     * Takes one byte as the listener.
     *
     * [underAttention] is the state of ATN the caller is expecting throughout — asserted while the
     * computer is handing out commands, released while it is handing over data. If it changes part
     * way through then the computer has abandoned what it was doing, and the half a byte collected
     * so far is worth nothing: this gives up and returns null so the caller can start again.
     */
    private suspend fun kotlin.sequences.SequenceScope<Unit>.receive(underAttention: Boolean): Int? {
        // First, wait for the talker to have hold of the clock at all.
        //
        // A released clock means "a byte is ready" — but it also means "nothing has started yet",
        // and the two are the same line at the same level. The computer asserts attention a couple
        // of dozen cycles before it takes hold of the clock, so a device that answers attention and
        // looks straight at the clock sees it released, decides a byte is coming, and is a whole
        // handshake ahead of the computer for the rest of the transaction. Between bytes the talker
        // is already holding it, so this costs nothing after the first.
        untilOr(TAKING_HOLD) { bus.clock || bus.atn != underAttention }
        if (bus.atn != underAttention) return null

        // Now the release means what it says.
        until { !bus.clock || bus.atn != underAttention }
        if (bus.atn != underAttention) return null
        pullData(false)

        // The talker now pulls CLK back down to start. If it does not, there is nothing more
        // coming: that silence is the end of the file, and it wants acknowledging.
        val deadline = cycles + EOI_TIMEOUT
        until { bus.clock || cycles >= deadline || bus.atn != underAttention }
        if (bus.atn != underAttention) return null
        if (!bus.clock) {
            pullData(true)
            pause(EOI_ACKNOWLEDGE)
            pullData(false)
            until { bus.clock || bus.atn != underAttention }
            if (bus.atn != underAttention) return null
        }

        var value = 0
        repeat(8) {
            until { !bus.clock || bus.atn != underAttention } // the edge the bit is valid on
            if (bus.atn != underAttention) return null
            // Least significant first, and a line nobody is pulling down is a one.
            value = (value shr 1) or (if (bus.data) 0 else 0x80)
            until { bus.clock || bus.atn != underAttention }
            if (bus.atn != underAttention) return null
        }
        pullData(true) // taken
        bytesTransferred++
        return value
    }

    /** Hands over the whole of whatever channel is open, as the talker. */
    private suspend fun kotlin.sequences.SequenceScope<Unit>.send() {
        while (true) {
            val byte = iec.readFromChannel()
            step("sending %02X".format(byte))
            if (byte < 0) {
                // Nothing to send — a name that is not on the disk. A real 1541 lets go of the bus
                // and says nothing, and the computer's own timeout turns that into FILE NOT FOUND.
                release()
                return
            }
            val last = iec.channelAtEnd

            // Let go of the clock to say there is a byte ready, and hold it there. This is the one
            // edge in the whole protocol with nothing on the other end acknowledging it: the
            // listener is sitting in a two-instruction loop watching for it, and a pulse narrower
            // than that loop is a pulse it never sees.
            pullClock(false)
            pause(READY_HOLD)
            until { !bus.data || bus.atn } // the listener is ready for it
            if (bus.atn) { step("attention before %02X".format(byte)); return }

            if (last) {
                // Nothing more after this one, and the way to say so is to say nothing: leave the
                // clock alone well past the couple of hundred microseconds the listener waits
                // before it gives up on another byte. It answers by pulling DATA down and letting
                // go again — or it does not bother, and the silence was the message either way.
                if (untilOr(END_OF_FILE, { bus.data || bus.atn })) {
                    if (bus.atn) return
                    untilOr(END_OF_FILE) { !bus.data || bus.atn }
                    if (bus.atn) return
                }
            }

            pullClock(true)
            pause(SETTLE)

            var bits = byte
            repeat(8) {
                pullData(bits and 1 == 0) // a zero is the line pulled down
                bits = bits shr 1
                pause(BIT_SETUP)
                pullClock(false) // the listener reads the bit here
                pause(BIT_HOLD)
                pullClock(true)
                pullData(false)
            }

            until { bus.data || bus.atn } // taken
            if (bus.atn) { step("abandoned after %02X".format(byte)); return }
            bytesTransferred++
            if (last) {
                release()
                return
            }
        }
    }

    private companion object {
        const val LISTEN_BASE = 0x20
        const val UNLISTEN = 0x3F
        const val TALK_BASE = 0x40
        const val UNTALK = 0x5F
        const val SECONDARY_BASE = 0x60

        /**
         * How long the listener waits for the talker to start clocking before deciding there is
         * nothing more coming. The real figure is two hundred microseconds, and a cycle is near
         * enough a microsecond on this machine.
         */
        const val EOI_TIMEOUT = 200

        /** How long to hold DATA down to acknowledge that silence. */
        const val EOI_ACKNOWLEDGE = 60

        /** A moment after taking the bus over before clocking anything out. */
        const val TURNAROUND = 1000
        const val SETTLE = 600

        /** How long "there is a byte ready" is held, which has to outlast a listener's poll loop. */
        const val READY_HOLD = 100

        /**
         * How long to give the talker to take hold of the clock before giving up on it. Commodore's
         * KERNAL takes a couple of dozen cycles over it; this is generous, and bounded only so that
         * a computer which does something else entirely cannot wedge the drive for ever.
         */
        const val TAKING_HOLD = 2000

        /**
         * How long the gap before the last byte is.
         *
         * This is the whole of how the end of a file is announced, and it works by outlasting the
         * listener's patience — so it has to be longer than however long that is. Commodore's
         * KERNAL times it with a CIA counting somewhere between two and five hundred cycles, which
         * a four-hundred-cycle gap loses to about half the time; the symptom is a load that
         * transfers every byte correctly and then hangs asking for one more. Milliseconds are free
         * here, since this happens once per file.
         */
        const val END_OF_FILE = 4000

        /**
         * How long a bit is held before, and then during, the clock edge that reads it.
         *
         * There is no handshake inside a byte — the listener samples on the edge and that is all —
         * so a pulse it fails to notice is a bit lost and a transfer that never recovers. The
         * listener's poll loop is a dozen cycles, which would suggest something short, but the
         * number that actually matters is the video chip: on a badline it stops the processor dead
         * for forty-odd cycles, and a pulse shorter than that can pass entirely while the computer
         * is not running. Both of these are comfortably longer than a badline.
         *
         * The worst case is a badline plus a full line of sprite fetches, which is a little over
         * sixty cycles; a hundred leaves enough on top of that for a poll loop to go round twice.
         * That works out at half again the speed of a real 1541 — still slow, and slow is the price
         * of a transfer with no handshake inside a byte. The fast-forward button exists.
         */
        const val BIT_SETUP = 100
        const val BIT_HOLD = 100
    }
}
