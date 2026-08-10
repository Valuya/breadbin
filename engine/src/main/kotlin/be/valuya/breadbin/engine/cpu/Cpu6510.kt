package be.valuya.breadbin.engine.cpu

/**
 * The MOS 6510 as fitted to the C64: a 6502 with an on-chip I/O port at $0000/$0001, which the
 * memory implementation handles rather than the CPU.
 *
 * The whole documented instruction set is here, plus the undocumented opcodes, because a fair
 * number of games and almost every cracked intro use them. Decimal mode is implemented properly,
 * including the flags the 6502 leaves in a strange state.
 *
 * Timing comes from the bus: see [Bus].
 */
class Cpu6510(private val bus: Bus) {

    var a = 0
    var x = 0
    var y = 0
    var sp = 0xFD
    var pc = 0

    var carry = false
    var zero = false
    var interruptDisable = true
    var decimal = false
    var overflow = false
    var negative = false

    /** Set while a JAM opcode has locked the processor up, as on real hardware. */
    var jammed = false
        private set

    /** IRQ is level triggered: hold this true for as long as a source is asserting it. */
    var irqLine = false

    private var nmiLine = false
    private var nmiPending = false

    /**
     * The I flag only starts masking interrupts one instruction after SEI, and stops masking one
     * instruction after CLI, because the 6502 samples the line before the flag is written. Games
     * that run `CLI` immediately before a raster IRQ depend on it.
     */
    private var interruptDisableSampled = true

    fun reset() {
        sp = 0xFD
        interruptDisable = true
        decimal = false
        jammed = false
        nmiPending = false
        nmiLine = false
        interruptDisableSampled = true
        pc = bus.read(0xFFFC) or (bus.read(0xFFFD) shl 8)
    }

    /** NMI is edge triggered: the interrupt is latched on the high-to-low transition only. */
    fun setNmiLine(asserted: Boolean) {
        if (asserted && !nmiLine) nmiPending = true
        nmiLine = asserted
    }

    var status: Int
        get() = (if (carry) 0x01 else 0) or
            (if (zero) 0x02 else 0) or
            (if (interruptDisable) 0x04 else 0) or
            (if (decimal) 0x08 else 0) or
            0x20 or
            (if (overflow) 0x40 else 0) or
            (if (negative) 0x80 else 0)
        set(value) {
            carry = value and 0x01 != 0
            zero = value and 0x02 != 0
            interruptDisable = value and 0x04 != 0
            decimal = value and 0x08 != 0
            overflow = value and 0x40 != 0
            negative = value and 0x80 != 0
        }

    /** Executes one instruction, or enters an interrupt sequence if one is pending. */
    fun step() {
        if (jammed) {
            bus.read(pc)
            return
        }
        if (nmiPending) {
            nmiPending = false
            bus.read(pc)
            bus.read(pc)
            interrupt(0xFFFA, brk = false)
            return
        }
        if (irqLine && !interruptDisableSampled) {
            bus.read(pc)
            bus.read(pc)
            interrupt(0xFFFE, brk = false)
            return
        }
        interruptDisableSampled = interruptDisable
        instructionAt = pc
        execute(readPc())
    }

    /** Where the instruction being executed began, so a jump can say where it came from. */
    private var instructionAt = 0

    /**
     * Told about every JMP and JSR, as (from, to). Null unless something is watching, which in
     * practice means the machine looking out for programs that jump into the middle of the KERNAL.
     */
    var jumpWatcher: ((Int, Int) -> Unit)? = null

    /**
     * Unwinds a JSR the way an RTS would. The KERNAL patches use it to return from a trapped
     * routine once the handler has done the work in Kotlin.
     */
    fun returnFromSubroutine() {
        val lo = pull()
        val hi = pull()
        pc = ((lo or (hi shl 8)) + 1) and 0xFFFF
    }

