package helium314.keyboard.latin.pinyin

/** What [PinyinInputController] needs from librime. Tests pass a fake. */
interface RimeClient {
    fun startup(sharedDir: String, userDir: String, prebuiltDir: String): Boolean
    fun createSession(): Long
    fun destroySession(session: Long)
    fun selectSchema(session: Long, schemaId: String): Boolean
    fun setOption(session: Long, option: String, value: Boolean)
    fun processKey(session: Long, keycode: Int, mask: Int): Boolean
    fun selectCandidate(session: Long, index: Int): Boolean
    fun commitComposition(session: Long): Boolean
    fun clearComposition(session: Long)
    fun readSnapshot(session: Long): RimeSnapshot
}
