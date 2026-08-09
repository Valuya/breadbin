package be.valuya.breadbin.engine.cpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Klaus Dormann's 6502 functional test, which walks every documented opcode, addressing mode and
 * flag interaction — decimal mode included — and traps itself on the first disagreement.
 *
 * The binary is a full 64K image: it runs from $0400 and, when it passes, falls into a `jmp *` at
 * a known address. Any other self-loop is a failure, and the address says which test failed when
 * cross-referenced with the listing in Klaus2m5/6502_65C02_functional_tests.
 */
class Cpu6510ConformanceTest {

    private class FlatMemory(image: ByteArray) : Bus {
        val memory = IntArray(0x10000)
        var cycles = 0L

        init {
            for (i in 0 until 0x10000) memory[i] = image[i].toInt() and 0xFF
        }

        override fun read(address: Int): Int {
            cycles++
            return memory[address and 0xFFFF]
        }

        override fun write(address: Int, value: Int) {
            cycles++
            memory[address and 0xFFFF] = value and 0xFF
        }
    }

    @Test
    fun `passes the 6502 functional test`() {
        val image = checkNotNull(javaClass.getResourceAsStream("/6502_functional_test.bin")) {
            "6502_functional_test.bin is missing from the test resources"
        }.readBytes()
        assertEquals(0x10000, image.size)

        val bus = FlatMemory(image)
        val cpu = Cpu6510(bus)
        cpu.pc = 0x0400

        var previousPc = -1
        var instructions = 0
        while (cpu.pc != previousPc) {
            previousPc = cpu.pc
            cpu.step()
            check(++instructions < 100_000_000) { "the test never settled" }
        }

        assertTrue("the processor jammed at ${"%04X".format(cpu.pc)}", !cpu.jammed)
        assertEquals(
            "trapped at ${"%04X".format(cpu.pc)} rather than the success address",
            SUCCESS_ADDRESS,
            cpu.pc,
        )
    }

    private companion object {
        /** Where the shipped build of the functional test parks itself once every case has passed. */
        const val SUCCESS_ADDRESS = 0x3469
    }
}
