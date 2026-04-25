/*
 * Velune Web Radio — QrEncoder
 *
 * Compact pure-Kotlin QR Code encoder. No native deps, no Gradle changes.
 *
 * Supports byte-mode payloads (UTF-8) at error-correction level L–H,
 * automatic version selection 1..40. Output is a square boolean matrix
 * where `true` = dark module.
 *
 * Algorithm port of the public-domain Project Nayuki QR encoder
 * (https://www.nayuki.io/page/qr-code-generator-library).
 */

package com.nikhil.yt.radio.qr

/** Error-correction level. Higher = more redundancy, more modules. */
enum class QrEcc(internal val ordinalForFormat: Int) {
    Low(1), Medium(0), Quartile(3), High(2);
}

/** Result of encoding: a [size]×[size] matrix. */
class QrCode(val size: Int, private val modules: BooleanArray) {
    /** True if module at (x, y) is dark. (0,0) is the top-left. */
    fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}

object QrEncoder {

    /** Encode [text] as UTF-8 bytes at level [ecc]. */
    fun encode(text: String, ecc: QrEcc = QrEcc.Medium): QrCode {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = chooseVersion(data.size, ecc)
        return encodeBytes(data, version, ecc)
    }

    // -------------------------------------------------------------- internals

    private fun chooseVersion(byteCount: Int, ecc: QrEcc): Int {
        for (v in 1..40) {
            val capacity = capacityBits(v, ecc) / 8
            // Mode indicator (4 bits) + char count indicator + payload
            val cci = if (v < 10) 8 else 16
            val needed = byteCount + 2 + (cci / 8) // approximate, conservative
            if (capacity >= needed) return v
        }
        error("Data too long for QR code (${byteCount} bytes)")
    }

    private fun capacityBits(version: Int, ecc: QrEcc): Int {
        val totalCw = totalDataCodewords(version, ecc)
        return totalCw * 8
    }

    /**
     * The big one — data → matrix.
     */
    private fun encodeBytes(data: ByteArray, version: Int, ecc: QrEcc): QrCode {
        // 1. Build the bit stream.
        val bb = BitBuffer()
        bb.append(0b0100, 4)                                  // Byte mode
        val cciLen = if (version < 10) 8 else 16
        bb.append(data.size, cciLen)                          // Char count
        for (b in data) bb.append(b.toInt() and 0xFF, 8)      // Payload

        val totalDataBits = totalDataCodewords(version, ecc) * 8
        // Terminator: up to 4 zero bits
        bb.append(0, minOf(4, totalDataBits - bb.size))
        // Pad to byte boundary
        bb.append(0, (8 - bb.size % 8) % 8)
        // Pad with alternating 0xEC, 0x11 until full
        var pad = 0xEC
        while (bb.size < totalDataBits) {
            bb.append(pad, 8)
            pad = pad xor (0xEC xor 0x11)
        }

        // 2. Codewords + ECC, interleaved per QR spec.
        val allCodewords = addEccAndInterleave(bb.toBytes(), version, ecc)

        // 3. Place modules into the matrix.
        val size = version * 4 + 17
        val modules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)
        drawFunctionPatterns(modules, isFunction, version)
        drawCodewords(modules, isFunction, allCodewords, size)

