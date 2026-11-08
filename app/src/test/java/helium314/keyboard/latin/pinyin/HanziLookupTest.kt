package helium314.keyboard.latin.pinyin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HanziLookupTest {
    @Test
    fun walkPagesReachesTheLastPageAndRestoresTheStrip() {
        val pages = (0 until 45).map { page ->
            listOf(RimeCandidate(if (page == 44) "系" else "字$page", ""))
        }
        var page = 10
        val menu = walkPages(
            read = {
                RimeSnapshot(
                    pageNo = page,
                    isLastPage = page == pages.lastIndex,
                    candidates = pages[page],
                )
            },
            change = { backward ->
                val next = if (backward) page - 1 else page + 1
                if (next !in pages.indices) false else {
                    page = next
                    true
                }
            },
        )
        assertEquals(45, menu.size)
        assertEquals("系", menu.last().text)
        assertEquals(44, menu.last().page)
        assertEquals(10, page)
    }

    @Test
    fun radicalsSortByCountThenStrokeCount() {
        val hanzi = object : HanziIndex {
            override fun radical(codePoint: Int) = when (codePoint) {
                '人'.code -> '人'.code
                '系'.code, '紧'.code -> '糸'.code
                '木'.code -> '木'.code
                else -> 0
            }
            override fun strokeCount(codePoint: Int) = when (codePoint) {
                '人'.code -> 2
                '木'.code -> 4
                '糸'.code -> 6
                else -> 0
            }
            override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int) = emptyList<Int>()
            override fun common() = IntArray(0)
            override fun radicalLabel(radical: Int, simplified: Boolean) = Character.toString(radical)
        }
        val menu = listOf(
            MenuChar("人", 0, 0),
            MenuChar("木", 0, 1),
            MenuChar("系", 0, 2),
            MenuChar("紧", 0, 3),
            MenuChar("人", 1, 0),
        )
        val model = buildLookup(menu, emptyList(), hanzi, true, 0, false, false, true)
        assertEquals(listOf("人", "糸", "木"), model.radicals.map { it.second })
    }

    @Test
    fun horizontalStrokePointsEast() {
        val feature = inkFeatures(listOf(floatArrayOf(0f, 10f, 80f, 10f)))
        assertEquals(0, feature[0] and 7)
    }

    @Test
    fun reversedFlatStrokeStaysNearTheModel() {
        val model = inkFeatures(listOf(floatArrayOf(90f, 10f, 10f, 16f)))
        val reversed = inkFeatures(listOf(floatArrayOf(10f, 16f, 90f, 10f)))
        val down = inkFeatures(listOf(floatArrayOf(50f, 0f, 50f, 80f)))
        assertEquals(4, model[0] and 7)
        assertEquals(0, reversed[0] and 7)
        val exact = matchScore(model, model)
        val flipped = matchScore(reversed, model)
        assertTrue(exact - flipped <= 8, "exact=$exact flipped=$flipped")
        assertTrue(flipped > matchScore(down, model))
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
        assertEquals("nǐ", hanzi.reading('你'.code))
        assertEquals("hǎo", hanzi.reading('好'.code))
        assertEquals("le", hanzi.reading('了'.code))
        assertEquals("yī", hanzi.reading('一'.code))
        assertEquals("", hanzi.reading('?'.code))
        val whole = inkFeatures(listOf(floatArrayOf(80f, 400f, 720f, 420f)))
        val hits = hanzi.rank(whole, hanzi.common(), true, 0)
        assertTrue(hits.isNotEmpty())
    }

    @Test
    fun eachCharacterKeepsItsSyllableOnTheGridAndThePad() {
        val hanzi = object : HanziIndex by TinyHanzi {
            override fun reading(codePoint: Int) = when (codePoint) {
                '你'.code -> "nǐ"
                '好'.code -> "hǎo"
                '语'.code -> "yǔ"
                '人'.code -> "rén"
                else -> ""
            }
        }
        val menu = listOf(MenuChar("你好", 0, 0), MenuChar("语", 0, 1))
        val grid = buildLookup(menu, emptyList(), hanzi, true, 0, false, false, true)
        assertEquals(listOf("nǐ", "hǎo"), grid.rows[0].readings)
        assertEquals(listOf("yǔ"), grid.rows[1].readings)
        assertEquals(2, lookupSpan("你好", 8))
        assertEquals(1, lookupSpan("语", 8))
        val ink = listOf(floatArrayOf(0f, 0f, 10f, 10f))
        val pad = buildLookup(menu, ink, hanzi, true, 0, true, true, true)
        assertEquals(listOf("rén"), pad.rows.first { it.text == "人" }.readings)
        assertEquals(1, lookupSpan("人", 4))
        assertEquals(4, lookupSpan("中华人民共和国", 4))
    }

    @Test
    fun aStrokeInTheMiddleBeatsTheSameStrokeAtTheTop() {
        val square = arrayOf(0f, 0f, INK_SQUARE, INK_SQUARE)
        val middle = packStroke(floatArrayOf(120f, 500f, 900f, 520f), square[0], square[1], square[2], square[3])
        val top = packStroke(floatArrayOf(120f, 40f, 900f, 60f), square[0], square[1], square[2], square[3])
        assertTrue(matchScore(intArrayOf(middle), intArrayOf(middle)) > matchScore(intArrayOf(top), intArrayOf(middle)))
    }

    @Test
    fun aHookBeatsAStraightLineAgainstAHook() {
        val straight = packStroke(floatArrayOf(100f, 400f, 500f, 400f, 900f, 420f), 0f, 0f, INK_SQUARE, INK_SQUARE)
        val hook = packStroke(floatArrayOf(100f, 200f, 500f, 220f, 520f, 800f), 0f, 0f, INK_SQUARE, INK_SQUARE)
        assertEquals(0, straight and (1 shl 14))
        assertTrue(hook and (1 shl 14) != 0)
        assertTrue(matchScore(intArrayOf(hook), intArrayOf(hook)) > matchScore(intArrayOf(straight), intArrayOf(hook)))
    }

    @Test
    fun frequencyBreaksATieAndARememberedCharacterGoesFirst() {
        val feature = packStroke(floatArrayOf(120f, 500f, 900f, 520f), 0f, 0f, INK_SQUARE, INK_SQUARE)
        val rare = '丁'.code
        val common = '七'.code
        val hanzi = tableWithFrequency(intArrayOf(rare, common), intArrayOf(feature, feature), intArrayOf(0, 255))
        val ink = intArrayOf(feature)
        val pool = intArrayOf(rare, common)
        assertEquals(common, hanzi.rank(ink, pool, true, 0).first())
        val file = java.io.File.createTempFile("ink", ".txt")
        val memory = HandwritingMemory(file)
        memory.remember(Character.toString(rare))
        assertEquals(HandwritingMemory.BONUS, memory.bonus(rare))
        memory.learning = false
        memory.remember(Character.toString(common))
        assertEquals(0, memory.bonus(common))
        HandwritingMemory.active = HandwritingMemory(file)
        try {
            assertEquals(rare, hanzi.rank(ink, pool, true, 0).first())
        } finally {
            HandwritingMemory.active = HandwritingMemory.NONE
        }
    }

    @Test
    fun aFlatStrokeAcrossTheMiddlePicksYi() {
        val file = listOf(
            java.io.File("src/main/assets/rime/hanzi_lookup.bin"),
            java.io.File("app/src/main/assets/rime/hanzi_lookup.bin"),
        ).firstOrNull { it.isFile } ?: error("hanzi_lookup.bin missing")
        val hanzi = HanziTable.decode(file.readBytes())
        val across = inkFeatures(listOf(floatArrayOf(120f, 500f, 900f, 520f)))
        assertEquals('一'.code, hanzi.rank(across, hanzi.common(), true, 0).first())
    }

    @Test
    fun readingTrailerRoundTripsOneSyllable() {
        val bytes = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bytes.put("HZL1".toByteArray())
        bytes.putShort(1)
        bytes.putShort(1)
        bytes.putInt(0)
        bytes.putShort(0)
        bytes.putShort(0)
        bytes.putShort('你'.code.toShort())
        bytes.putShort(0)
        bytes.putInt(0)
        bytes.putShort(0)
        bytes.put("RDG1".toByteArray())
        bytes.putShort(1)
        val syllable = "nǐ".toByteArray()
        bytes.put(syllable.size.toByte())
        bytes.put(syllable)
        val hanzi = HanziTable.decode(bytes.array().copyOf(bytes.position()))
        assertEquals("nǐ", hanzi.reading('你'.code))
    }
}

