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
