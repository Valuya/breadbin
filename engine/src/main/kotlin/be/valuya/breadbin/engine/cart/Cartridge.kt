package be.valuya.breadbin.engine.cart

/**
 * What the expansion port does to the machine.
 *
 * A cartridge is two ROM windows — $8000 (ROML) and $A000 or $E000 (ROMH) — plus two lines, EXROM
 * and GAME, that tell the PLA which of them to show and what to hide behind them. Anything with
 * more than 16K of ROM also watches the two I/O windows at $DE00 and $DF00 for the writes that
 * page a bank in.
 */
interface Cartridge {
    /** True when the line is high, meaning inactive. Pulling it low is what maps ROM in. */
    val exrom: Boolean
    val game: Boolean

    fun readRoml(address: Int): Int
    fun readRomh(address: Int): Int

    fun writeRoml(address: Int, value: Int) {}
    fun writeRomh(address: Int, value: Int) {}

    fun readIo1(address: Int): Int = 0xFF
    fun readIo2(address: Int): Int = 0xFF
    fun writeIo1(address: Int, value: Int) {}
    fun writeIo2(address: Int, value: Int) {}

    fun reset() {}

    /** Called by the cartridge when it changes EXROM or GAME, so the PLA can be told. */
    var onLinesChanged: (() -> Unit)?

    companion object {
        /**
         * Builds the cartridge a file describes: a .crt says which board it is, and a raw binary
         * is assumed to be a plain unbanked one.
         */
        fun of(bytes: ByteArray): Cartridge = when {
            CrtImage.isCrt(bytes) -> of(CrtImage.parse(bytes))
            else -> RawBinaryCartridge(bytes)
        }

        fun of(image: CrtImage): Cartridge = when (image.hardwareType) {
            3 -> FinalCartridgeIII(image)
            4 -> SimonsBasic(image)
            5 -> OceanType1(image)
            7 -> FunPlay(image)
            8 -> SuperGames(image)
            15 -> C64GameSystem(image)
            17 -> Dinamic(image)
            18 -> Zaxxon(image)
            19 -> MagicDesk(image)
            21 -> Comal80(image)
            32 -> EasyFlash(image)
            // Type 0 is a plain cartridge, and an unrecognised board is more likely to be a plain
            // one than anything else, so it is a better guess than refusing to run at all.
            else -> NormalCartridge(image)
        }
    }
}

/** Shared plumbing: the banks, sliced out of the CHIP packets, and the two lines. */
abstract class BankedCartridge(image: CrtImage) : Cartridge {

    protected val romlBanks: Array<IntArray>
    protected val romhBanks: Array<IntArray>

    override var onLinesChanged: (() -> Unit)? = null

    override var exrom = image.exromLine
        protected set(value) {
            if (field != value) {
                field = value
                onLinesChanged?.invoke()
            }
        }

    override var game = image.gameLine
        protected set(value) {
            if (field != value) {
                field = value
                onLinesChanged?.invoke()
            }
        }

    protected var bank = 0

    init {
        val low = sortedMapOf<Int, IntArray>()
        val high = sortedMapOf<Int, IntArray>()
        for (chip in image.chips) {
            // A 16K packet covers both windows; anything at $A000 or $E000 is a high one.
            if (chip.data.size > 0x2000) {
                low[chip.bank] = chip.data.copyOfRange(0, 0x2000)
                high[chip.bank] = chip.data.copyOfRange(0x2000, minOf(chip.data.size, 0x4000)).padTo(0x2000)
            } else if (chip.loadAddress >= 0xA000) {
                high[chip.bank] = chip.data.padTo(0x2000)
            } else {
                low[chip.bank] = chip.data.padTo(0x2000)
            }
        }
        romlBanks = banksOf(low)
        romhBanks = banksOf(high)
    }

    private fun banksOf(chips: Map<Int, IntArray>): Array<IntArray> {
        if (chips.isEmpty()) return arrayOf(IntArray(0x2000) { 0xFF })
        val count = (chips.keys.max()) + 1
        return Array(count) { chips[it] ?: IntArray(0x2000) { 0xFF } }
    }

    private fun IntArray.padTo(size: Int) =
        if (this.size >= size) this else IntArray(size) { if (it < this.size) this[it] else 0xFF }

    protected fun roml(bank: Int, address: Int) = romlBanks[bank % romlBanks.size][address and 0x1FFF]

    protected fun romh(bank: Int, address: Int) = romhBanks[bank % romhBanks.size][address and 0x1FFF]

    override fun readRoml(address: Int) = roml(bank, address)

    override fun readRomh(address: Int) = romh(bank, address)
}

/** Type 0: however much ROM fits in the windows, with nothing to page. */
class NormalCartridge(image: CrtImage) : BankedCartridge(image)

/** A raw dump with no header: 8K maps low, 16K fills both windows. */
class RawBinaryCartridge(bytes: ByteArray) : Cartridge {
    private val roml = IntArray(0x2000) { if (it < bytes.size) bytes[it].toInt() and 0xFF else 0xFF }
    private val romh = IntArray(0x2000) {
        val at = it + 0x2000
        if (at < bytes.size) bytes[at].toInt() and 0xFF else 0xFF
    }

    override val exrom = false
    override val game = bytes.size <= 0x2000
    override var onLinesChanged: (() -> Unit)? = null

    override fun readRoml(address: Int) = roml[address and 0x1FFF]
    override fun readRomh(address: Int) = romh[address and 0x1FFF]
}

