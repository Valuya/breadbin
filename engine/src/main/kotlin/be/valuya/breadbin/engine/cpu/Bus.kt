package be.valuya.breadbin.engine.cpu

/**
 * The CPU's view of the machine.
 *
 * Every call here is one system cycle: the implementation is expected to advance the VIC-II, the
 * CIAs and everything else by a cycle *before* answering. The CPU therefore never counts cycles
 * itself — it performs exactly the bus accesses a real 6510 performs, including the dummy reads
 * and the double writes of a read-modify-write, and the clock follows from that.
 *
 * Doing it this way means cycle counts cannot drift out of step with the instruction
 * implementations, which is the usual way a table-driven core goes subtly wrong.
 */
interface Bus {
    fun read(address: Int): Int

    fun write(address: Int, value: Int)

    /**
     * Called when the CPU executes an illegal $02 opcode, which is how the KERNAL patches for the
     * virtual disk drive announce themselves. Returning true means the handler has dealt with it
     * and set the CPU up to continue (normally by faking an RTS); false halts the processor, which
     * is what a real 6510 does with a JAM.
     */
    fun trap(cpu: Cpu6510, address: Int): Boolean = false
}
