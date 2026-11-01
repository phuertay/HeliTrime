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
    private val script: PinyinScriptStore = MemoryScriptStore(),
    private val hanzi: HanziIndex = EmptyHanzi,
) {
    private var asciiMode = false
    private var asciiLocked = false
    private var simplified = true
    private var incognito = false
    private var ready = false
    private var preparing = false
    private var failed = false
    private var hasPreedit = false
    private var swipeLatched = false
    private var lookupOpen = false
    private var passed = false
    private var committing = false
    private var padOpen = false
    private var widened = false
    private var radical = 0
    private val ink = ArrayList<FloatArray>()
    private var menu = emptyList<MenuChar>()
    private val pending = ArrayList<PendingKey>()

    fun onStartInput(keepSession: Boolean, forceAscii: Boolean, incognitoNow: Boolean) {
        val incognitoChanged = ready && incognito != incognitoNow
        if (keepSession && ready && !incognitoChanged && !failed) {
            asciiLocked = forceAscii
            if (forceAscii && !asciiMode) lockAscii()
            val saved = script.read()
            if (saved != simplified) {
                applyScript(saved)
                publishMode()
            }
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
                    applyScript(script.read())
                    publishMode()
                    flushPending()
                }
            }
        }
    }

    fun onFinishInput() {
        // The tap already passed the character. Finishing input drops the connection, so committing here never lands.
        closeLookup()
        dropPreedit()
        client.destroySession()
        ready = false
        hasPreedit = false
        PinyinSpaceLabel.text = null
    }

    fun onLeaveSubtype() {
        closeLookup()
        dropPreedit()
        client.destroySession()
        ready = false
        PinyinSpaceLabel.text = null
        ui.onModeChanged("")
    }

    /** @return true when the key was consumed */
    fun onEvent(codePoint: Int, keyCode: Int, symbolsLayer: Boolean): Boolean {
        if (keyCode == KeyCode.RIME_SCRIPT) return onScriptToggle()
        if (lookupOpen && (keyCode == KeyCode.EMOJI || keyCode == KeyCode.LANGUAGE_SWITCH
                || keyCode == KeyCode.SYMBOL)) {
            closeLookup()
            return false
        }
        if (symbolsLayer && codePoint > 0) {
            closeLookup()
            if (ready && !failed) endCompositionAsText()
            return false
        }
        if (padOpen) {
            if (RimeKeyMapper.isDelete(keyCode)) {
                onUndoStroke()
                return true
            }
            if (RimeKeyMapper.isEnter(codePoint) || RimeKeyMapper.isSpace(codePoint)) return true
            return true
        }
        if (failed) return false
        if (preparing || !ready) {
            if (!wouldConsume(codePoint, keyCode, symbolsLayer)) return false
            pending.add(PendingKey(codePoint, keyCode, symbolsLayer))
            return true
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
            closeLookup()
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
        if (committing) return true
        if (asciiMode || !ready || failed) return false
        if (!hasPreedit || belated) return true
        dropPreedit()
        return true
    }

    fun onStartBatchInput(): Boolean = true

    fun shouldKeepStrip(): Boolean {
        return preparing || failed || (ready && !asciiMode && hasPreedit)
    }

    fun lookupAvailable(): Boolean {
        return ready && !failed && !preparing && !asciiMode && !asciiLocked
    }

    fun onOpenLookup() {
        if (!lookupAvailable()) return
        lookupOpen = true
        widened = false
        radical = 0
        ink.clear()
        menu = if (hasPreedit) collectMenu() else emptyList()
        padOpen = !hasPreedit
        publishLookup()
    }

    fun onCloseLookup() = closeLookup()

    /** Finger-down. The editor is still focused here; the click is not. */
    fun onLookupPress(row: LookupRow) {
        pass(row)
    }

    fun onLookupPick(row: LookupRow) {
        pass(row)
        if (!lookupOpen) return
        ui.showNeutral()
        closeLookup()
    }

    fun onLookupRadical(code: Int) {
        if (!lookupOpen) return
        radical = code
        publishLookup()
    }

    fun onOpenPad() {
        if (!lookupOpen || !lookupAvailable()) return
        padOpen = true
        publishLookup()
    }

    fun onLeavePad() {
        if (!padOpen) return
        ink.clear()
        padOpen = false
        widened = false
        publishLookup()
    }

    fun onLookupStroke(points: FloatArray) {
        if (!padOpen || points.size < 4) return
        ink.add(points)
        publishLookup()
    }

    fun onUndoStroke() {
        if (!padOpen) return
        if (ink.isNotEmpty()) ink.removeAt(ink.lastIndex)
        publishLookup()
    }

    fun onClearInk() {
        if (!padOpen) return
        ink.clear()
        widened = false
        publishLookup()
    }

    fun onWidenLookup() {
        if (!padOpen || !hasPreedit) return
        widened = !widened
        publishLookup()
    }

    fun onLookupScript() {
        if (lookupOpen) onScriptToggle()
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

    /** One character per gesture. A later click or finish must not send it again. */
    private fun pass(row: LookupRow) {
        if (!lookupOpen || passed || row.text.isEmpty()) return
        passed = true
        val text = if (row.page < 0) row.text else {
            gotoPage(row.page)
            client.selectCandidate(row.index)
            val commit = client.readSnapshot().commit
            if (commit.isNotEmpty()) commit else row.text
        }
        commitPlain(text)
        client.clearComposition()
        hasPreedit = false
    }

    private fun commitPlain(text: String) {
        committing = true
        hasPreedit = false
        try {
            editor.commitText(text)
        } finally {
            committing = false
        }
    }

    private fun applySnapshot() {
        val snapshot = client.readSnapshot()
        if (snapshot.commit.isNotEmpty()) commitPlain(snapshot.commit)
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
        if (!lookupOpen) return
        if (snapshot.commit.isNotEmpty()) {
            closeLookup()
        } else {
            if (hasPreedit) menu = collectMenu()
            publishLookup()
        }
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

    private fun onScriptToggle(): Boolean {
        if (failed || asciiLocked) return true
        if (preparing || !ready) {
            script.save(!script.read())
            return true
        }
        val next = !simplified
        script.save(next)
        applyScript(next)
        if (asciiMode) publishMode() else applySnapshot()
        return true
    }

    /** Dict is traditional. `zh_hans` runs OpenCC t2s, so true is simplified. */
    private fun applyScript(simplifiedNow: Boolean) {
        simplified = simplifiedNow
        client.setOption("zh_hans", simplifiedNow)
        client.setOption("zh_hant", !simplifiedNow)
        client.setOption("zh_hant_hk", false)
        client.setOption("zh_hant_tw", false)
        client.setOption("ascii_mode", asciiMode)
    }

    private fun publishLookup() {
        ui.showLookup(buildLookup(
            menu, ink, hanzi, simplified, radical, padOpen, widened, hasPreedit,
        ))
    }

    private fun closeLookup() {
        passed = false
        if (!lookupOpen) return
        lookupOpen = false
        padOpen = false
        widened = false
        radical = 0
        ink.clear()
        menu = emptyList()
        ui.hideLookup()
    }

    private fun collectMenu(): List<MenuChar> {
        val saved = client.readSnapshot().pageNo
        var guard = 0
        while (client.readSnapshot().pageNo > 0 && guard++ < 40) {
            if (!client.changePage(true)) break
        }
        val out = ArrayList<MenuChar>()
        guard = 0
        while (guard++ < 40) {
            val snap = client.readSnapshot()
            snap.candidates.forEachIndexed { index, candidate ->
                out.add(MenuChar(candidate.text, snap.pageNo, index))
            }
            if (snap.isLastPage || !client.changePage(false)) break
        }
        guard = 0
        while (client.readSnapshot().pageNo > saved && guard++ < 40) {
            if (!client.changePage(true)) break
        }
        while (client.readSnapshot().pageNo < saved && guard++ < 40) {
            if (!client.changePage(false)) break
        }
        return out
    }

    private fun gotoPage(page: Int) {
        var guard = 0
        while (client.readSnapshot().pageNo > page && guard++ < 40) {
            if (!client.changePage(true)) return
        }
        while (client.readSnapshot().pageNo < page && guard++ < 40) {
            if (!client.changePage(false)) return
        }
    }

    private fun publishMode() {
        val label = if (asciiMode) "英文" else if (simplified) "中文·简" else "中文·繁"
        PinyinSpaceLabel.text = label
        ui.onModeChanged(label)
    }

    private data class PendingKey(val codePoint: Int, val keyCode: Int, val symbols: Boolean)
}