/**
 * Type 5, Ocean's board and the most common one on 8-bit budget releases: any write to $DE00 pages
 * one of up to 64 8K banks into $8000. The 512K carts put their upper half at $A000 instead.
 */
class OceanType1(image: CrtImage) : BankedCartridge(image) {
    override fun writeIo1(address: Int, value: Int) {
        bank = value and 0x3F
    }

    override fun readRomh(address: Int) = roml(bank, address)
}

/** Type 19, Magic Desk and the boards that copied it: bank in the low bits, bit 7 unmaps the ROM. */
class MagicDesk(image: CrtImage) : BankedCartridge(image) {
    override fun writeIo1(address: Int, value: Int) {
        bank = value and 0x7F
        exrom = value and 0x80 != 0
    }
}

/** Type 15, the C64 Game System: the bank is the low byte of the address that was read. */
class C64GameSystem(image: CrtImage) : BankedCartridge(image) {
    override fun readIo1(address: Int): Int {
        bank = address and 0x3F
        return 0xFF
    }

    override fun writeIo1(address: Int, value: Int) {
        bank = 0
    }
}

/** Type 17, Dinamic's board, which pages on a read rather than a write. */
class Dinamic(image: CrtImage) : BankedCartridge(image) {
    override fun readIo1(address: Int): Int {
        bank = address and 0x0F
        return 0xFF
    }
}

/** Type 7, Fun Play and Power Play, whose bank number arrives scrambled across the byte. */
class FunPlay(image: CrtImage) : BankedCartridge(image) {
    override fun writeIo1(address: Int, value: Int) {
        if (value == 0x86) {
            exrom = true
            game = true
            return
        }
        exrom = false
        game = true
        bank = ((value shr 3) and 0x07) or ((value and 0x01) shl 3)
    }
}

/** Type 8, Super Games: four 16K banks, switched from $DF00. */
class SuperGames(image: CrtImage) : BankedCartridge(image) {
    private var locked = false

    override fun writeIo2(address: Int, value: Int) {
        if (locked) return
        bank = value and 0x03
        val sixteenK = value and 0x04 == 0
        exrom = false
        game = !sixteenK
        if (value and 0x08 != 0) {
            exrom = true
            game = true
            locked = true
        }
    }

    override fun reset() {
        locked = false
        bank = 0
    }
}

/** Type 21, Comal-80: four 16K banks. */
class Comal80(image: CrtImage) : BankedCartridge(image) {
    override fun writeIo1(address: Int, value: Int) {
        bank = value and 0x03
        exrom = false
        game = false
    }
}

/**
 * Type 4, Simons' BASIC, which is 16K but spends most of its time pretending to be 8K: a write to
 * $DE00 pulls GAME low to show the second half, a read puts it back.
 */
class SimonsBasic(image: CrtImage) : BankedCartridge(image) {
    override fun readIo1(address: Int): Int {
        game = true
        return 0xFF
    }

    override fun writeIo1(address: Int, value: Int) {
        game = false
    }
}

/**
 * Type 18, Zaxxon: the 4K low ROM is mirrored across the whole $8000 window, and which half of it
 * the processor touches chooses the high bank. Reading the ROM is the bank switch.
 */
class Zaxxon(image: CrtImage) : BankedCartridge(image) {
    override fun readRoml(address: Int): Int {
        bank = if (address and 0x1000 != 0) 1 else 0
        return roml(0, address and 0x0FFF)
    }
}

/**
 * Type 3, Final Cartridge III: four 16K banks and a control register at $DFFF that also drives the
 * two lines and the freezer's NMI, which is not emulated — this runs the cartridge, not its
 * back-up features.
 */
class FinalCartridgeIII(image: CrtImage) : BankedCartridge(image) {
    private var registerLocked = false

    override fun readIo1(address: Int) = romh(bank, 0x1E00 or (address and 0xFF))

    override fun readIo2(address: Int) = romh(bank, 0x1F00 or (address and 0xFF))

    override fun writeIo2(address: Int, value: Int) {
        if (registerLocked || address and 0xFF != 0xFF) return
        bank = value and 0x03
        game = value and 0x10 != 0
        exrom = value and 0x20 != 0
        if (value and 0x40 != 0) registerLocked = true
    }

    override fun reset() {
        registerLocked = false
        bank = 0
        game = false
        exrom = false
    }
}

/**
 * Type 32, EasyFlash: 64 banks of 16K, a control register that drives both lines directly, and
 * 256 bytes of RAM at $DF00 that the cartridge's own code uses as scratch.
 *
 * The flash chips are readable but not writable here: this loads EasyFlash cartridges, it does not
 * let one reprogram itself.
 */
class EasyFlash(image: CrtImage) : BankedCartridge(image) {
    private val ram = IntArray(0x100)

    init {
        exrom = true
        game = image.gameLine
    }

    override fun writeIo1(address: Int, value: Int) {
        when (address and 0xFF) {
            0x00 -> bank = value and 0x3F
            0x02 -> {
                exrom = value and 0x02 == 0
                game = if (value and 0x04 != 0) value and 0x01 != 0 else false
            }
        }
    }

    override fun readIo2(address: Int) = ram[address and 0xFF]

    override fun writeIo2(address: Int, value: Int) {
        ram[address and 0xFF] = value and 0xFF
    }

    override fun reset() {
        bank = 0
    }
}
