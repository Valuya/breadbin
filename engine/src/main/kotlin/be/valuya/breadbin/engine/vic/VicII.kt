package be.valuya.breadbin.engine.vic

import be.valuya.breadbin.engine.mem.Memory

/**
 * The MOS 6569 (PAL) / 6567 (NTSC) video chip.
 *
 * This is the part of a C64 that games talk to constantly and rarely as documented. Two things
 * matter for compatibility and both are here: the display state machine from Christian Bauer's
 * VIC-II article — VC, VCBASE, RC, badlines and the two border flip-flops — and pixel output that
 * happens a cycle at a time rather than a line at a time, so a raster interrupt that changes the
 * background colour halfway across a line changes it halfway across the line.
 *
 * A line is built in three layers, in the order the chip builds them: graphics, then sprites over
 * the graphics, then the border over both. Drawing the border last is what lets a program that has
 * tricked the border flip-flops show sprites outside the display window.
 *
 * Sprites are evaluated once per line rather than per cycle. That is the one deliberate
 * simplification: it costs the handful of demo effects that move a sprite mid-line and keeps every
 * sprite multiplexer — which is what games actually use — working.
 */
class VicII(
    private val memory: Memory,
    val model: VideoModel = VideoModel.PAL,
) {
    /** ARGB pixels, [VideoModel.width] by [VideoModel.height]. */
    val frame = IntArray(model.width * model.height)

    /** Called once each time a frame is finished. */
    var onFrameComplete: (() -> Unit)? = null

    /** Asserted while the VIC is holding an interrupt. The machine wires this to the CPU's IRQ. */
    var irq = false
        private set

    /** True when the VIC has the bus and the processor may not run this cycle. */
    var cpuStalled = false
        private set

    /** The last byte the VIC read, which is what a read of an unmapped address gets. */
    var lastBusData = 0
        private set

    var rasterY = 0
        private set

    var cycle = 0
        private set

    // registers
    private val spriteX = IntArray(8)
    private val spriteY = IntArray(8)
    private var control1 = 0x1B
    private var control2 = 0x08
    private var rasterCompare = 0
    private var spriteEnable = 0
    private var spriteExpandY = 0
    private var spriteExpandX = 0
    private var spritePriority = 0
    private var spriteMulticolour = 0
    private var memoryPointers = 0x14
    private var interruptLatch = 0
    private var interruptEnable = 0
    private var spriteSpriteCollision = 0
    private var spriteBackgroundCollision = 0
    private var borderColour = 14
    private val backgroundColour = intArrayOf(6, 0, 0, 0)
    private val spriteMulticolourColour = intArrayOf(0, 0)
    private val spriteColour = IntArray(8)

    // display state machine
    private var videoCounter = 0
    private var videoCounterBase = 0
    private var rowCounter = 7
    private var displayState = false
    private var badLine = false

    /** Set if DEN was high during any cycle of raster line $30, which is what arms badlines. */
    private var displayEnabledThisFrame = false

    private val videoMatrix = IntArray(40)
    private val colourLine = IntArray(40)

    // border flip-flops
    private var mainBorder = true
    private var verticalBorder = true

    // the line being built, in sprite coordinates
    private val lineColour = IntArray(LINE_WIDTH)
    private val lineForeground = BooleanArray(LINE_WIDTH)
    private val lineBorder = BooleanArray(LINE_WIDTH)
    private val lineBorderColour = IntArray(LINE_WIDTH)
    private val spriteOwner = IntArray(LINE_WIDTH)

    // sprites on this line
    private val spriteData = IntArray(8)
    private val spriteActive = BooleanArray(8)
    private var spriteStallCycles = 0

    private val yScroll get() = control1 and 0x07
    private val rowSelect get() = control1 and 0x08 != 0
    private val displayEnable get() = control1 and 0x10 != 0
    private val bitmapMode get() = control1 and 0x20 != 0
    private val extendedColour get() = control1 and 0x40 != 0
    private val xScroll get() = control2 and 0x07
    private val columnSelect get() = control2 and 0x08 != 0
    private val multicolour get() = control2 and 0x10 != 0

    private val videoMatrixBase get() = (memoryPointers and 0xF0) shl 6
    private val characterBase get() = (memoryPointers and 0x0E) shl 10

    fun reset() {
        rasterY = 0
        cycle = 0
        control1 = 0x1B
        control2 = 0x08
        memoryPointers = 0x14
        interruptLatch = 0
        interruptEnable = 0
        irq = false
        videoCounter = 0
        videoCounterBase = 0
        rowCounter = 7
        displayState = false
        badLine = false
        displayEnabledThisFrame = false
        mainBorder = true
        verticalBorder = true
        java.util.Arrays.fill(frame, Palette.ARGB[0])
    }

    // ---- registers ---------------------------------------------------------------------------

    fun read(register: Int): Int = when (register) {
        in 0x00..0x0F -> if (register and 1 == 0) spriteX[register shr 1] and 0xFF else spriteY[register shr 1]
        0x10 -> (0 until 8).fold(0) { bits, i -> bits or (if (spriteX[i] and 0x100 != 0) 1 shl i else 0) }
        0x11 -> (control1 and 0x7F) or ((rasterY and 0x100) shr 1)
        0x12 -> rasterY and 0xFF
        0x13, 0x14 -> 0 // the light pen latches, which need a light pen to say anything
        0x15 -> spriteEnable
        0x16 -> control2 or 0xC0
        0x17 -> spriteExpandY
        0x18 -> memoryPointers or 0x01
        0x19 -> interruptLatch or 0x70 or (if (irq) 0x80 else 0)
        0x1A -> interruptEnable or 0xF0
        0x1B -> spritePriority
        0x1C -> spriteMulticolour
        0x1D -> spriteExpandX
        // Reading a collision register clears it, which is how a game learns of a collision once.
        0x1E -> spriteSpriteCollision.also { spriteSpriteCollision = 0 }
        0x1F -> spriteBackgroundCollision.also { spriteBackgroundCollision = 0 }
        0x20 -> borderColour or 0xF0
        in 0x21..0x24 -> backgroundColour[register - 0x21] or 0xF0
        0x25, 0x26 -> spriteMulticolourColour[register - 0x25] or 0xF0
        in 0x27..0x2E -> spriteColour[register - 0x27] or 0xF0
        else -> 0xFF
    }

    fun write(register: Int, value: Int) {
        val v = value and 0xFF
        when (register) {
            in 0x00..0x0F ->
                if (register and 1 == 0) {
                    spriteX[register shr 1] = (spriteX[register shr 1] and 0x100) or v
                } else {
                    spriteY[register shr 1] = v
                }
            0x10 -> for (i in 0 until 8) {
                spriteX[i] = (spriteX[i] and 0xFF) or (if (v and (1 shl i) != 0) 0x100 else 0)
            }
            0x11 -> {
                control1 = v
                rasterCompare = (rasterCompare and 0xFF) or ((v and 0x80) shl 1)
                // Writing $d011 mid-line can start or cancel a badline, which is the whole basis
                // of FLD and of opening the top and bottom borders.
                evaluateBadLine()
                compareRaster()
            }
            0x12 -> {
                rasterCompare = (rasterCompare and 0x100) or v
                compareRaster()
            }
            0x15 -> spriteEnable = v
            0x16 -> control2 = v
            0x17 -> spriteExpandY = v
            0x18 -> memoryPointers = v
            0x19 -> {
                interruptLatch = interruptLatch and v.inv() and 0x0F
                updateIrq()
            }
            0x1A -> {
                interruptEnable = v and 0x0F
                updateIrq()
            }
            0x1B -> spritePriority = v
            0x1C -> spriteMulticolour = v
            0x1D -> spriteExpandX = v
            0x20 -> borderColour = v and 0x0F
            in 0x21..0x24 -> backgroundColour[register - 0x21] = v and 0x0F
            0x25, 0x26 -> spriteMulticolourColour[register - 0x25] = v and 0x0F
            in 0x27..0x2E -> spriteColour[register - 0x27] = v and 0x0F
        }
    }

    private fun raiseInterrupt(bit: Int) {
        interruptLatch = interruptLatch or bit
        updateIrq()
    }

    private fun updateIrq() {
        irq = interruptLatch and interruptEnable and 0x0F != 0
    }

    // ---- the cycle ---------------------------------------------------------------------------

    /** Advances the chip by one system cycle, drawing the eight pixels that belong to it. */
    fun cycle() {
        when (cycle) {
            0 -> {
                startLine()
                if (rasterY == 0) {
                    videoCounterBase = 0
                    displayEnabledThisFrame = false
                } else {
                    // Line 0 compares a cycle later than every other line does.
                    compareRaster()
                }
            }
            1 -> if (rasterY == 0) compareRaster()
            // The video counter is reloaded from its base at the top of every line.
            14 -> {
                videoCounter = videoCounterBase
                if (badLine) rowCounter = 0
            }
        }

        if (rasterY == 0x30 && displayEnable) displayEnabledThisFrame = true
        evaluateBadLine()

        // A badline costs the processor forty cycles, and each sprite on the line costs it two
        // more. The sprite cost is charged at the top of the line rather than at the exact cycles
        // hardware uses: what timing-sensitive code notices is how much time went missing.
        cpuStalled = (badLine && cycle in FIRST_STALL_CYCLE..LAST_STALL_CYCLE) ||
            cycle < spriteStallCycles

        drawCycle()

        if (cycle == 57) {
            if (rowCounter == 7) {
                videoCounterBase = videoCounter
                if (!badLine) displayState = false
            }
            if (displayState || badLine) rowCounter = (rowCounter + 1) and 0x07
        }

        if (cycle == model.cyclesPerLine - 1) {
            // Rules 2 and 3 of the border flip-flops. Flipping RSEL just before the last line of
            // the display window is how the top and bottom borders get opened.
            if (rasterY == bottomComparison()) verticalBorder = true
            if (rasterY == topComparison() && displayEnable) verticalBorder = false
            finishLine()
            cycle = 0
            rasterY++
            if (rasterY >= model.linesPerFrame) {
                rasterY = 0
                onFrameComplete?.invoke()
            }
        } else {
            cycle++
        }
    }

    private fun compareRaster() {
        if (rasterY == rasterCompare && !rasterMatched) {
            rasterMatched = true
            raiseInterrupt(0x01)
        }
    }

    private var rasterMatched = false

    private fun evaluateBadLine() {
        val wasBad = badLine
        badLine = displayEnabledThisFrame &&
            rasterY in 0x30..0xF7 &&
            (rasterY and 0x07) == yScroll
        if (badLine && !wasBad) displayState = true
    }

    private fun startLine() {
        rasterMatched = false
        java.util.Arrays.fill(lineForeground, false)
        java.util.Arrays.fill(lineBorder, false)
        latchSprites()
    }

    private fun busRead(address: Int): Int {
        val value = memory.vicRead(address and 0x3FFF)
        lastBusData = value
        return value
    }

    // ---- pixels ------------------------------------------------------------------------------

    private fun xOfCycle(cycle: Int) = (X_AT_CYCLE_ZERO + cycle * 8) and 0x1FF

    private fun leftComparison() = if (columnSelect) 24 else 31
    private fun rightComparison() = if (columnSelect) 344 else 335
    private fun topComparison() = if (rowSelect) 51 else 55
    private fun bottomComparison() = if (rowSelect) 251 else 247

    /**
     * The eight pixels this cycle is responsible for: first the border flip-flops, which have to
     * see every pixel position in order and exactly once, then the graphics underneath them.
     */
    private fun drawCycle() {
        val x0 = xOfCycle(cycle)
        // None of the four edges can move inside a cycle: they come from two register bits, and the
        // processor is not running while these eight pixels are drawn. Read once rather than once
        // per pixel — at eight pixels a cycle these four were being worked out thirty million times
        // a second to give the same four answers.
        val right = rightComparison()
        val left = leftComparison()
        val bottom = bottomComparison()
        val top = topComparison()

        // Almost every cycle is wholly inside the border or wholly inside the picture: only two
        // cycles a line contain an edge at all, and only one wraps past pixel 511. When the flip
        // flop cannot move across these eight pixels there is nothing to decide per pixel — either
        // eight identical writes, which the run-fill does far faster, or none at all.
        if (x0 + 7 < 0x200 && right !in x0..x0 + 7 && left !in x0..x0 + 7) {
            if (mainBorder) {
                java.util.Arrays.fill(lineBorder, x0, x0 + 8, true)
                java.util.Arrays.fill(lineBorderColour, x0, x0 + 8, borderColour)
            }
        } else {
            for (i in 0 until 8) {
                val x = (x0 + i) and 0x1FF
                // Rule 1: the right edge sets the main flip-flop.
                if (x == right) mainBorder = true
                // Rules 4, 5 and 6, all of which fire at the left edge.
                if (x == left) {
                    if (rasterY == bottom) verticalBorder = true
                    if (rasterY == top && displayEnable) verticalBorder = false
                    if (!verticalBorder) mainBorder = false
                }
                if (mainBorder) {
                    lineBorder[x] = true
                    lineBorderColour[x] = borderColour
                }
            }
        }

        val column = cycle - FIRST_GRAPHICS_CYCLE
        if (column in 0..39) {
            if (badLine) fetchVideoMatrix(column)
            drawGraphics(column, x0)
        } else {
            for (i in 0 until 8) lineColour[(x0 + i) and 0x1FF] = backgroundColour[0]
        }
    }

    private fun fetchVideoMatrix(column: Int) {
        videoMatrix[column] = busRead(videoMatrixBase or (videoCounter and 0x3FF))
        colourLine[column] = memory.colorRam[videoCounter and 0x3FF] and 0x0F
    }

    /**
     * One character cell. The horizontal scroll simply moves the eight pixels to the right, which
     * is why a scrolled screen shows background colour in the leftmost few pixels of the window.
     */
    private fun drawGraphics(column: Int, x0: Int) {
        val start = x0 + xScroll
        if (!displayState) {
            // Idle state: the chip keeps fetching, from $3fff, and shows the result in black.
            val idle = busRead(if (extendedColour) 0x39FF else 0x3FFF)
            for (i in 0 until 8) {
                val x = (start + i) and 0x1FF
                val set = idle and (0x80 shr i) != 0
                lineColour[x] = if (set) 0 else backgroundColour[0]
                lineForeground[x] = set
            }
            return
        }

        val character = videoMatrix[column]
        val colour = colourLine[column]
        val data = when {
            bitmapMode -> busRead((characterBase and 0x2000) or ((videoCounter and 0x3FF) shl 3) or rowCounter)
            extendedColour -> busRead(characterBase or ((character and 0x3F) shl 3) or rowCounter)
            else -> busRead(characterBase or (character shl 3) or rowCounter)
        }
        videoCounter = (videoCounter + 1) and 0x3FF

        // Ordinary text, which is what almost everything on the screen almost always is. Deciding
        // the mode once instead of inside every pixel is worth doing here and nowhere else: this
        // loop runs eight times a cycle for forty cycles of every line of every frame.
        if (!bitmapMode && !extendedColour && !(multicolour && colour and 0x08 != 0)) {
            val ink = colour and 0x0F
            val paper = backgroundColour[0] and 0x0F
            for (i in 0 until 8) {
                val x = (start + i) and 0x1FF
                val set = data and (0x80 shr i) != 0
                lineColour[x] = if (set) ink else paper
                lineForeground[x] = set
            }
            return
        }

        for (i in 0 until 8) {
            val x = (start + i) and 0x1FF
            val pixel = pixelFor(character, colour, data, i)
            lineColour[x] = pixel and 0x0F
            lineForeground[x] = pixel and FOREGROUND != 0
        }
    }

    /** Returns a colour index with [FOREGROUND] set when the pixel counts as foreground. */
    private fun pixelFor(character: Int, colour: Int, data: Int, bit: Int): Int {
        val multicolourText = multicolour && colour and 0x08 != 0
        return when {
            bitmapMode && multicolour -> when ((data shr (6 - (bit and 0x06))) and 0x03) {
                0 -> backgroundColour[0]
                1 -> ((character shr 4) and 0x0F)
                2 -> (character and 0x0F) or FOREGROUND
                else -> colour or FOREGROUND
            }
            bitmapMode -> {
                val set = data and (0x80 shr bit) != 0
                if (set) ((character shr 4) and 0x0F) or FOREGROUND else character and 0x0F
            }
            multicolourText -> when ((data shr (6 - (bit and 0x06))) and 0x03) {
                0 -> backgroundColour[0]
                1 -> backgroundColour[1]
                2 -> backgroundColour[2] or FOREGROUND
                else -> (colour and 0x07) or FOREGROUND
            }
            extendedColour -> {
                val set = data and (0x80 shr bit) != 0
                if (set) colour or FOREGROUND else backgroundColour[(character shr 6) and 0x03]
            }
            else -> {
                val set = data and (0x80 shr bit) != 0
                if (set) colour or FOREGROUND else backgroundColour[0]
            }
        }
    }

    // ---- sprites -----------------------------------------------------------------------------

    /** Works out which sprites are on this line and fetches their three bytes for it. */
    private fun latchSprites() {
        var stolen = 0
        for (i in 0 until 8) {
            spriteActive[i] = false
            if (spriteEnable and (1 shl i) == 0) continue
            val expanded = spriteExpandY and (1 shl i) != 0
            val height = if (expanded) 42 else 21
            val top = spriteY[i]
            if (rasterY < top || rasterY >= top + height) continue
            val row = if (expanded) (rasterY - top) shr 1 else rasterY - top
            val pointer = busRead(videoMatrixBase or 0x3F8 or i)
            val base = (pointer shl 6) + row * 3
            spriteData[i] = (busRead(base) shl 16) or (busRead(base + 1) shl 8) or busRead(base + 2)
            spriteActive[i] = true
            stolen += 2
        }
        // Never take so much that the graphics fetch would be pushed off the line.
        spriteStallCycles = minOf(stolen, FIRST_STALL_CYCLE)
    }

    /**
     * Draws the line's sprites, lowest number in front, and records the collisions. Collisions are
     * recorded even where the border will cover the sprite, because the chip compares the pixels
     * before the border is applied.
     */
    private fun compositeSprites() {
        var spriteSprite = 0
        var spriteBackground = 0
        java.util.Arrays.fill(spriteOwner, -1)

        for (i in 7 downTo 0) {
            if (!spriteActive[i]) continue
            val data = spriteData[i]
            val multi = spriteMulticolour and (1 shl i) != 0
            val expandX = spriteExpandX and (1 shl i) != 0
            val behind = spritePriority and (1 shl i) != 0
            val width = if (expandX) 48 else 24
            val startX = spriteX[i]

            for (pixel in 0 until width) {
                val bit = if (expandX) pixel shr 1 else pixel
                val x = (startX + pixel) and 0x1FF
                val colour = if (multi) {
                    when ((data shr (22 - (bit and 0x1E))) and 0x03) {
                        0 -> -1
                        1 -> spriteMulticolourColour[0]
                        2 -> spriteColour[i]
                        else -> spriteMulticolourColour[1]
                    }
                } else {
                    if (data and (0x800000 shr bit) != 0) spriteColour[i] else -1
                }
                if (colour < 0) continue
                if (spriteOwner[x] >= 0) {
                    spriteSprite = spriteSprite or (1 shl i) or (1 shl spriteOwner[x])
                }
                spriteOwner[x] = i
                if (lineForeground[x]) spriteBackground = spriteBackground or (1 shl i)
                if (!(behind && lineForeground[x])) lineColour[x] = colour
            }
        }

        if (spriteSprite != 0) {
            val wasEmpty = spriteSpriteCollision == 0
            spriteSpriteCollision = spriteSpriteCollision or spriteSprite
            if (wasEmpty) raiseInterrupt(0x04)
        }
        if (spriteBackground != 0) {
            val wasEmpty = spriteBackgroundCollision == 0
            spriteBackgroundCollision = spriteBackgroundCollision or spriteBackground
            if (wasEmpty) raiseInterrupt(0x02)
        }
    }

    private fun finishLine() {
        compositeSprites()
        val row = rasterY - model.firstVisibleLine
        if (row < 0 || row >= model.height) return
        var target = row * model.width
        var x = model.firstVisibleX
        repeat(model.width) {
            val position = x and 0x1FF
            val index = if (lineBorder[position]) lineBorderColour[position] else lineColour[position]
            frame[target++] = Palette.ARGB[index]
            x++
        }
    }

    private companion object {
        const val LINE_WIDTH = 512

        /**
         * Where the beam is at the start of a line, in the coordinates sprites use, chosen so that
         * cycle 16 — the first video matrix fetch — puts its pixel at X 24, the left edge of the
         * display window.
         */
        const val X_AT_CYCLE_ZERO = 408

        const val FIRST_GRAPHICS_CYCLE = 16
        const val FIRST_STALL_CYCLE = 15
        const val LAST_STALL_CYCLE = 54

        /** Carried alongside a colour index while a pixel is worked out; never stored in the line. */
        const val FOREGROUND = 0x100
    }
}
