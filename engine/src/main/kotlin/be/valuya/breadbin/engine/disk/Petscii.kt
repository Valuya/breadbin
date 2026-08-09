package be.valuya.breadbin.engine.disk

/**
 * PETSCII is not ASCII, and the difference that matters is the letters.
 *
 * Typing an unshifted letter on a C64 produces $41 to $5A — the same codes ASCII uses for capitals
 * — and the default character set draws them as capitals. Shift produces $C1 to $DA, which draw as
 * graphics in that set and as capitals in the lower-case one. The range ASCII keeps its lower case
 * in, $61 to $7A, is a third set of letter codes again.
 *
 * Getting this backwards is easy and quiet: two pieces of code that both get it wrong agree with
 * each other perfectly, and only disagree with the machine.
 */
object Petscii {

    fun toAscii(code: Int): Char = when (code) {
        in 0x41..0x5A -> code.toChar()                 // unshifted letters
        in 0x61..0x7A -> (code - 32).toChar()          // the same letters in the lower-case set
        in 0xC1..0xDA -> (code - 0x80).toChar()        // shifted letters
        in 0x20..0x3F -> code.toChar()                 // digits, punctuation and space
        0x5B -> '['
        0x5C -> '\\'
        0x5D -> ']'
        0x0D -> '\n'
        else -> if (code in 0x20..0x7E) code.toChar() else '.'
    }

    fun toAscii(codes: IntArray): String = buildString { for (code in codes) append(toAscii(code)) }

    /**
     * The code the machine would have produced had someone typed this character, which is what a
     * file name has to be made of if the machine is ever going to find the file.
     */
    fun fromAscii(character: Char): Int = when (character) {
        in 'a'..'z' -> character.code - 32
        '\n' -> 0x0D
        else -> character.code and 0xFF
    }

    fun fromAscii(text: String): IntArray = IntArray(text.length) { fromAscii(text[it]) }

    /** For showing a name from a disk to a person. */
    fun display(codes: IntArray): String = toAscii(codes).trimEnd()
}