    /**
     * The five cycles a 6502 spends saving its state and jumping through a vector. The two dummy
     * reads that precede them belong to the caller, because BRK spends one of them fetching (and
     * discarding) the byte after the opcode while a hardware interrupt spends both idling.
     */
    private fun interrupt(vector: Int, brk: Boolean) {
        push(pc shr 8)
        push(pc and 0xFF)
        push(if (brk) status or 0x10 else status)
        interruptDisable = true
        interruptDisableSampled = true
        pc = bus.read(vector) or (bus.read(vector + 1) shl 8)
    }

    private fun readPc(): Int {
        val value = bus.read(pc)
        pc = (pc + 1) and 0xFFFF
        return value
    }

    private fun push(value: Int) {
        bus.write(0x0100 or sp, value and 0xFF)
        sp = (sp - 1) and 0xFF
    }

    private fun pull(): Int {
        sp = (sp + 1) and 0xFF
        return bus.read(0x0100 or sp)
    }

    // ---- addressing ------------------------------------------------------------------------

    private fun zeroPage(): Int = readPc()

    private fun zeroPageIndexed(index: Int): Int {
        val base = readPc()
        bus.read(base)
        return (base + index) and 0xFF
    }

    private fun absolute(): Int = readPc() or (readPc() shl 8)

    /** Indexed absolute for a read: the extra cycle only happens when the page boundary is crossed. */
    private fun absoluteIndexedRead(index: Int): Int {
        val base = absolute()
        val address = (base + index) and 0xFFFF
        if ((base and 0xFF00) != (address and 0xFF00)) {
            bus.read((base and 0xFF00) or (address and 0xFF))
        }
        return address
    }

    /** Indexed absolute for a write, which always pays the extra cycle. */
    private fun absoluteIndexedWrite(index: Int): Int {
        val base = absolute()
        val address = (base + index) and 0xFFFF
        bus.read((base and 0xFF00) or (address and 0xFF))
        return address
    }

    private fun indexedIndirect(): Int {
        val zp = readPc()
        bus.read(zp)
        val pointer = (zp + x) and 0xFF
        return bus.read(pointer) or (bus.read((pointer + 1) and 0xFF) shl 8)
    }

    private fun indirectIndexedRead(): Int {
        val zp = readPc()
        val base = bus.read(zp) or (bus.read((zp + 1) and 0xFF) shl 8)
        val address = (base + y) and 0xFFFF
        if ((base and 0xFF00) != (address and 0xFF00)) {
            bus.read((base and 0xFF00) or (address and 0xFF))
        }
        return address
    }

    private fun indirectIndexedWrite(): Int {
        val zp = readPc()
        val base = bus.read(zp) or (bus.read((zp + 1) and 0xFF) shl 8)
        val address = (base + y) and 0xFFFF
        bus.read((base and 0xFF00) or (address and 0xFF))
        return address
    }

    // ---- primitives ------------------------------------------------------------------------

    private fun setNz(value: Int) {
        zero = value and 0xFF == 0
        negative = value and 0x80 != 0
    }

    private fun branch(taken: Boolean) {
        val offset = readPc()
        if (!taken) return
        bus.read(pc)
        val target = (pc + (offset.toByte()).toInt()) and 0xFFFF
        if ((target and 0xFF00) != (pc and 0xFF00)) {
            bus.read((pc and 0xFF00) or (target and 0xFF))
        }
        pc = target
    }

    private fun compare(register: Int, value: Int) {
        val result = register - value
        carry = result >= 0
        setNz(result and 0xFF)
    }

