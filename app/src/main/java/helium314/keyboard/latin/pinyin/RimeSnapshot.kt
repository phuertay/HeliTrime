package helium314.keyboard.latin.pinyin

data class RimeCandidate(val text: String, val comment: String)

data class RimeSnapshot(
    val commit: String = "",
    val preedit: String = "",
    val cursor: Int = 0,
    val pageNo: Int = 0,
    val pageSize: Int = 0,
    val isLastPage: Boolean = true,
    val asciiMode: Boolean = false,
    val composing: Boolean = false,
    val candidates: List<RimeCandidate> = emptyList(),
)
