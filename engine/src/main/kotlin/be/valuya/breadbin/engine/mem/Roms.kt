package be.valuya.breadbin.engine.mem

/**
 * The three Commodore ROMs the machine cannot run without.
 *
 * They are not shipped with this emulator — they are still someone's copyright — so the app asks
 * for them once and keeps them. [describe] is what the setup screen shows about a file the user
 * has just picked.
 */
class Roms(
    val basic: IntArray,
    val kernal: IntArray,
    val character: IntArray,
) {
    init {
        require(basic.size == 0x2000) { "the BASIC ROM is 8192 bytes, not ${basic.size}" }
        require(kernal.size == 0x2000) { "the KERNAL ROM is 8192 bytes, not ${kernal.size}" }
        require(character.size == 0x1000) { "the character ROM is 4096 bytes, not ${character.size}" }
    }

    companion object {
        const val BASIC_SIZE = 0x2000
        const val KERNAL_SIZE = 0x2000
        const val CHARACTER_SIZE = 0x1000
        const val DRIVE_SIZE = 0x4000

        fun of(basic: ByteArray, kernal: ByteArray, character: ByteArray) =
            Roms(basic.toUnsigned(), kernal.toUnsigned(), character.toUnsigned())

        private fun ByteArray.toUnsigned() = IntArray(size) { this[it].toInt() and 0xFF }

        /**
         * What a ROM file looks like, so the setup screen can tell the user which of the three
         * they just handed over instead of making them get the order right.
         *
         * The checks are deliberately shallow — a size and a couple of bytes — because plenty of
         * perfectly good dumps differ from the ones Commodore shipped (a fixed KERNAL, a JiffyDOS
         * replacement), and refusing those would be unhelpful.
         */
        /**
         * What a ROM is, in as much detail as it will admit to.
         *
         * A KERNAL says so itself: Commodore put the revision number at $FF80, which is the last
         * word on the subject and works for revisions nobody here has ever seen. Nothing else has
         * anything so convenient, so the rest get a checksum — not to look up, but to tell two
         * files apart and to give the user something to search for.
         */
        fun describe(kind: RomKind, bytes: ByteArray): String {
            val checksum = "CRC %08X".format(crc32(bytes))
            if (kind == RomKind.DRIVE) {
                return if (driveRomPassesSelfTest(bytes)) checksum
                else "$checksum — fails its own self-test, so it will not be used"
            }
            if (kind != RomKind.KERNAL || bytes.size != KERNAL_SIZE) return checksum
            val revision = bytes[0xFF80 - 0xE000].toInt() and 0xFF
            val name = KERNAL_REVISIONS[revision] ?: return "revision \$%02X, %s".format(revision, checksum)
            return "$name, $checksum"
        }

        /**
         * What the byte at $FF80 means. Revision three is the one to want: it is the last, it is
         * what almost everything was tested against, and revision one has the serial bus bug that
         * some software will not load past.
         */
        private val KERNAL_REVISIONS = mapOf(
            0xAA to "Commodore revision 1 (901227-01)",
            0x00 to "Commodore revision 2 (901227-02)",
            0x03 to "Commodore revision 3 (901227-03)",
            0x43 to "Commodore SX-64 (251104-04)",
            0x64 to "Commodore C64 GS (390852-01)",
        )

        /**
         * Whether a 1541 DOS would survive its own power-on test.
         *
         * The drive checks its ROM before it does anything else, by summing each half and comparing
         * the total against that half's own page number. A ROM that fails does not report anything:
         * the drive blinks its LED for ever in a loop that never touches the serial bus, so from
         * the computer's side it is simply a drive that is not there and a LOAD that says SEARCHING
         * until somebody gives up. Worth knowing before handing it to the emulated drive.
         *
         * Rebuilt ROMs get this wrong routinely — the reconstruction everybody uses leaves the two
         * checksum bytes at zero — so this is not a hypothetical.
         */
        fun driveRomPassesSelfTest(bytes: ByteArray): Boolean =
            bytes.size == DRIVE_SIZE &&
                halfSum(bytes, 0x00) == 0xE0 &&
                halfSum(bytes, 0xE0) == 0xC0

        /** The drive's own checksum: thirty-two pages added up backwards, carries and all. */
        private fun halfSum(bytes: ByteArray, startPage: Int): Int {
            var total = 0
            var carry = 0
            var page = startPage
            repeat(32) {
                page = (page - 1) and 0xFF
                val base = (page shl 8) - 0xC000
                for (i in 0 until 256) {
                    val sum = total + (bytes[base + i].toInt() and 0xFF) + carry
                    total = sum and 0xFF
                    carry = if (sum > 0xFF) 1 else 0
                }
            }
            return (total + carry) and 0xFF
        }

        /** Whether this is one of Commodore's own KERNALs, rather than a replacement. */
        fun isCommodoreKernal(bytes: ByteArray) =
            bytes.size == KERNAL_SIZE && (bytes[0xFF80 - 0xE000].toInt() and 0xFF) in KERNAL_REVISIONS

        fun crc32(bytes: ByteArray): Long =
            java.util.zip.CRC32().apply { update(bytes) }.value

        fun identify(bytes: ByteArray): RomKind? = when {
            bytes.size == CHARACTER_SIZE -> RomKind.CHARACTER
            bytes.size == DRIVE_SIZE -> RomKind.DRIVE
            bytes.size != BASIC_SIZE -> null
            // The KERNAL's reset vector at $FFFC points into the KERNAL itself ($FCE2 on a stock
            // machine), so the last six bytes are the give-away.
            (bytes[0x1FFD].toInt() and 0xFF) >= 0xE0 -> RomKind.KERNAL
            else -> RomKind.BASIC
        }
    }
}

/**
 * A ROM the emulator can be given.
 *
 * The first three are the machine itself and it will not start without them. The fourth is the
 * 1541's own DOS, which is a different computer's ROM entirely: with it the drive is emulated down
 * to its processor and fast loaders work, and without it there is still a drive, just not that one.
 */
enum class RomKind(val size: Int, val required: Boolean) {
    BASIC(Roms.BASIC_SIZE, true),
    KERNAL(Roms.KERNAL_SIZE, true),
    CHARACTER(Roms.CHARACTER_SIZE, true),
    DRIVE(Roms.DRIVE_SIZE, false),
}