    private fun adc(value: Int) {
        if (decimal) {
            var lo = (a and 0x0F) + (value and 0x0F) + (if (carry) 1 else 0)
            var hi = (a and 0xF0) + (value and 0xF0)
            // Z comes from the binary result, N and V from the half-corrected one: the 6502 works
            // out the flags mid-correction and never goes back to tidy them up.
            zero = (a + value + (if (carry) 1 else 0)) and 0xFF == 0
            if (lo > 0x09) {
                hi += 0x10
                lo += 0x06
            }
            negative = hi and 0x80 != 0
            overflow = ((a xor hi) and (value xor hi) and 0x80) != 0
            if (hi > 0x90) hi += 0x60
            carry = hi and 0xFF00 != 0
            a = ((hi and 0xF0) or (lo and 0x0F)) and 0xFF
        } else {
            val sum = a + value + (if (carry) 1 else 0)
            carry = sum > 0xFF
            overflow = ((a xor sum) and (value xor sum) and 0x80) != 0
            a = sum and 0xFF
            setNz(a)
        }
    }

    private fun sbc(value: Int) {
        if (decimal) {
            val borrow = if (carry) 0 else 1
            var lo = (a and 0x0F) - (value and 0x0F) - borrow
            var hi = (a and 0xF0) - (value and 0xF0)
            if (lo and 0x10 != 0) {
                lo -= 0x06
                hi -= 0x10
            }
            if (hi and 0x0100 != 0) hi -= 0x60
            // The flags are the binary ones, which is why BCD subtraction sets them sensibly.
            val binary = a - value - borrow
            carry = binary and 0x100 == 0
            overflow = ((a xor binary) and ((0xFF - value) xor binary) and 0x80) != 0
            setNz(binary and 0xFF)
            a = ((hi and 0xF0) or (lo and 0x0F)) and 0xFF
        } else {
            adc(value.inv() and 0xFF)
        }
    }

    private fun asl(value: Int): Int {
        carry = value and 0x80 != 0
        val result = (value shl 1) and 0xFF
        setNz(result)
        return result
    }

    private fun lsr(value: Int): Int {
        carry = value and 0x01 != 0
        val result = value shr 1
        setNz(result)
        return result
    }

    private fun rol(value: Int): Int {
        val result = ((value shl 1) or (if (carry) 1 else 0)) and 0xFF
        carry = value and 0x80 != 0
        setNz(result)
        return result
    }

    private fun ror(value: Int): Int {
        val result = (value shr 1) or (if (carry) 0x80 else 0)
        carry = value and 0x01 != 0
        setNz(result)
        return result
    }

    /** A read-modify-write writes the old value back before the new one, and hardware notices. */
    private inline fun readModifyWrite(address: Int, operation: (Int) -> Int) {
        val value = bus.read(address)
        bus.write(address, value)
        bus.write(address, operation(value) and 0xFF)
    }

    /**
     * The address written by SHA/SHX/SHY/TAS is ANDed with the high byte of the target address
     * plus one. Hardware only does this reliably when no page boundary was crossed; emulating the
     * documented-unstable case as the stable one is what every other emulator settles on.
     */
    private fun storeHigh(address: Int, value: Int) {
        bus.write(address, value and (((address shr 8) + 1) and 0xFF))
    }

    // ---- the instruction set ---------------------------------------------------------------

