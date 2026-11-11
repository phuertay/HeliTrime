package helium314.keyboard.latin.pinyin

/**
 * librime, as seen by [PinyinInputController]. Unit tests supply a fake.
 * [RimeSession] is the only implementation that calls JNI.
 */
interface RimeClient {
    val failed: Boolean
    fun hasSession(): Boolean
    fun ensure(incognito: Boolean, schemaId: String)
    fun deleteIncognitoData()
    fun destroySession()
    fun processKey(keysym: Int, mask: Int): Boolean
    fun selectCandidate(index: Int): Boolean
    fun changePage(backward: Boolean): Boolean

    /**
     * One menu step for [expectedPreedit]. A key can run between steps.
     * Null when the spelling changed or the page did not move.
     */
    fun menuPage(expectedPreedit: String, move: MenuMove): RimeSnapshot? {
        val snap = readSnapshot()
        if (snap.preedit != expectedPreedit) return null
        val hold = move == MenuMove.STAY
            || (move == MenuMove.NEXT && snap.isLastPage)
            || (move == MenuMove.PREVIOUS && snap.pageNo == 0)
        if (hold) return snap
        val before = snap.pageNo
        if (!changePage(move == MenuMove.PREVIOUS)) return null
        val now = readSnapshot()
        if (now.preedit != expectedPreedit) return null
        val moved = if (move == MenuMove.PREVIOUS) now.pageNo < before else now.pageNo > before
        return if (moved) now else null
    }

    fun readMenu(): List<MenuChar> = walkPages(read = { readSnapshot() }, change = { changePage(it) })
    fun selectOnPage(page: Int, index: Int): String {
        if (!seekPage(page, read = { readSnapshot() }, change = { changePage(it) })) return ""
        if (!selectCandidate(index)) return ""
        return readSnapshot().commit
    }
    fun commitComposition(): Boolean
    fun clearComposition()
    fun setOption(option: String, value: Boolean)
    fun getOption(option: String): Boolean
    fun readSnapshot(): RimeSnapshot
    fun shutdown()
}