private fun tableWithFrequency(codes: IntArray, strokes: IntArray, frequency: IntArray): HanziIndex {
    val buffer = java.nio.ByteBuffer.allocate(64 + strokes.size * 2 + frequency.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.put("HZL1".toByteArray())
    buffer.putShort(1)
    buffer.putShort(codes.size.toShort())
    buffer.putInt(strokes.size)
    buffer.putShort(0)
    buffer.putShort(0)
    var offset = 0
    for (index in codes.indices) {
        buffer.putShort(codes[index].toShort())
        buffer.putShort(0)
        buffer.putInt(offset)
        buffer.putShort(1)
        offset += 1
    }
    for (stroke in strokes) buffer.putShort(stroke.toShort())
    buffer.put("RDG1".toByteArray())
    buffer.putShort(codes.size.toShort())
    repeat(codes.size) { buffer.put(0) }
    buffer.put("FRQ1".toByteArray())
    buffer.putShort(codes.size.toShort())
    for (value in frequency) buffer.put(value.toByte())
    return HanziTable.decode(buffer.array().copyOf(buffer.position()))
}

private object OnlyJi : HanziIndex {
    override fun radical(codePoint: Int) = 0
    override fun strokeCount(codePoint: Int) = 0
    override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int) =
        pool.filter { it == '机'.code }.toList()
    override fun common() = intArrayOf('机'.code, '系'.code)
    override fun radicalLabel(radical: Int, simplified: Boolean) = ""
}

private object TinyHanzi : HanziIndex {
    override fun radical(codePoint: Int) = if (codePoint == '语'.code) '言'.code else 0
    override fun strokeCount(codePoint: Int) = 0
    override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int): List<Int> {
        return pool.filter { radical == 0 || it == '语'.code }.toList()
    }
    override fun common() = intArrayOf('人'.code, '语'.code)
    override fun radicalLabel(radical: Int, simplified: Boolean) =
        if (simplified && radical == '言'.code) "讠" else "言"
}
