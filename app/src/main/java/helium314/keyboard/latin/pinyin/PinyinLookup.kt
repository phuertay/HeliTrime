package helium314.keyboard.latin.pinyin

data class MenuChar(val text: String, val page: Int, val index: Int)

data class LookupRow(val text: String, val page: Int, val index: Int)

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
    val radicals = singles.map { hanzi.radical(it.text.codePointAt(0)) }
        .filter { it != 0 }
        .distinct()
        .map { it to hanzi.radicalLabel(it, simplified) }
    val features = inkFeatures(ink)
    val rows = if (features.isEmpty()) {
        val shown = if (radical == 0) menu else singles.filter {
            hanzi.radical(it.text.codePointAt(0)) == radical
        }
        shown.map { LookupRow(it.text, it.page, it.index) }
    } else {
        val pool = if (widened || !hasSpelling) hanzi.common()
        else singles.map { it.text.codePointAt(0) }.toIntArray()
        val matched = hanzi.rank(features, pool, simplified, radical).map { codePoint ->
            val text = Character.toString(codePoint)
            val hit = menu.firstOrNull { it.text == text }
            if (hit != null) LookupRow(text, hit.page, hit.index) else LookupRow(text, -1, -1)
        }
        if (widened || !hasSpelling) matched
        else {
            val seen = matched.map { it.text }.toHashSet()
            val rest = singles.mapNotNull { item ->
                if (item.text in seen) return@mapNotNull null
                val code = item.text.codePointAt(0)
                if (radical != 0 && hanzi.radical(code) != radical) return@mapNotNull null
                LookupRow(item.text, item.page, item.index)
            }
            matched + rest
        }
    }
    return LookupModel(rows, radicals, radical, pad, widened, simplified, hasSpelling, ink.toList())
}
