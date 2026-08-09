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
        fun identify(bytes: ByteArray): RomKind? = when {
            bytes.size == CHARACTER_SIZE -> RomKind.CHARACTER
            bytes.size != BASIC_SIZE -> null
            // The KERNAL's reset vector at $FFFC points into the KERNAL itself ($FCE2 on a stock
            // machine), so the last six bytes are the give-away.
            (bytes[0x1FFD].toInt() and 0xFF) >= 0xE0 -> RomKind.KERNAL
            else -> RomKind.BASIC
        }
    }
}

enum class RomKind { BASIC, KERNAL, CHARACTER }
