package helium314.keyboard.latin.pinyin

data class MenuChar(val text: String, val page: Int, val index: Int)

/** Stop when the page does not move. A stuck page ends the walk; a count does not. */
internal fun seekPage(target: Int, read: () -> RimeSnapshot, change: (Boolean) -> Boolean): Boolean {
    fun step(backward: Boolean, more: (Int) -> Boolean): Boolean {
        while (more(read().pageNo)) {
            val before = read().pageNo
            if (!change(backward)) return false
            val now = read().pageNo
            val stuck = if (backward) now >= before else now <= before
            if (stuck) return false
        }
        return true
    }
    if (!step(true) { it > target }) return false
    if (!step(false) { it < target }) return false
    return read().pageNo == target
}

internal fun walkPages(read: () -> RimeSnapshot, change: (Boolean) -> Boolean): List<MenuChar> {
    val saved = read().pageNo
    seekPage(0, read, change)
    val out = ArrayList<MenuChar>()
    while (true) {
        val snap = read()
        snap.candidates.forEachIndexed { index, candidate ->
            out.add(MenuChar(candidate.text, snap.pageNo, index))
        }
        if (snap.isLastPage) break
        val before = snap.pageNo
        if (!change(false)) break
        if (read().pageNo <= before) break
    }
    seekPage(saved, read, change)
    return out
}

data class LookupRow(
    val text: String,
    val page: Int,
    val index: Int,
    val readings: List<String> = emptyList(),
)

/** One toned syllable per character. Empty where the table has no reading. */
internal fun readingsOf(text: String, hanzi: HanziIndex): List<String> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<String>(text.codePointCount(0, text.length))
    var offset = 0
    while (offset < text.length) {
        val code = text.codePointAt(offset)
        out.add(hanzi.reading(code))
        offset += Character.charCount(code)
    }
    return out
}

/** Grid columns this candidate occupies: one per character, and never more than the row. */
internal fun lookupSpan(text: String, columns: Int): Int {
    val count = text.codePointCount(0, text.length)
    if (count < 1) return 1
    return if (count > columns) columns else count
}

data class LookupModel(
    val rows: List<LookupRow>,
    val radicals: List<Pair<Int, String>>,
    val selectedRadical: Int,
    val pad: Boolean,
    val widened: Boolean,
    val simplified: Boolean,
    val hasSpelling: Boolean,
    val strokes: List<FloatArray>,
)

fun buildLookup(
    menu: List<MenuChar>,
    ink: List<FloatArray>,
    hanzi: HanziIndex,
    simplified: Boolean,
    radical: Int,
    pad: Boolean,
    widened: Boolean,
    hasSpelling: Boolean,
): LookupModel {
    val singles = menu.filter { it.text.codePointCount(0, it.text.length) == 1 }
    val counts = LinkedHashMap<Int, Int>()
    for (item in singles) {
        val code = hanzi.radical(item.text.codePointAt(0))
        if (code != 0) counts[code] = (counts[code] ?: 0) + 1
    }
    val radicals = counts.entries.sortedWith(
        compareByDescending<Map.Entry<Int, Int>> { it.value }
            .thenBy { hanzi.strokeCount(it.key).let { strokes -> if (strokes == 0) Int.MAX_VALUE else strokes } }
            .thenBy { it.key },
    ).map { it.key to hanzi.radicalLabel(it.key, simplified) }
    val features = inkFeatures(ink)
    val rows = if (features.isEmpty()) {
        val shown = if (radical == 0) menu else singles.filter {
            hanzi.radical(it.text.codePointAt(0)) == radical
        }
        shown.map { lookupRow(it.text, it.page, it.index, hanzi) }
    } else {
        val pool = if (widened || !hasSpelling) hanzi.common()
        else singles.map { it.text.codePointAt(0) }.toIntArray()
        val matched = hanzi.rank(features, pool, simplified, radical).map { codePoint ->
            val text = Character.toString(codePoint)
            val hit = menu.firstOrNull { it.text == text }
            if (hit != null) lookupRow(text, hit.page, hit.index, hanzi) else lookupRow(text, -1, -1, hanzi)
        }
        if (widened || !hasSpelling) matched
        else {
            val seen = matched.map { it.text }.toHashSet()
            val rest = singles.mapNotNull { item ->
                if (item.text in seen) return@mapNotNull null
                val code = item.text.codePointAt(0)
                if (radical != 0 && hanzi.radical(code) != radical) return@mapNotNull null
                lookupRow(item.text, item.page, item.index, hanzi)
            }
            matched + rest
        }
    }
    return LookupModel(rows, radicals, radical, pad, widened, simplified, hasSpelling, ink.toList())
}

private fun lookupRow(text: String, page: Int, index: Int, hanzi: HanziIndex): LookupRow {
    return LookupRow(text, page, index, readingsOf(text, hanzi))
}
