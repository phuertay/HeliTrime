package helium314.keyboard.latin.pinyin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HanziLookupTest {
    @Test
    fun horizontalStrokePointsEast() {
        val feature = inkFeatures(listOf(floatArrayOf(0f, 10f, 80f, 10f)))
        assertEquals(0, feature[0] and 7)
    }

    @Test
    fun oneMissingStrokeStillMatches() {
        val model = intArrayOf(1, 2, 3)
        assertTrue(matchScore(intArrayOf(1, 2), model) != Int.MIN_VALUE)
        assertEquals(Int.MIN_VALUE, matchScore(intArrayOf(1), model))
    }

    @Test
    fun twoDroppedStrokesStillMatchALongCharacter() {
        val model = intArrayOf(1, 2, 3, 4, 5, 6, 7)
        assertTrue(matchScore(intArrayOf(1, 2, 3, 4, 5), model) != Int.MIN_VALUE)
        assertEquals(Int.MIN_VALUE, matchScore(intArrayOf(1, 2), model))
    }

    @Test
    fun drawingKeepsAMenuCharacterTheInkMissed() {
        val menu = listOf(MenuChar("系", 4, 1), MenuChar("机", 0, 0))
        val ink = listOf(floatArrayOf(0f, 0f, 10f, 10f))
        val rows = buildLookup(menu, ink, OnlyJi, true, 0, true, false, true)
        assertEquals(listOf("机", "系"), rows.rows.map { it.text })
        assertEquals(4, rows.rows.last().page)
    }

    @Test
    fun simplifiedCollapsesAPairAndTraditionalKeepsMany() {
        val pair = collapseHits(listOf('国'.code to 5, '國'.code to 9), true) { _, _ -> '国'.code }
        assertEquals(listOf('国'.code), pair)
        val many = collapseHits(
            listOf('发'.code to 3, '發'.code to 8, '髮'.code to 7),
            false,
        ) { code, _ -> if (code == '发'.code) 0 else code }
        assertEquals(listOf('發'.code, '髮'.code), many)
    }

    @Test
    fun radicalFiltersTheMenuAndWidenCommitsOutsideIt() {
        val menu = listOf(MenuChar("你好", 0, 0), MenuChar("语", 0, 1))
        val plain = buildLookup(menu, emptyList(), TinyHanzi, true, 0, false, false, true)
        assertEquals(listOf("你好", "语"), plain.rows.map { it.text })
        assertEquals("讠", plain.radicals.single().second)
        val filtered = buildLookup(menu, emptyList(), TinyHanzi, true, '言'.code, false, false, true)
        assertEquals(listOf("语"), filtered.rows.map { it.text })
        assertEquals(1, filtered.rows.single().index)
        val ink = listOf(floatArrayOf(0f, 0f, 10f, 10f))
        val widened = buildLookup(menu, ink, TinyHanzi, true, 0, true, true, true)
        val outside = widened.rows.first { it.text == "人" }
        assertEquals(-1, outside.page)
        val inside = widened.rows.first { it.text == "语" }
        assertEquals(1, inside.index)
    }

    @Test
    fun packedTableKeepsDirectionAndTheScriptPairs() {
        val file = listOf(
            java.io.File("src/main/assets/rime/hanzi_lookup.bin"),
            java.io.File("app/src/main/assets/rime/hanzi_lookup.bin"),
        ).firstOrNull { it.isFile } ?: error("hanzi_lookup.bin missing")
        val hanzi = HanziTable.decode(file.readBytes()) as HanziTable
        val across = inkFeatures(listOf(floatArrayOf(0f, 20f, 100f, 22f)))
        val down = inkFeatures(listOf(floatArrayOf(20f, 0f, 22f, 100f)))
        val pool = intArrayOf('一'.code, '丨'.code)
        assertEquals('一'.code, hanzi.rank(across, pool, true, 0).first())
        assertEquals('丨'.code, hanzi.rank(down, pool, true, 0).first())
        assertEquals('国'.code, hanzi.displayOf('國'.code, true))
        assertEquals('國'.code, hanzi.displayOf('国'.code, false))
        assertEquals(0, hanzi.displayOf('发'.code, false))
        assertEquals('發'.code, hanzi.displayOf('發'.code, false))
        assertEquals('髮'.code, hanzi.displayOf('髮'.code, false))
        assertEquals('发'.code, hanzi.displayOf('發'.code, true))
        assertEquals('讠'.code, hanzi.radical('语'.code))
        assertEquals('言'.code, hanzi.radical('語'.code))
        assertEquals("讠", hanzi.radicalLabel(hanzi.radical('语'.code), true))
        assertEquals("言", hanzi.radicalLabel(hanzi.radical('語'.code), false))
    }
}

private object OnlyJi : HanziIndex {
    override fun radical(codePoint: Int) = 0
    override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int) =
        pool.filter { it == '机'.code }.toList()
    override fun common() = intArrayOf('机'.code, '系'.code)
    override fun radicalLabel(radical: Int, simplified: Boolean) = ""
}

private object TinyHanzi : HanziIndex {
    override fun radical(codePoint: Int) = if (codePoint == '语'.code) '言'.code else 0
    override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int): List<Int> {
        return pool.filter { radical == 0 || it == '语'.code }.toList()
    }
    override fun common() = intArrayOf('人'.code, '语'.code)
    override fun radicalLabel(radical: Int, simplified: Boolean) =
        if (simplified && radical == '言'.code) "讠" else "言"
}
