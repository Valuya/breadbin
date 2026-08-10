package be.valuya.breadbin.engine.mem

import be.valuya.breadbin.engine.cart.Cartridge
import be.valuya.breadbin.engine.cia.Cia
import be.valuya.breadbin.engine.sid.Sid
import be.valuya.breadbin.engine.vic.VicII

/**
 * The 64K address space, the PLA that decides what is visible in it, and the I/O dispatch.
 *
 * The C64 has more addressable hardware than address space, and the PLA arbitrates using three
 * bits of the CPU's own I/O port at $0001 plus the two lines a cartridge pulls. Writes almost
 * always land in the RAM underneath whatever ROM happens to be banked in, which is why a program
 * can poke the screen while BASIC is visible at the same addresses.
 */
class Memory(private val roms: Roms) {

    val ram = IntArray(0x10000)

    /** Colour RAM is 1024 nybbles; the top four bits of a read come off the floating bus. */
    val colorRam = IntArray(0x400)

    lateinit var vic: VicII
    lateinit var sid: Sid
    lateinit var cia1: Cia
    lateinit var cia2: Cia

    var cartridge: Cartridge? = null
        set(value) {
            field = value
            updateBanking()
        }

    /** Direction bits of the 6510's own port. Bits set here are outputs. */
    private var portDirection = 0x2F

    /** Latched output data of the 6510's port. */
    private var portData = 0x37

    private var loram = true
    private var hiram = true
    private var charen = true

    /** Notified when the datasette motor line or the write line changes. */
    var onPortWrite: ((data: Int, direction: Int) -> Unit)? = null

    /** Read by the port when the cassette sense line is asserted: false means a key is down. */
    var cassetteSense = true

    private var basicVisible = true
    private var kernalVisible = true

    /** Whether a jump to $E000 or above lands in the KERNAL rather than in the RAM beneath it. */
    val kernalIsVisible get() = kernalVisible

    /** The same question for $A000..$BFFF and BASIC. */
    val basicIsVisible get() = basicVisible

    private var charVisible = false
    private var ioVisible = true
    private var romlVisible = false
    private var romhVisible = false
    private var ultimax = false

    fun reset() {
        portDirection = 0x2F
        portData = 0x37
        java.util.Arrays.fill(colorRam, 0)
        // Uninitialised DRAM is not uniform: it comes up in alternating blocks of $00 and $FF,
        // and a few programs (and the KERNAL's own RAM test) can tell the difference.
        for (address in 0 until 0x10000) {
            ram[address] = if (address and 0x40 != 0) 0xFF else 0x00
        }
        updateBanking()
    }

    /** Re-runs the PLA, which a cartridge needs after it changes EXROM or GAME. */
    fun refreshBanking() = updateBanking()

    private fun updateBanking() {
        // Lines configured as inputs float high through the port's pull-ups, so they read as set.
        val effective = (portData and portDirection) or (portDirection.inv() and 0x07)
        loram = effective and 0x01 != 0
        hiram = effective and 0x02 != 0
        charen = effective and 0x04 != 0

        val cart = cartridge
        val exrom = cart?.exrom ?: true
        val game = cart?.game ?: true

        ultimax = exrom && !game
        if (ultimax) {
            basicVisible = false
            kernalVisible = false
            charVisible = false
            ioVisible = true
            romlVisible = true
            romhVisible = true
            return
        }
        romlVisible = !exrom && loram && hiram
        romhVisible = !exrom && !game && hiram
        basicVisible = !romhVisible && loram && hiram
        kernalVisible = hiram
        val ramOverIo = !loram && !hiram
        charVisible = !ramOverIo && !charen
        ioVisible = !ramOverIo && charen
    }

    fun read(address: Int): Int {
        val a = address and 0xFFFF
        return when (a shr 12) {
            0x0 -> when (a) {
                0x0000 -> portDirection
                0x0001 -> readPort()
                else -> ram[a]
            }
            in 0x1..0x7 -> if (ultimax) openBus(a) else ram[a]
            0x8, 0x9 -> if (romlVisible) cartridge!!.readRoml(a) else if (ultimax) openBus(a) else ram[a]
            0xA, 0xB -> when {
                ultimax -> openBus(a)
                romhVisible -> cartridge!!.readRomh(a)
                basicVisible -> roms.basic[a - 0xA000]
                else -> ram[a]
            }
            0xC -> if (ultimax) openBus(a) else ram[a]
            0xD -> when {
                ioVisible -> readIo(a)
                charVisible -> roms.character[a - 0xD000]
                else -> ram[a]
            }
            else -> when {
                ultimax -> cartridge!!.readRomh(a)
                kernalVisible -> roms.kernal[a - 0xE000]
                else -> ram[a]
            }
        }
    }