        // 4. Pick the best mask, apply, and write format/version info.
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            applyMask(modules, isFunction, size, mask)
            drawFormatBits(modules, isFunction, size, ecc, mask)
            val p = penaltyScore(modules, size)
            if (p < bestPenalty) {
                bestPenalty = p
                bestMask = mask
            }
            applyMask(modules, isFunction, size, mask) // XOR is its own inverse
        }
        applyMask(modules, isFunction, size, bestMask)
        drawFormatBits(modules, isFunction, size, ecc, bestMask)
        if (version >= 7) drawVersion(modules, isFunction, size, version)

        return QrCode(size, modules)
    }

    // ---- Function patterns -------------------------------------------------

    private fun drawFunctionPatterns(modules: BooleanArray, isFn: BooleanArray, version: Int) {
        val size = version * 4 + 17
        // Timing patterns
        for (i in 0 until size) {
            setFn(modules, isFn, size, 6, i, i % 2 == 0)
            setFn(modules, isFn, size, i, 6, i % 2 == 0)
        }
        // Three finder patterns
        drawFinder(modules, isFn, size, 3, 3)
        drawFinder(modules, isFn, size, size - 4, 3)
        drawFinder(modules, isFn, size, 3, size - 4)
        // Alignment patterns
        val align = alignmentPositions(version)
        for (i in align.indices) for (j in align.indices) {
            // Skip overlap with finder patterns
            if ((i == 0 && j == 0) ||
                (i == 0 && j == align.size - 1) ||
                (i == align.size - 1 && j == 0)) continue
            drawAlignment(modules, isFn, size, align[i], align[j])
        }
        // Reserve format info regions
        for (i in 0..8) {
            setFn(modules, isFn, size, i, 8, false)
            setFn(modules, isFn, size, 8, i, false)
            setFn(modules, isFn, size, size - 1 - i, 8, false)
            setFn(modules, isFn, size, 8, size - 1 - i, false)
        }
        setFn(modules, isFn, size, 8, size - 8, true) // dark module
    }

    private fun drawFinder(m: BooleanArray, isFn: BooleanArray, size: Int, cx: Int, cy: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val x = cx + dx; val y = cy + dy
            if (x !in 0 until size || y !in 0 until size) continue
            val d = maxOf(Math.abs(dx), Math.abs(dy))
            setFn(m, isFn, size, x, y, d != 2 && d != 4)
        }
    }

    private fun drawAlignment(m: BooleanArray, isFn: BooleanArray, size: Int, cx: Int, cy: Int) {
        for (dy in -2..2) for (dx in -2..2) {
            val d = maxOf(Math.abs(dx), Math.abs(dy))
            setFn(m, isFn, size, cx + dx, cy + dy, d != 1)
        }
    }

    private fun setFn(m: BooleanArray, isFn: BooleanArray, size: Int, x: Int, y: Int, dark: Boolean) {
        if (x !in 0 until size || y !in 0 until size) return
        m[y * size + x] = dark
        isFn[y * size + x] = true
    }

    // ---- Codeword placement ------------------------------------------------

    private fun drawCodewords(m: BooleanArray, isFn: BooleanArray, data: ByteArray, size: Int) {
        var i = 0
        var col = size - 1
        while (col >= 1) {
            if (col == 6) col = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = col - j
                    val upward = ((col + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFn[y * size + x] && i < data.size * 8) {
                        m[y * size + x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
            }
            col -= 2
        }
    }

    // ---- Masking & format/version info ------------------------------------

    private fun applyMask(m: BooleanArray, isFn: BooleanArray, size: Int, mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            if (isFn[y * size + x]) continue
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> (x * y) % 2 + (x * y) % 3 == 0
                6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                else -> false
            }
            if (invert) m[y * size + x] = !m[y * size + x]
        }
    }

    private fun drawFormatBits(m: BooleanArray, isFn: BooleanArray, size: Int, ecc: QrEcc, mask: Int) {
        val data = (ecc.ordinalForFormat shl 3) or mask
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        val bits = ((data shl 10) or rem) xor 0x5412
        // Top-left
        for (i in 0..5) setFmt(m, isFn, size, 8, i, ((bits ushr i) and 1) != 0)
        setFmt(m, isFn, size, 8, 7, ((bits ushr 6) and 1) != 0)
        setFmt(m, isFn, size, 8, 8, ((bits ushr 7) and 1) != 0)
        setFmt(m, isFn, size, 7, 8, ((bits ushr 8) and 1) != 0)
        for (i in 9..14) setFmt(m, isFn, size, 14 - i, 8, ((bits ushr i) and 1) != 0)
        // Bottom-left + top-right
        for (i in 0..7) setFmt(m, isFn, size, size - 1 - i, 8, ((bits ushr i) and 1) != 0)
        for (i in 8..14) setFmt(m, isFn, size, 8, size - 15 + i, ((bits ushr i) and 1) != 0)
        setFmt(m, isFn, size, 8, size - 8, true) // always-dark
    }

    private fun setFmt(m: BooleanArray, isFn: BooleanArray, size: Int, x: Int, y: Int, dark: Boolean) {
        m[y * size + x] = dark
        isFn[y * size + x] = true
    }

    private fun drawVersion(m: BooleanArray, isFn: BooleanArray, size: Int, version: Int) {
        var rem = version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val bit = ((bits ushr i) and 1) != 0
            val a = size - 11 + i % 3
            val b = i / 3
            setFmt(m, isFn, size, a, b, bit)
            setFmt(m, isFn, size, b, a, bit)
        }
    }

    // ---- Penalty score (mask selection) -----------------------------------

    private fun penaltyScore(m: BooleanArray, size: Int): Int {
        var p = 0
        // Run penalties (rows + cols)
        for (y in 0 until size) {
            var run = 0; var prev = false
            for (x in 0 until size) {
                val c = m[y * size + x]
                if (c == prev) { run++ } else { if (run >= 5) p += run - 2; run = 1; prev = c }
            }
            if (run >= 5) p += run - 2
        }
        for (x in 0 until size) {
            var run = 0; var prev = false
            for (y in 0 until size) {
                val c = m[y * size + x]
                if (c == prev) { run++ } else { if (run >= 5) p += run - 2; run = 1; prev = c }
            }
            if (run >= 5) p += run - 2
        }
        // 2×2 same-colour blocks: +3 each
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = m[y * size + x]
            if (c == m[y * size + x + 1] && c == m[(y + 1) * size + x] && c == m[(y + 1) * size + x + 1]) p += 3
        }
        // Proportion of dark modules
        var dark = 0
        for (b in m) if (b) dark++
        val percent = dark * 100 / m.size
        val k = (Math.abs(percent - 50) + 4) / 5
        p += k * 10
        return p
    }

    // ---- Reed-Solomon ECC --------------------------------------------------

    private fun addEccAndInterleave(data: ByteArray, version: Int, ecc: QrEcc): ByteArray {
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version]
        val rawCodewords = numRawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockDataLen = rawCodewords / numBlocks - blockEccLen

        val blocksData = Array(numBlocks) { ByteArray(0) }
        val blocksEcc = Array(numBlocks) { ByteArray(0) }
        val rsGen = reedSolomonGenerator(blockEccLen)
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortBlockDataLen + (if (i < numShortBlocks) 0 else 1)
            val dat = data.copyOfRange(k, k + datLen)
            k += datLen
            blocksData[i] = dat
            blocksEcc[i] = reedSolomonRemainder(dat, rsGen)
        }
        val result = ByteArray(rawCodewords)
        var idx = 0
        // Data columns
        for (i in 0..shortBlockDataLen) {
            for (b in 0 until numBlocks) {
                if (i == shortBlockDataLen && b < numShortBlocks) continue
                if (i < blocksData[b].size) result[idx++] = blocksData[b][i]
            }
        }
        // ECC columns
        for (i in 0 until blockEccLen) {
            for (b in 0 until numBlocks) {
                result[idx++] = blocksEcc[b][i]
            }
        }
        return result
    }

    private fun reedSolomonGenerator(degree: Int): ByteArray {
        require(degree in 1..255)
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMul(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) result[j] = (result[j].toInt() xor (result[j + 1].toInt() and 0xFF)).toByte()
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, gen: ByteArray): ByteArray {
        val result = ByteArray(gen.size)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices)
                result[i] = (result[i].toInt() xor gfMul(gen[i].toInt() and 0xFF, factor)).toByte()
        }
        return result
    }

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    // ---- Capacity / layout tables -----------------------------------------

    private fun numRawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private fun totalDataCodewords(version: Int, ecc: QrEcc): Int {
        return numRawDataModules(version) / 8 -
            ECC_CODEWORDS_PER_BLOCK[ecc.ordinal][version] *
            NUM_ERROR_CORRECTION_BLOCKS[ecc.ordinal][version]
    }

    /** Padding param so we can keep one helper signature. */
    private fun numFn(@Suppress("UNUSED_PARAMETER") v: Int) = 0

    private fun alignmentPositions(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val numAlign = version / 7 + 2
        val step = if (version == 32) 26
                   else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        var pos = version * 4 + 10
        for (i in numAlign - 1 downTo 1) { result[i] = pos; pos -= step }
        return result
    }

    /** Bytes 0..40 — index 0 is unused (versions are 1-based). */
    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        // L (Medium ordinal=0 in nayuki original is L=1 here — see QrEcc.ordinal mapping)
        // We index by enum.ordinal: Low=0, Medium=1, Quartile=2, High=3
        intArrayOf( // Low
            -1,  7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28,
            28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30
        ),
        intArrayOf( // Medium
            -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
            26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28
        ),
        intArrayOf( // Quartile
            -1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30,
            28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30
        ),
        intArrayOf( // High
            -1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28,
            30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30
        ),
    )

    private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
        intArrayOf( // Low
            -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4,  4,  4,  4,  4,  6,  6,  6,  6,  7,  8,
             8, 9,10,12,12,13,14,15,16,17,18,19,19,20,21,22,24,25,27,28
        ),
        intArrayOf( // Medium
            -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5,  5,  8,  9,  9, 10, 10, 11, 13, 14, 16,
            17,17,18,20,21,23,25,26,28,29,31,33,35,37,38,40,43,45,47,49
        ),
        intArrayOf( // Quartile
            -1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8,  8, 10, 12, 16, 12, 17, 16, 18, 21, 20,
            23,23,25,27,29,34,34,35,38,40,43,45,48,51,53,56,59,62,65,68
        ),
        intArrayOf( // High
            -1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25,
            25,34,30,32,35,37,40,42,45,48,51,54,57,60,63,66,70,74,77,81
        ),
    )

    // ---- Helper: bit buffer ------------------------------------------------

    private class BitBuffer {
        private val bits = mutableListOf<Boolean>()
        val size: Int get() = bits.size

        fun append(value: Int, len: Int) {
            require(len in 0..31)
            for (i in len - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
        }

        fun toBytes(): ByteArray {
            val out = ByteArray((bits.size + 7) / 8)
            for (i in bits.indices) {
                if (bits[i]) {
                    out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - (i and 7)))).toByte()
                }
            }
            return out
        }
    }
}
