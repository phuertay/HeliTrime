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
    fun commitComposition(): Boolean
    fun clearComposition()
    fun setOption(option: String, value: Boolean)
    fun getOption(option: String): Boolean
    fun readSnapshot(): RimeSnapshot
    fun shutdown()
}
