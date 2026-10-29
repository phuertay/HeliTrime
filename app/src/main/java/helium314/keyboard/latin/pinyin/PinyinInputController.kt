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
    private var preparing: Boolean = false
    private var failed: Boolean = false
    private var incognito: Boolean = false
    private var prepareGeneration: Int = 0
    private val pending = ArrayList<Pending>()
    var lastSnapshot: RimeSnapshot = RimeSnapshot.empty
        private set

    fun sessionsCreated(): Int = createdSessions
    fun isPreparing(): Boolean = preparing
    fun isFailed(): Boolean = failed
    fun isActive(): Boolean = active
    fun incognitoDiffers(value: Boolean): Boolean = incognito != value

    fun onSubtype(rimeSchema: String?) {
        prepareGeneration += 1
        if (rimeSchema == null) {
            dropSession(commit = false)
            active = false
            preparing = false
            failed = false
            pending.clear()
            return
        }
        schemaId = rimeSchema
        active = true
    }

    fun beginPreparing(): Int {
        if (!active) return prepareGeneration
        preparing = true
        failed = false
        prepareGeneration += 1
        return prepareGeneration
    }

    fun isPrepareCurrent(token: Int): Boolean = active && preparing && token == prepareGeneration

    fun markFailed() {
        preparing = false
        prepareGeneration += 1
        failAndCommitQueued()
    }

    fun onStartInput(
        sharedDir: String,
        userDir: String,
        prebuiltDir: String,
        restarting: Boolean,
        forceAscii: Boolean,
        incognito: Boolean = false,
    ): Boolean {
        if (!active) return false
        val keep = restarting && session != 0L && this.incognito == incognito && !failed && !preparing
        this.forcedAscii = forceAscii
        if (keep) {
            if (forceAscii && !asciiMode) {
                asciiMode = true
                client.setOption(session, "ascii_mode", true)
                client.clearComposition(session)
                editor.finishComposing()
                lastSnapshot = RimeSnapshot.empty
            }
            return true
        }
        this.incognito = incognito
        preparing = false
        prepareGeneration += 1
        if (session != 0L) dropSession(commit = false)
        if (!incognito) client.releaseIncognitoDir()
        if (!client.startup(sharedDir, userDir, prebuiltDir)) {
            failAndCommitQueued()
            return false
        }
        session = client.createSession()
        createdSessions += 1
        if (session == 0L || !client.selectSchema(session, schemaId)) {
            if (session != 0L) client.destroySession(session)
            session = 0
            failAndCommitQueued()
            return false
        }
        client.setOption(session, "simplification", true)
        asciiMode = forceAscii
        client.setOption(session, "ascii_mode", asciiMode)
        failed = false
        lastSnapshot = RimeSnapshot.empty
        flushPending(commitRaw = false)
        return true
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

    fun onEventCode(code: Int, shifted: Boolean, symbols: Boolean = false): Boolean {
        if (!active || failed) return false
        if (code == KeyCode.SHIFT) return false
        if (preparing) {
            if (code < 0) return false
            pending.add(Pending.Key(code, shifted, symbols))
            return true
        }
        if (session == 0L) return false
        if (symbols && code in 32..126) return insertRaw(code)
        if (asciiMode) return handleAscii(code)
        return handleChinese(code)
    }

    fun onText(text: String): Boolean {
        if (!active || failed || text.isEmpty()) return false
        if (preparing) {
            pending.add(Pending.Text(text))
            return true
        }
        if (session == 0L) return false
        if (asciiMode) {
            editor.commit(text)
            return true
        }
        endComposition()
        editor.commit(text)
        return true
    }

    fun onVerticalSpaceSwipe(steps: Int): Boolean {
        if (!active || failed) return false
        if (steps <= 0) return false
        if (preparing || session == 0L) return true
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
        if (!active || failed || preparing || session == 0L || asciiMode) return false
        client.selectCandidate(session, index)
        publish()
        return true
    }

    fun onPage(delta: Int): Boolean {
        if (!active || failed || preparing || session == 0L || asciiMode || delta == 0) return false
        val keysym = if (delta > 0) RimeKeyMapper.XK_NEXT else RimeKeyMapper.XK_PRIOR
        client.processKey(session, keysym, 0)
        publish()
        return true
    }

    fun onCursorMoved(belated: Boolean) {
        if (!active || failed || session == 0L || belated) return
        if (lastSnapshot.preedit.isEmpty()) return
        client.clearComposition(session)
        editor.finishComposing()
        lastSnapshot = RimeSnapshot.empty
    }

    fun onGlide() {
        // Ignore glide. Commit nothing.
    }

    fun blocksNeutralStrip(): Boolean {
        if (!active) return false
        if (preparing || failed) return true
        if (asciiMode) return false
        return lastSnapshot.preedit.isNotEmpty() || lastSnapshot.candidates.isNotEmpty()
    }

    fun spaceLabel(): String = if (asciiMode) "英文" else "中文"

    private fun insertRaw(code: Int): Boolean {
        endComposition()
        editor.commit(code.toChar().toString())
        return true
    }

    private fun handleAscii(code: Int): Boolean {
        val text = when (code) {
            KeyCode.DELETE -> return false
            Constants.CODE_ENTER -> return false
            Constants.CODE_SPACE -> " "
            in 32..126 -> code.toChar().toString()
            else -> return false
        }
        editor.commit(text)
        lastSnapshot = RimeSnapshot.empty
        return true
    }

    private fun handleChinese(code: Int): Boolean {
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
        publish()
        return true
    }

    private fun endComposition() {
        client.clearComposition(session)
        editor.finishComposing()
        lastSnapshot = RimeSnapshot.empty
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

    private fun failAndCommitQueued() {
        failed = true
        session = 0
        flushPending(commitRaw = true)
    }

    private fun flushPending(commitRaw: Boolean) {
        val queued = pending.toList()
        pending.clear()
        if (commitRaw) {
            for (item in queued) {
                when (item) {
                    is Pending.Key -> if (item.code in 32..126) editor.commit(item.code.toChar().toString())
                    is Pending.Text -> editor.commit(item.text)
                }
            }
            return
        }
        for (item in queued) {
            when (item) {
                is Pending.Key -> onEventCode(item.code, item.shifted, item.symbols)
                is Pending.Text -> onText(item.text)
            }
        }
    }

    private sealed class Pending {
        class Key(val code: Int, val shifted: Boolean, val symbols: Boolean) : Pending()
        class Text(val text: String) : Pending()
    }
}
