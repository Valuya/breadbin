package be.valuya.breadbin.engine.cart

/**
 * A .crt file: a small header saying how the cartridge is wired, followed by one CHIP packet per
 * ROM the board carries.
 *
 * The format exists because a C64 cartridge is not just a ROM — it is a ROM plus whatever logic
 * the publisher used to page more than 16K into a 16K window, and there were dozens of those.
 */
class CrtImage(
    val hardwareType: Int,
    val exromLine: Boolean,
    val gameLine: Boolean,
    val name: String,
    val chips: List<ChipPacket>,
) {
    class ChipPacket(
        val bank: Int,
        val loadAddress: Int,
        val data: IntArray,
    )

    companion object {
        private const val SIGNATURE = "C64 CARTRIDGE   "

        fun isCrt(bytes: ByteArray): Boolean =
            bytes.size >= 0x40 && String(bytes, 0, 16, Charsets.US_ASCII) == SIGNATURE

        fun parse(bytes: ByteArray): CrtImage {
            require(isCrt(bytes)) { "not a .crt file" }
            val headerLength = bytes.beInt(0x10)
            require(headerLength >= 0x40) { "the header claims to be $headerLength bytes" }
            val hardwareType = bytes.beShort(0x16)
            // The file stores each line as the port sees it: 0 is pulled low, which is the active
            // state, and the rest of this code calls a line "true" when it is high and inactive.
            val exrom = bytes[0x18].toInt() and 0xFF != 0
            val game = bytes[0x19].toInt() and 0xFF != 0
            val name = String(bytes, 0x20, 32, Charsets.US_ASCII).trim { it <= ' ' }

            val chips = mutableListOf<ChipPacket>()
            var offset = headerLength
            while (offset + 0x10 <= bytes.size) {
                if (String(bytes, offset, 4, Charsets.US_ASCII) != "CHIP") break
                val packetLength = bytes.beInt(offset + 4)
                val bank = bytes.beShort(offset + 10)
                val loadAddress = bytes.beShort(offset + 12)
                val imageSize = bytes.beShort(offset + 14)
                val start = offset + 0x10
                val end = minOf(start + imageSize, bytes.size)
                if (end > start) {
                    chips += ChipPacket(
                        bank = bank,
                        loadAddress = loadAddress,
                        data = IntArray(end - start) { bytes[start + it].toInt() and 0xFF },
                    )
                }
                if (packetLength <= 0) break
                offset += maxOf(packetLength, 0x10)
            }
            require(chips.isNotEmpty()) { "the cartridge holds no ROM" }
            return CrtImage(hardwareType, exrom, game, name, chips)
        }

        private fun ByteArray.beShort(at: Int) =
            ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

        private fun ByteArray.beInt(at: Int) = (beShort(at) shl 16) or beShort(at + 2)
    }
}
