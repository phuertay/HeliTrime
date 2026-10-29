package helium314.keyboard.latin.pinyin

data class RimeCandidate(val text: String, val comment: String)

/** Immutable view of one librime session. */
data class RimeSnapshot(
    val commit: String,
    val preedit: String,
    val cursor: Int,
    val pageNo: Int,
    val isLastPage: Boolean,
    val highlighted: Int,
    val candidates: List<RimeCandidate>,
) {
    companion object {
        val empty = RimeSnapshot("", "", 0, 0, true, 0, emptyList())

        fun fromNative(fields: Array<String>?): RimeSnapshot {
            if (fields == null || fields.size < 6) return empty
            val candidates = ArrayList<RimeCandidate>()
            var index = 6
            while (index + 1 < fields.size) {
                candidates.add(RimeCandidate(fields[index], fields[index + 1]))
                index += 2
            }
            return RimeSnapshot(
                commit = fields[0],
                preedit = fields[1],
                cursor = fields[2].toIntOrNull() ?: 0,
                pageNo = fields[3].toIntOrNull() ?: 0,
                isLastPage = fields[4] == "1",
                highlighted = fields[5].toIntOrNull() ?: 0,
                candidates = candidates,
            )
        }
    }
}
