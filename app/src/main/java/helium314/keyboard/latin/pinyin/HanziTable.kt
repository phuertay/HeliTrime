package helium314.keyboard.latin.pinyin

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stroke table distilled from Make Me a Hanzi. Tests use a fake. */
interface HanziIndex {
    fun radical(codePoint: Int): Int
    fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int): List<Int>
    fun common(): IntArray
    fun radicalLabel(radical: Int, simplified: Boolean): String
}

object EmptyHanzi : HanziIndex {
    override fun radical(codePoint: Int) = 0
    override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int) = emptyList<Int>()
    override fun common() = IntArray(0)
    override fun radicalLabel(radical: Int, simplified: Boolean) = ""
}

class HanziTable private constructor(
    private val codes: IntArray,
    private val radicals: IntArray,
    private val strokeAt: IntArray,
    private val strokeLen: IntArray,
    private val strokes: IntArray,
    private val toSimplified: Map<Int, Int>,
    private val toTraditional: Map<Int, IntArray>,
) : HanziIndex {
    override fun radical(codePoint: Int): Int {
        val index = indexOf(codePoint)
        return if (index < 0) 0 else radicals[index]
    }

    override fun common(): IntArray = codes

    override fun radicalLabel(radical: Int, simplified: Boolean): String {
        return if (radical == 0) "" else Character.toString(radical)
    }

    override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int): List<Int> {
        if (ink.isEmpty() || pool.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Int, Int>>(pool.size)
        for (codePoint in pool) {
            val index = indexOf(codePoint)
            if (index < 0) continue
            if (radical != 0 && radicals[index] != radical) continue
            val start = strokeAt[index]
            val model = strokes.copyOfRange(start, start + strokeLen[index])
            val score = matchScore(ink, model)
            if (score != Int.MIN_VALUE) hits.add(codePoint to score)
        }
        return collapseHits(hits, simplified, ::displayOf)
    }

    fun displayOf(codePoint: Int, simplified: Boolean): Int {
        val simple = toSimplified[codePoint] ?: codePoint
        val traditional = toTraditional[simple]
        if (simplified) return simple
        if (traditional == null) return codePoint
        if (traditional.size == 1) return traditional[0]
        if (codePoint != simple) {
            for (form in traditional) if (form == codePoint) return codePoint
        }
        return 0
    }

    private fun indexOf(codePoint: Int): Int {
        var low = 0
        var high = codes.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val found = codes[mid]
            when {
                found == codePoint -> return mid
                found < codePoint -> low = mid + 1
                else -> high = mid - 1
            }
        }
        return -1
    }

    companion object {
        private var cached: HanziIndex? = null

        fun open(context: Context): HanziIndex {
            cached?.let { return it }
            val loaded = try {
                context.assets.open("rime/hanzi_lookup.bin").use { input -> decode(input.readBytes()) }
            } catch (_: Throwable) {
                EmptyHanzi
            }
            cached = loaded
            return loaded
        }

        fun decode(bytes: ByteArray): HanziIndex {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buffer.get(magic)
            if (!magic.contentEquals(byteArrayOf('H'.code.toByte(), 'Z'.code.toByte(), 'L'.code.toByte(), '1'.code.toByte()))) {
                return EmptyHanzi
            }
            buffer.u16()
            val charCount = buffer.u16()
            val strokeCount = buffer.int
            val pairCount = buffer.u16()
            val manyCount = buffer.u16()
            val codes = IntArray(charCount)
            val radicals = IntArray(charCount)
            val strokeAt = IntArray(charCount)
            val strokeLen = IntArray(charCount)
            for (index in 0 until charCount) {
                codes[index] = buffer.u16()
                radicals[index] = buffer.u16()
                strokeAt[index] = buffer.int
                strokeLen[index] = buffer.u16()
            }
            val strokes = IntArray(strokeCount)
            for (index in 0 until strokeCount) strokes[index] = buffer.u16()
            val toSimplified = HashMap<Int, Int>(pairCount)
            for (index in 0 until pairCount) {
                val traditional = buffer.u16()
                toSimplified[traditional] = buffer.u16()
            }
            val toTraditional = HashMap<Int, IntArray>(manyCount)
            for (index in 0 until manyCount) {
                val simple = buffer.u16()
                val count = buffer.get().toInt() and 0xff
                val forms = IntArray(count)
                for (form in 0 until count) forms[form] = buffer.u16()
                toTraditional[simple] = forms
            }
            return HanziTable(codes, radicals, strokeAt, strokeLen, strokes, toSimplified, toTraditional)
        }
    }
}

private fun ByteBuffer.u16(): Int = short.toInt() and 0xffff
