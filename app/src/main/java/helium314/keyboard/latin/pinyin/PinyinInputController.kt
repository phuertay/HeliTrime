package helium314.keyboard.latin.pinyin

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants

/**
 * 26-key pinyin policy. Librime calls go through [RimeClient].
 * A false return means the caller should keep the Latin path.
 */
class PinyinInputController(
    private val client: RimeClient,
    private val editor: PinyinEditor,
) {
    var session: Long = 0
        private set
    var active: Boolean = false
        private set
    var asciiMode: Boolean = false
        private set
    var forcedAscii: Boolean = false
        private set
    var schemaId: String = "luna_pinyin"
        private set
    private var createdSessions: Int = 0
    private var swipeLatched: Boolean = false
    var lastSnapshot: RimeSnapshot = RimeSnapshot.empty
        private set

    fun sessionsCreated(): Int = createdSessions

    fun onSubtype(rimeSchema: String?) {
        if (rimeSchema == null) {
            dropSession(commit = false)
            active = false
            return
        }
        schemaId = rimeSchema
        active = true
    }

    fun onStartInput(sharedDir: String, userDir: String, prebuiltDir: String, restarting: Boolean, forceAscii: Boolean) {
        if (!active) return
        forcedAscii = forceAscii
        if (restarting && session != 0L) {
            asciiMode = forcedAscii || client.readSnapshot(session).let { asciiMode }
            if (forcedAscii) {
                asciiMode = true
                client.setOption(session, "ascii_mode", true)
            }
            return
        }
        if (!client.startup(sharedDir, userDir, prebuiltDir)) {
            active = false
            return
        }
        session = client.createSession()
        createdSessions += 1
        client.selectSchema(session, schemaId)
        client.setOption(session, "simplification", true)
        asciiMode = forceAscii
        client.setOption(session, "ascii_mode", asciiMode)
        lastSnapshot = RimeSnapshot.empty
    }

    fun onFinishInput(destroy: Boolean) {
        if (session == 0L) return
        client.clearComposition(session)
        editor.finishComposing()
        lastSnapshot = RimeSnapshot.empty
        if (destroy) {
            client.destroySession(session)
            session = 0
        }
    }

    fun onEventCode(code: Int, shifted: Boolean): Boolean {
        if (!active || session == 0L) return false
        if (code == KeyCode.SHIFT) return false
        if (asciiMode) return handleAscii(code)
        return handleChinese(code, shifted)
    }

    fun onText(text: String): Boolean {
        if (!active || session == 0L || text.isEmpty()) return false
        if (asciiMode) {
            editor.commit(text)
            return true
        }
        endComposition()
        editor.commit(text)
        return true
    }

    fun onVerticalSpaceSwipe(steps: Int): Boolean {
        if (!active || session == 0L) return false
        if (steps <= 0) return false
        if (!swipeLatched) {
            swipeLatched = true
            if (!forcedAscii) {
                if (!asciiMode) {
                    editor.finishComposing()
                    client.clearComposition(session)
                    lastSnapshot = RimeSnapshot.empty
                }
                asciiMode = !asciiMode
                client.setOption(session, "ascii_mode", asciiMode)
            }
        }
        return true
    }

    fun onEndSpaceSwipe() {
        swipeLatched = false
    }

    fun onSelectCandidate(index: Int): Boolean {
        if (!active || session == 0L || asciiMode) return false
        client.selectCandidate(session, index)
        publish()
        return true
    }

    fun onCursorMoved(belated: Boolean) {
        if (!active || session == 0L || belated) return
        if (lastSnapshot.preedit.isEmpty()) return
        client.clearComposition(session)
        editor.finishComposing()
        lastSnapshot = RimeSnapshot.empty
    }

    fun onGlide() {
        // Ignore glide. Commit nothing.
    }

    fun spaceLabel(): String = if (asciiMode) "英文" else "中文"

    private fun handleAscii(code: Int): Boolean {
        val text = when (code) {
            KeyCode.DELETE -> return false
            Constants.CODE_ENTER -> return false
            Constants.CODE_SPACE -> " "
            in 32..126 -> code.toChar().toString()
            else -> return false
        }
        editor.commit(text)
        return true
    }

    private fun handleChinese(code: Int, shifted: Boolean): Boolean {
        when (code) {
            Constants.CODE_SPACE -> client.processKey(session, RimeKeyMapper.XK_SPACE, 0)
            KeyCode.DELETE -> client.processKey(session, RimeKeyMapper.XK_BACKSPACE, 0)
            Constants.CODE_ENTER -> {
                client.commitComposition(session)
                publish()
                return true
            }
            in '1'.code..'9'.code -> client.selectCandidate(session, code - '1'.code)
            in 'a'.code..'z'.code -> client.processKey(session, code, 0)
            in 'A'.code..'Z'.code -> client.processKey(session, RimeKeyMapper.letterKeysym(code), 0)
            else -> {
                if (code in 32..126) client.processKey(session, code, 0) else return false
            }
        }
        if (shifted) {
            // Shift+letter stays in 中文. The keysym above is already lowercase.
        }
        publish()
        return true
    }

    private fun endComposition() {
        client.clearComposition(session)
        editor.finishComposing()
    }

    private fun publish() {
        val snapshot = client.readSnapshot(session)
        if (snapshot.commit.isNotEmpty()) editor.commit(snapshot.commit)
        if (snapshot.preedit.isEmpty()) editor.finishComposing() else editor.setComposing(snapshot.preedit)
        lastSnapshot = snapshot.copy(commit = "")
    }

    private fun dropSession(commit: Boolean) {
        if (session == 0L) return
        if (!commit) client.clearComposition(session)
        editor.finishComposing()
        client.destroySession(session)
        session = 0
        lastSnapshot = RimeSnapshot.empty
    }
}