    private fun execute(opcode: Int) {
        when (opcode) {
            // ADC
            0x69 -> adc(readPc())
            0x65 -> adc(bus.read(zeroPage()))
            0x75 -> adc(bus.read(zeroPageIndexed(x)))
            0x6D -> adc(bus.read(absolute()))
            0x7D -> adc(bus.read(absoluteIndexedRead(x)))
            0x79 -> adc(bus.read(absoluteIndexedRead(y)))
            0x61 -> adc(bus.read(indexedIndirect()))
            0x71 -> adc(bus.read(indirectIndexedRead()))

            // AND
            0x29 -> { a = a and readPc(); setNz(a) }
            0x25 -> { a = a and bus.read(zeroPage()); setNz(a) }
            0x35 -> { a = a and bus.read(zeroPageIndexed(x)); setNz(a) }
            0x2D -> { a = a and bus.read(absolute()); setNz(a) }
            0x3D -> { a = a and bus.read(absoluteIndexedRead(x)); setNz(a) }
            0x39 -> { a = a and bus.read(absoluteIndexedRead(y)); setNz(a) }
            0x21 -> { a = a and bus.read(indexedIndirect()); setNz(a) }
            0x31 -> { a = a and bus.read(indirectIndexedRead()); setNz(a) }

            // ASL
            0x0A -> { bus.read(pc); a = asl(a) }
            0x06 -> readModifyWrite(zeroPage(), ::asl)
            0x16 -> readModifyWrite(zeroPageIndexed(x), ::asl)
            0x0E -> readModifyWrite(absolute(), ::asl)
            0x1E -> readModifyWrite(absoluteIndexedWrite(x), ::asl)

            // branches
            0x10 -> branch(!negative)
            0x30 -> branch(negative)
            0x50 -> branch(!overflow)
            0x70 -> branch(overflow)
            0x90 -> branch(!carry)
            0xB0 -> branch(carry)
            0xD0 -> branch(!zero)
            0xF0 -> branch(zero)

            // BIT
            0x24 -> bit(bus.read(zeroPage()))
            0x2C -> bit(bus.read(absolute()))

            0x00 -> { // BRK, whose signature byte is fetched and thrown away
                readPc()
                interrupt(0xFFFE, brk = true)
            }

            0x18 -> { bus.read(pc); carry = false }
            0x38 -> { bus.read(pc); carry = true }
            0x58 -> { bus.read(pc); interruptDisable = false }
            0x78 -> { bus.read(pc); interruptDisable = true }
            0xB8 -> { bus.read(pc); overflow = false }
            0xD8 -> { bus.read(pc); decimal = false }
            0xF8 -> { bus.read(pc); decimal = true }

            // CMP / CPX / CPY
            0xC9 -> compare(a, readPc())
            0xC5 -> compare(a, bus.read(zeroPage()))
            0xD5 -> compare(a, bus.read(zeroPageIndexed(x)))
            0xCD -> compare(a, bus.read(absolute()))
            0xDD -> compare(a, bus.read(absoluteIndexedRead(x)))
            0xD9 -> compare(a, bus.read(absoluteIndexedRead(y)))
            0xC1 -> compare(a, bus.read(indexedIndirect()))
            0xD1 -> compare(a, bus.read(indirectIndexedRead()))
            0xE0 -> compare(x, readPc())
            0xE4 -> compare(x, bus.read(zeroPage()))
            0xEC -> compare(x, bus.read(absolute()))
            0xC0 -> compare(y, readPc())
            0xC4 -> compare(y, bus.read(zeroPage()))
            0xCC -> compare(y, bus.read(absolute()))

            // DEC / INC
            0xC6 -> readModifyWrite(zeroPage()) { decrement(it) }
            0xD6 -> readModifyWrite(zeroPageIndexed(x)) { decrement(it) }
            0xCE -> readModifyWrite(absolute()) { decrement(it) }
            0xDE -> readModifyWrite(absoluteIndexedWrite(x)) { decrement(it) }
            0xE6 -> readModifyWrite(zeroPage()) { increment(it) }
            0xF6 -> readModifyWrite(zeroPageIndexed(x)) { increment(it) }
            0xEE -> readModifyWrite(absolute()) { increment(it) }
            0xFE -> readModifyWrite(absoluteIndexedWrite(x)) { increment(it) }

            0xCA -> { bus.read(pc); x = (x - 1) and 0xFF; setNz(x) }
            0x88 -> { bus.read(pc); y = (y - 1) and 0xFF; setNz(y) }
            0xE8 -> { bus.read(pc); x = (x + 1) and 0xFF; setNz(x) }
            0xC8 -> { bus.read(pc); y = (y + 1) and 0xFF; setNz(y) }

            // EOR
            0x49 -> { a = a xor readPc(); setNz(a) }
            0x45 -> { a = a xor bus.read(zeroPage()); setNz(a) }
            0x55 -> { a = a xor bus.read(zeroPageIndexed(x)); setNz(a) }
            0x4D -> { a = a xor bus.read(absolute()); setNz(a) }
            0x5D -> { a = a xor bus.read(absoluteIndexedRead(x)); setNz(a) }
            0x59 -> { a = a xor bus.read(absoluteIndexedRead(y)); setNz(a) }
            0x41 -> { a = a xor bus.read(indexedIndirect()); setNz(a) }
            0x51 -> { a = a xor bus.read(indirectIndexedRead()); setNz(a) }

            // ORA
            0x09 -> { a = a or readPc(); setNz(a) }
            0x05 -> { a = a or bus.read(zeroPage()); setNz(a) }
            0x15 -> { a = a or bus.read(zeroPageIndexed(x)); setNz(a) }
            0x0D -> { a = a or bus.read(absolute()); setNz(a) }
            0x1D -> { a = a or bus.read(absoluteIndexedRead(x)); setNz(a) }
            0x19 -> { a = a or bus.read(absoluteIndexedRead(y)); setNz(a) }
            0x01 -> { a = a or bus.read(indexedIndirect()); setNz(a) }
            0x11 -> { a = a or bus.read(indirectIndexedRead()); setNz(a) }

            0x4C -> { pc = absolute(); jumpWatcher?.invoke(instructionAt, pc) }
            0x6C -> { // JMP indirect, with the famous page-boundary bug
                val pointer = absolute()
                val lo = bus.read(pointer)
                val hi = bus.read((pointer and 0xFF00) or ((pointer + 1) and 0xFF))
                pc = lo or (hi shl 8)
                jumpWatcher?.invoke(instructionAt, pc)
            }
            0x20 -> { // JSR
                val lo = readPc()
                bus.read(0x0100 or sp)
                push(pc shr 8)
                push(pc and 0xFF)
                pc = lo or (readPc() shl 8)
                jumpWatcher?.invoke(instructionAt, pc)
            }
            0x40 -> { // RTI
                bus.read(pc)
                bus.read(0x0100 or sp)
                status = pull()
                interruptDisableSampled = interruptDisable
                pc = pull() or (pull() shl 8)
            }
            0x60 -> { // RTS
                bus.read(pc)
                bus.read(0x0100 or sp)
                pc = pull() or (pull() shl 8)
                bus.read(pc)
                pc = (pc + 1) and 0xFFFF
            }

            // LDA / LDX / LDY
            0xA9 -> { a = readPc(); setNz(a) }
            0xA5 -> { a = bus.read(zeroPage()); setNz(a) }
            0xB5 -> { a = bus.read(zeroPageIndexed(x)); setNz(a) }
            0xAD -> { a = bus.read(absolute()); setNz(a) }
            0xBD -> { a = bus.read(absoluteIndexedRead(x)); setNz(a) }
            0xB9 -> { a = bus.read(absoluteIndexedRead(y)); setNz(a) }
            0xA1 -> { a = bus.read(indexedIndirect()); setNz(a) }
            0xB1 -> { a = bus.read(indirectIndexedRead()); setNz(a) }
            0xA2 -> { x = readPc(); setNz(x) }
            0xA6 -> { x = bus.read(zeroPage()); setNz(x) }
            0xB6 -> { x = bus.read(zeroPageIndexed(y)); setNz(x) }
            0xAE -> { x = bus.read(absolute()); setNz(x) }
            0xBE -> { x = bus.read(absoluteIndexedRead(y)); setNz(x) }
            0xA0 -> { y = readPc(); setNz(y) }
            0xA4 -> { y = bus.read(zeroPage()); setNz(y) }
            0xB4 -> { y = bus.read(zeroPageIndexed(x)); setNz(y) }
            0xAC -> { y = bus.read(absolute()); setNz(y) }
            0xBC -> { y = bus.read(absoluteIndexedRead(x)); setNz(y) }

            // LSR
            0x4A -> { bus.read(pc); a = lsr(a) }
            0x46 -> readModifyWrite(zeroPage(), ::lsr)
            0x56 -> readModifyWrite(zeroPageIndexed(x), ::lsr)
            0x4E -> readModifyWrite(absolute(), ::lsr)
            0x5E -> readModifyWrite(absoluteIndexedWrite(x), ::lsr)

            0xEA -> bus.read(pc)

            // stack
            0x48 -> { bus.read(pc); push(a) }
            0x08 -> { bus.read(pc); push(status or 0x10) }
            0x68 -> { bus.read(pc); bus.read(0x0100 or sp); a = pull(); setNz(a) }
            0x28 -> { bus.read(pc); bus.read(0x0100 or sp); status = pull() }

            // ROL / ROR
            0x2A -> { bus.read(pc); a = rol(a) }
            0x26 -> readModifyWrite(zeroPage(), ::rol)
            0x36 -> readModifyWrite(zeroPageIndexed(x), ::rol)
            0x2E -> readModifyWrite(absolute(), ::rol)
            0x3E -> readModifyWrite(absoluteIndexedWrite(x), ::rol)
            0x6A -> { bus.read(pc); a = ror(a) }
            0x66 -> readModifyWrite(zeroPage(), ::ror)
            0x76 -> readModifyWrite(zeroPageIndexed(x), ::ror)
            0x6E -> readModifyWrite(absolute(), ::ror)
            0x7E -> readModifyWrite(absoluteIndexedWrite(x), ::ror)

            // SBC
            0xE9, 0xEB -> sbc(readPc())
            0xE5 -> sbc(bus.read(zeroPage()))
            0xF5 -> sbc(bus.read(zeroPageIndexed(x)))
            0xED -> sbc(bus.read(absolute()))
            0xFD -> sbc(bus.read(absoluteIndexedRead(x)))
            0xF9 -> sbc(bus.read(absoluteIndexedRead(y)))
            0xE1 -> sbc(bus.read(indexedIndirect()))
            0xF1 -> sbc(bus.read(indirectIndexedRead()))

            // STA / STX / STY
            0x85 -> bus.write(zeroPage(), a)
            0x95 -> bus.write(zeroPageIndexed(x), a)
            0x8D -> bus.write(absolute(), a)
            0x9D -> bus.write(absoluteIndexedWrite(x), a)
            0x99 -> bus.write(absoluteIndexedWrite(y), a)
            0x81 -> bus.write(indexedIndirect(), a)
            0x91 -> bus.write(indirectIndexedWrite(), a)
            0x86 -> bus.write(zeroPage(), x)
            0x96 -> bus.write(zeroPageIndexed(y), x)
            0x8E -> bus.write(absolute(), x)
            0x84 -> bus.write(zeroPage(), y)
            0x94 -> bus.write(zeroPageIndexed(x), y)
            0x8C -> bus.write(absolute(), y)

            // transfers
            0xAA -> { bus.read(pc); x = a; setNz(x) }
            0xA8 -> { bus.read(pc); y = a; setNz(y) }
            0xBA -> { bus.read(pc); x = sp; setNz(x) }
            0x8A -> { bus.read(pc); a = x; setNz(a) }
            0x9A -> { bus.read(pc); sp = x }
            0x98 -> { bus.read(pc); a = y; setNz(a) }

            // ---- undocumented ------------------------------------------------------------

            // LAX: load A and X together
            0xA7 -> lax(bus.read(zeroPage()))
            0xB7 -> lax(bus.read(zeroPageIndexed(y)))
            0xAF -> lax(bus.read(absolute()))
            0xBF -> lax(bus.read(absoluteIndexedRead(y)))
            0xA3 -> lax(bus.read(indexedIndirect()))
            0xB3 -> lax(bus.read(indirectIndexedRead()))
            0xAB -> { // LXA, unstable: the magic constant is the value hardware settles on
                val value = readPc()
                a = (a or 0xEE) and value
                x = a
                setNz(a)
            }

            // SAX: store A AND X
            0x87 -> bus.write(zeroPage(), a and x)
            0x97 -> bus.write(zeroPageIndexed(y), a and x)
            0x8F -> bus.write(absolute(), a and x)
            0x83 -> bus.write(indexedIndirect(), a and x)

            // DCP: DEC then CMP
            0xC7 -> readModifyWrite(zeroPage()) { dcp(it) }
            0xD7 -> readModifyWrite(zeroPageIndexed(x)) { dcp(it) }
            0xCF -> readModifyWrite(absolute()) { dcp(it) }
            0xDF -> readModifyWrite(absoluteIndexedWrite(x)) { dcp(it) }
            0xDB -> readModifyWrite(absoluteIndexedWrite(y)) { dcp(it) }
            0xC3 -> readModifyWrite(indexedIndirect()) { dcp(it) }
            0xD3 -> readModifyWrite(indirectIndexedWrite()) { dcp(it) }

            // ISC: INC then SBC
            0xE7 -> readModifyWrite(zeroPage()) { isc(it) }
            0xF7 -> readModifyWrite(zeroPageIndexed(x)) { isc(it) }
            0xEF -> readModifyWrite(absolute()) { isc(it) }
            0xFF -> readModifyWrite(absoluteIndexedWrite(x)) { isc(it) }
            0xFB -> readModifyWrite(absoluteIndexedWrite(y)) { isc(it) }
            0xE3 -> readModifyWrite(indexedIndirect()) { isc(it) }
            0xF3 -> readModifyWrite(indirectIndexedWrite()) { isc(it) }

            // SLO: ASL then ORA
            0x07 -> readModifyWrite(zeroPage()) { slo(it) }
            0x17 -> readModifyWrite(zeroPageIndexed(x)) { slo(it) }
            0x0F -> readModifyWrite(absolute()) { slo(it) }
            0x1F -> readModifyWrite(absoluteIndexedWrite(x)) { slo(it) }
            0x1B -> readModifyWrite(absoluteIndexedWrite(y)) { slo(it) }
            0x03 -> readModifyWrite(indexedIndirect()) { slo(it) }
            0x13 -> readModifyWrite(indirectIndexedWrite()) { slo(it) }

            // RLA: ROL then AND
            0x27 -> readModifyWrite(zeroPage()) { rla(it) }
            0x37 -> readModifyWrite(zeroPageIndexed(x)) { rla(it) }
            0x2F -> readModifyWrite(absolute()) { rla(it) }
            0x3F -> readModifyWrite(absoluteIndexedWrite(x)) { rla(it) }
            0x3B -> readModifyWrite(absoluteIndexedWrite(y)) { rla(it) }
            0x23 -> readModifyWrite(indexedIndirect()) { rla(it) }
            0x33 -> readModifyWrite(indirectIndexedWrite()) { rla(it) }

            // SRE: LSR then EOR
            0x47 -> readModifyWrite(zeroPage()) { sre(it) }
            0x57 -> readModifyWrite(zeroPageIndexed(x)) { sre(it) }
            0x4F -> readModifyWrite(absolute()) { sre(it) }
            0x5F -> readModifyWrite(absoluteIndexedWrite(x)) { sre(it) }
            0x5B -> readModifyWrite(absoluteIndexedWrite(y)) { sre(it) }
            0x43 -> readModifyWrite(indexedIndirect()) { sre(it) }
            0x53 -> readModifyWrite(indirectIndexedWrite()) { sre(it) }

            // RRA: ROR then ADC
            0x67 -> readModifyWrite(zeroPage()) { rra(it) }
            0x77 -> readModifyWrite(zeroPageIndexed(x)) { rra(it) }
            0x6F -> readModifyWrite(absolute()) { rra(it) }
            0x7F -> readModifyWrite(absoluteIndexedWrite(x)) { rra(it) }
            0x7B -> readModifyWrite(absoluteIndexedWrite(y)) { rra(it) }
            0x63 -> readModifyWrite(indexedIndirect()) { rra(it) }
            0x73 -> readModifyWrite(indirectIndexedWrite()) { rra(it) }

            0x0B, 0x2B -> { // ANC: AND, and carry follows bit 7
                a = a and readPc()
                setNz(a)
                carry = negative
            }
            0x4B -> { // ALR: AND then LSR
                a = a and readPc()
                a = lsr(a)
            }
            0x6B -> { // ARR: AND then a ROR with its own idea of the flags
                val value = a and readPc()
                a = (value shr 1) or (if (carry) 0x80 else 0)
                setNz(a)
                carry = a and 0x40 != 0
                overflow = ((a and 0x40) xor ((a and 0x20) shl 1)) != 0
            }
            0xCB -> { // SBX: (A AND X) - immediate, into X
                val value = readPc()
                val result = (a and x) - value
                carry = result >= 0
                x = result and 0xFF
                setNz(x)
            }
            0x9F -> storeHigh(absoluteIndexedWrite(y), a and x)              // SHA abs,Y
            0x93 -> storeHigh(indirectIndexedWrite(), a and x)               // SHA (zp),Y
            0x9E -> storeHigh(absoluteIndexedWrite(y), x)                    // SHX
            0x9C -> storeHigh(absoluteIndexedWrite(x), y)                    // SHY
            0x9B -> { // TAS
                val address = absoluteIndexedWrite(y)
                sp = a and x
                storeHigh(address, sp)
            }
            0xBB -> { // LAS
                val value = bus.read(absoluteIndexedRead(y)) and sp
                a = value
                x = value
                sp = value
                setNz(a)
            }
            0x8B -> { // ANE, unstable in the same way as LXA
                a = (a or 0xEE) and x and readPc()
                setNz(a)
            }

            // the undocumented NOPs, which differ only in how much they read
            0x1A, 0x3A, 0x5A, 0x7A, 0xDA, 0xFA -> bus.read(pc)
            0x80, 0x82, 0x89, 0xC2, 0xE2 -> readPc()
            0x04, 0x44, 0x64 -> bus.read(zeroPage())
            0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4 -> bus.read(zeroPageIndexed(x))
            0x0C -> bus.read(absolute())
            0x1C, 0x3C, 0x5C, 0x7C, 0xDC, 0xFC -> bus.read(absoluteIndexedRead(x))

            0x02 -> if (!bus.trap(this, (pc - 1) and 0xFFFF)) jammed = true
            else -> jammed = true // the other JAM opcodes
        }
    }

    private fun bit(value: Int) {
        zero = (a and value) and 0xFF == 0
        negative = value and 0x80 != 0
        overflow = value and 0x40 != 0
    }

    private fun lax(value: Int) {
        a = value
        x = value
        setNz(a)
    }

    private fun increment(value: Int): Int {
        val result = (value + 1) and 0xFF
        setNz(result)
        return result
    }

    private fun decrement(value: Int): Int {
        val result = (value - 1) and 0xFF
        setNz(result)
        return result
    }

    private fun dcp(value: Int): Int {
        val result = (value - 1) and 0xFF
        compare(a, result)
        return result
    }

    private fun isc(value: Int): Int {
        val result = (value + 1) and 0xFF
        sbc(result)
        return result
    }

    private fun slo(value: Int): Int {
        val result = asl(value)
        a = a or result
        setNz(a)
        return result
    }

    private fun rla(value: Int): Int {
        val result = rol(value)
        a = a and result
        setNz(a)
        return result
    }

    private fun sre(value: Int): Int {
        val result = lsr(value)
        a = a xor result
        setNz(a)
        return result
    }

    private fun rra(value: Int): Int {
        val result = ror(value)
        adc(result)
        return result
    }
}
