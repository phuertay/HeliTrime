package helium314.keyboard.latin.pinyin

import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode

/**
 * Key policy for the luna_pinyin subtype. Latin typing stays in InputLogic:
 * a key this controller does not consume is returned to the caller.
 */
class PinyinInputController(
    private val client: RimeClient,
    private val editor: PinyinEditor,
    private val ui: PinyinUi,
    private val preparingText: String,
    private val failureText: String,
    private val starter: PinyinStarter,
    private val deliver: (block: () -> Unit) -> Unit,
) {
    private var asciiMode = false
    private var asciiLocked = false
    private var incognito = false
    private var ready = false
    private var preparing = false
    private var failed = false
    private var hasPreedit = false
    private var swipeLatched = false
    private val pending = ArrayList<PendingKey>()

    fun onStartInput(keepSession: Boolean, forceAscii: Boolean, incognitoNow: Boolean) {
        val incognitoChanged = ready && incognito != incognitoNow
        if (keepSession && ready && !incognitoChanged && !failed) {
            asciiLocked = forceAscii
            if (forceAscii && !asciiMode) lockAscii()
            return
        }
        asciiLocked = forceAscii
        incognito = incognitoNow
        preparing = true
        ready = false
        ui.showPreparing(preparingText)
        starter.start {
            var ok = false
            try {
                if (incognitoChanged && !incognitoNow) {
                    client.shutdown()
                    client.deleteIncognitoData()
                }
                if (!keepSession) client.destroySession()
                client.ensure(incognitoNow, RimeData.SCHEMA_ID)
                val ascii = forceAscii || (keepSession && asciiMode)
                client.setOption("ascii_mode", ascii)
                asciiMode = ascii
                ok = !client.failed
            } catch (_: Throwable) {
                ok = false
            }
            val started = ok
            deliver {
                preparing = false
                failed = !started
                ready = started
                if (!started) {
                    ui.showFailure(failureText)
                    PinyinSpaceLabel.text = "英文"
                } else {
                    publishMode()
                    flushPending()
                }
            }
        }
    }

    fun onFinishInput() {
        dropPreedit()
        client.destroySession()
        ready = false
        hasPreedit = false
        PinyinSpaceLabel.text = null
    }

    fun onLeaveSubtype() {
        dropPreedit()
        client.destroySession()
        ready = false
        PinyinSpaceLabel.text = null
        ui.onModeChanged("")
    }

    /** @return true when the key was consumed */
    fun onEvent(codePoint: Int, keyCode: Int, symbolsLayer: Boolean): Boolean {
        if (failed) return false
        if (preparing || !ready) {
            if (!wouldConsume(codePoint, keyCode, symbolsLayer)) return false
            pending.add(PendingKey(codePoint, keyCode, symbolsLayer))
            return true
        }
        if (symbolsLayer && codePoint > 0) {
            endCompositionAsText()
            return false
        }
        if (!wouldConsume(codePoint, keyCode, symbolsLayer)) return false
        if (asciiMode) return false
        when {
            RimeKeyMapper.isDelete(keyCode) -> {
                if (!hasPreedit) return false
                client.processKey(RimeKeyMapper.XK_BACKSPACE, 0)
            }
            RimeKeyMapper.isEnter(codePoint) -> {
                if (!hasPreedit) return false
                client.commitComposition()
            }
            codePoint in '1'.code..'9'.code -> client.selectCandidate(codePoint - '1'.code)
            RimeKeyMapper.isSpace(codePoint) -> client.processKey(RimeKeyMapper.XK_SPACE, 0)
            codePoint > 0 -> client.processKey(RimeKeyMapper.lowercaseKeysym(codePoint), 0)
            else -> return false
        }
        applySnapshot()
        return true
    }

    fun onText(text: String): Boolean {
        if (failed || asciiMode) return false
        var consumed = false
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            consumed = onEvent(codePoint, Event.NOT_A_KEY_CODE, false) || consumed
            offset += Character.charCount(codePoint)
        }
        return consumed
    }

    fun onPick(index: Int): Boolean {
        if (!ready || failed || asciiMode || index < 0) return false
        client.selectCandidate(index)
        applySnapshot()
        return true
    }

    fun onPage(backward: Boolean) {
        if (!ready || failed || asciiMode) return
        client.changePage(backward)
        applySnapshot()
    }

    fun onVerticalSpaceSwipe(steps: Int) {
        if (steps <= 0 || swipeLatched || !ready || failed) return
        swipeLatched = true
        if (!asciiMode) {
            endCompositionAsText()
            client.setOption("ascii_mode", true)
            asciiMode = true
            ui.showNeutral()
            publishMode()
        } else if (!asciiLocked) {
            client.setOption("ascii_mode", false)
            asciiMode = false
            publishMode()
        }
    }

    fun onEndSpaceSwipe() {
        swipeLatched = false
    }

    /**
     * @param belated true when this update is the one setComposingText caused, or the cursor is
     * still inside that span. A real move outside the span drops the preedit.
     * @return true to skip Latin recorrection. In 中文 that reset desyncs the connection and the
     * next letter never shows.
     */
    fun onUpdateSelection(belated: Boolean): Boolean {
        if (asciiMode || !ready || failed) return false
        if (!hasPreedit || belated) return true
        dropPreedit()
        return true
    }

    fun onStartBatchInput(): Boolean = true

    fun shouldKeepStrip(): Boolean {
        return preparing || failed || (ready && !asciiMode && hasPreedit)
    }

    fun onIncognitoChanged(incognitoNow: Boolean) {
        if (incognitoNow == incognito && ready) return
        onStartInput(keepSession = false, forceAscii = asciiLocked, incognitoNow = incognitoNow)
    }

    private fun wouldConsume(codePoint: Int, keyCode: Int, symbolsLayer: Boolean): Boolean {
        if (symbolsLayer) return false
        if (RimeKeyMapper.isDelete(keyCode)) return true
        if (codePoint <= 0) return false
        if (keyCode < 0 && keyCode != Event.NOT_A_KEY_CODE) return false
        if (keyCode == KeyCode.EMOJI || keyCode == KeyCode.LANGUAGE_SWITCH) return false
        return true
    }

    private fun flushPending() {
        val queued = pending.toList()
        pending.clear()
        for (key in queued) onEvent(key.codePoint, key.keyCode, key.symbols)
    }

    private fun applySnapshot() {
        val snapshot = client.readSnapshot()
        if (snapshot.asciiMode) asciiMode = true
        if (snapshot.commit.isNotEmpty()) {
            editor.commitText(snapshot.commit)
            hasPreedit = false
        }
        if (!asciiMode && snapshot.preedit.isNotEmpty()) {
            editor.setComposingText(snapshot.preedit)
            hasPreedit = true
            ui.showCandidates(snapshot)
        } else if (snapshot.preedit.isEmpty()) {
            if (hasPreedit && snapshot.commit.isEmpty()) editor.setComposingText("")
            hasPreedit = false
            ui.showNeutral()
        }
        publishMode()
    }

    private fun endCompositionAsText() {
        if (hasPreedit) editor.finishComposingText()
        client.clearComposition()
        hasPreedit = false
    }

    private fun dropPreedit() {
        if (hasPreedit) editor.setComposingText("")
        client.clearComposition()
        hasPreedit = false
    }

    private fun lockAscii() {
        client.setOption("ascii_mode", true)
        asciiMode = true
        publishMode()
    }

    private fun publishMode() {
        val label = if (asciiMode) "英文" else "中文"
        PinyinSpaceLabel.text = label
        ui.onModeChanged(label)
    }

    private data class PendingKey(val codePoint: Int, val keyCode: Int, val symbols: Boolean)
}