    fun write(address: Int, value: Int) {
        val a = address and 0xFFFF
        val v = value and 0xFF
        when (a shr 12) {
            0x0 -> when (a) {
                0x0000 -> {
                    portDirection = v
                    updateBanking()
                    onPortWrite?.invoke(portData, portDirection)
                }
                0x0001 -> {
                    portData = v
                    updateBanking()
                    onPortWrite?.invoke(portData, portDirection)
                }
                else -> ram[a] = v
            }
            0x8, 0x9 -> {
                // A cartridge can watch writes into its own window even though the write itself
                // still lands in RAM: EasyFlash and friends bank that way.
                if (romlVisible || ultimax) cartridge?.writeRoml(a, v)
                ram[a] = v
            }
            0xA, 0xB -> {
                if (romhVisible) cartridge?.writeRomh(a, v)
                ram[a] = v
            }
            0xD -> if (ioVisible) writeIo(a, v) else ram[a] = v
            0xE, 0xF -> {
                if (ultimax) cartridge?.writeRomh(a, v)
                ram[a] = v
            }
            else -> ram[a] = v
        }
    }

    private fun readPort(): Int {
        var input = 0x17
        // Bit 4 is the cassette sense line, held low while a datasette key is pressed.
        if (!cassetteSense) input = input and 0x10.inv()
        return (portData and portDirection) or (input and portDirection.inv())
    }

    /**
     * Reading somewhere nothing answers gives whatever the VIC last put on the bus, which is what
     * "open bus" means on this machine.
     */
    private fun openBus(address: Int): Int = vic.lastBusData

    private fun readIo(address: Int): Int = when ((address shr 8) and 0x0F) {
        0x0, 0x1, 0x2, 0x3 -> vic.read(address and 0x3F)
        0x4, 0x5, 0x6, 0x7 -> sid.read(address and 0x1F)
        0x8, 0x9, 0xA, 0xB -> colorRam[address and 0x3FF] or (vic.lastBusData and 0xF0)
        0xC -> cia1.read(address and 0x0F)
        0xD -> cia2.read(address and 0x0F)
        0xE -> cartridge?.readIo1(address) ?: openBus(address)
        else -> cartridge?.readIo2(address) ?: openBus(address)
    }

    private fun writeIo(address: Int, value: Int) {
        when ((address shr 8) and 0x0F) {
            0x0, 0x1, 0x2, 0x3 -> vic.write(address and 0x3F, value)
            0x4, 0x5, 0x6, 0x7 -> sid.write(address and 0x1F, value)
            0x8, 0x9, 0xA, 0xB -> colorRam[address and 0x3FF] = value and 0x0F
            0xC -> cia1.write(address and 0x0F, value)
            0xD -> cia2.write(address and 0x0F, value)
            0xE -> cartridge?.writeIo1(address, value)
            else -> cartridge?.writeIo2(address, value)
        }
    }

    /**
     * What the VIC sees, which is not what the CPU sees: it addresses one 16K bank chosen by CIA 2,
     * always reads RAM, and finds the character ROM mirrored into $1000-$1FFF of banks 0 and 2.
     */
    fun vicRead(address: Int): Int {
        val bank = (cia2.portA.inv() and 0x03) shl 14
        val full = bank or (address and 0x3FFF)
        val cart = cartridge
        if (ultimax && cart != null && (address and 0x3FFF) in 0x3000..0x3FFF) {
            return cart.readRomh(0xF000 or (address and 0x0FFF))
        }
        if ((address and 0x3FFF) in 0x1000..0x1FFF && (bank shr 14) and 0x01 == 0) {
            return roms.character[address and 0x0FFF]
        }
        return ram[full]
    }

    /** Direct RAM access for loaders and snapshots, bypassing the banking entirely. */
    fun poke(address: Int, value: Int) {
        ram[address and 0xFFFF] = value and 0xFF
    }

    fun peek(address: Int): Int = ram[address and 0xFFFF]
}
