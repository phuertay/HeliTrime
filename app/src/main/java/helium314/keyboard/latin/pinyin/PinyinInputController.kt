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
    private val later: (Long, () -> Unit) -> Unit = { _, block -> block() },
    private val inkMemory: HandwritingMemory = HandwritingMemory.NONE,
) {
    private var asciiMode = false
    private var asciiLocked = false
    private var simplified = true
    private var incognito = false
    private var ready = false
    private var preparing = false
    private var failed = false
    private var hasPreedit = false
    private var preeditLength = 0
    private var shown = ""
    /** The page dropped the underline. The spelling is ordinary text until this word ends. */
    private var plain = false
    private var restoring = false
    private var lookupOpen = false
    private var shownLookup: LookupModel? = null
    private var passed = false
    private var committing = false
    private var padOpen = false
    private var widened = false
    private var radical = 0
    private val ink = ArrayList<FloatArray>()
    private var menu = emptyList<MenuChar>()
    private var menuFor: String? = null
    private var shownSnapshot: RimeSnapshot? = null
    private var menuGeneration = 0
    @Volatile private var lookupGeneration = 0
    private var leaveToken = 0
    private val lookupLock = Any()
    private val pending = ArrayList<PendingKey>()

    fun onStartInput(keepSession: Boolean, forceAscii: Boolean, incognitoNow: Boolean) {
        inkMemory.learning = !incognitoNow
        // Firefox on the Cursor page restarts the input while 查 is open. That is the same
        // field, so the pad stays. A different field still drops it.
        if (lookupOpen && leaveToken != 0) {
            leaveToken++
            if (!keepSession) finishNow()
        }
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
        dropMenu()
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
                    simplified = script.read()
                    publishMode()
                } else {
                    applyScript(script.read())
                    publishMode()
                    publishStrip()
                    flushPending()
                }
            }
        }
    }

    fun onFinishInput() {
        // The tap already passed the character. Finishing input drops the connection, so committing here never lands.
        if (!lookupOpen) {
            finishNow()
            return
        }
        // The Cursor page in Firefox finishes the input when the caret blinks, then starts
        // it again. Closing 查 here removes the pad under the finger. A real leave has no
        // following start, so the pad closes once that wait ends.
        val token = ++leaveToken
        later(LEAVE_WAIT_MS) finish@{
            if (token != leaveToken) return@finish
            finishNow()
        }
    }

    private fun finishNow() {
        leaveToken++
        closeLookup(restoreStrip = false)
        dropPreedit()
        client.destroySession()
        ready = false
        hasPreedit = false
        preeditLength = 0
        PinyinSpaceLabel.text = null
    }

    fun onLeaveSubtype() {
        closeLookup(restoreStrip = false)
        endCompositionAsText()
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

    /**
     * @param belated true when this update is the one setComposingText caused.
     * @param cursorWhereWeSetIt true when the cursor is still the one setComposingText recorded.
     * @param spanLength length of the composing span, or -1 when the editor did not report one.
     * Firefox on the Cursor page reports that and then deletes the underline. The spelling is
     * written back as ordinary text. A span that still covers the spelling, with the cursor
     * outside it and the spelling no longer in front of the cursor, is a real move.
     * @param cursorInside true when the cursor is inside a reported span. OneNote sends that for
     * the update setComposingText caused.
     * @return true to skip Latin recorrection. In 中文 that reset desyncs the connection and the
     * next letter never shows.
     */
    fun onUpdateSelection(
        belated: Boolean,
        cursorWhereWeSetIt: Boolean = false,
        spanLength: Int = Int.MAX_VALUE,
        cursorInside: Boolean = false,
    ): Boolean {
        if (committing || restoring) return true
        if (asciiMode || !ready || failed) return false
        if (!hasPreedit) return true
        if (plain) {
            if (belated || cursorWhereWeSetIt || spellingBeforeCursor()) return true
            abandonPreedit()
            return true
        }
        val covers = preeditLength > 0 && spanLength >= preeditLength
        if (covers && (belated || cursorWhereWeSetIt || cursorInside)) return true
        if (covers && spellingBeforeCursor()) return true
        if (covers) {
            // The spelling is still in the field, just not at the cursor: the user moved.
            // If the page deleted it, write it back. Firefox does that while the span still
            // looks long enough.
            if (shown.isNotEmpty() && editor.contains(shown)) dropPreedit() else holdSpellingAsPlainText()
            return true
        }
        // The underline is gone. setComposingText again just flashes and disappears in Firefox.
        holdSpellingAsPlainText()
        return true
    }

    /** Put the spelling back after a restart cleared the composing span. */
    fun restorePreedit() {
        if (!ready || failed || asciiMode || !hasPreedit || restoring) return
        val snapshot = client.readSnapshot()
        if (snapshot.preedit.isEmpty()) return
        restoring = true
        try {
            showPreedit(snapshot.preedit)
            shownSnapshot = snapshot
            ui.showCandidates(snapshot)
        } finally {
            restoring = false
        }
    }

    fun onStartBatchInput(): Boolean = true

    fun shouldKeepStrip(): Boolean {
        // An empty 查 has no spelling. Firefox still restarts the input, and the neutral
        // strip would cover the drawing buttons and poke the page.
        return lookupOpen || preparing || failed || (ready && !asciiMode && hasPreedit)
    }

    fun lookupAvailable(): Boolean {
        return ready && !failed && !preparing && !asciiMode && !asciiLocked
    }

    fun isLookupOpen(): Boolean = lookupOpen

    /**
     * Firefox restarts the input while the pad is open and the restart puts the toolbar
     * over the drawing buttons. Show the same pad again, without waiting for a new score.
     */
    fun onEditorRestarted() {
        if (!lookupOpen) return
        val base = shownLookup ?: buildLookup(
            menu, emptyList(), hanzi, simplified, radical, padOpen, widened, hasPreedit,
        )
        ui.showLookup(base.copy(strokes = ink.map { it.copyOf() }))
    }

    fun onOpenLookup() {
        if (!lookupAvailable()) return
        lookupOpen = true
        widened = false
        radical = 0
        ink.clear()
        if (hasPreedit) ensureMenu(client.readSnapshot().preedit) else dropMenu()
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
        ink.add(points.copyOf())
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
        if (ink.isNotEmpty()) inkMemory.remember(row.text)
        val text = if (row.page < 0) row.text else {
            val commit = client.selectOnPage(row.page, row.index)
            if (commit.isNotEmpty()) commit else row.text
        }
        commitPlain(text)
        client.clearComposition()
        hasPreedit = false
        preeditLength = 0
    }

    private fun commitPlain(text: String) {
        committing = true
        hasPreedit = false
        preeditLength = 0
        plain = false
        shown = ""
        try {
            editor.commitText(text)
        } finally {
            committing = false
        }
    }

    private fun applySnapshot() {
        val snapshot = client.readSnapshot()
        if (snapshot.commit.isNotEmpty()) {
            if (plain) deleteShown()
            commitPlain(snapshot.commit)
        }
        if (!asciiMode && snapshot.preedit.isNotEmpty()) {
            showPreedit(snapshot.preedit)
            shownSnapshot = snapshot
            ui.showCandidates(snapshot)
        } else if (snapshot.preedit.isEmpty()) {
            if (hasPreedit && snapshot.commit.isEmpty()) erasePreedit()
            plain = false
            shown = ""
            hasPreedit = false
            preeditLength = 0
            shownSnapshot = null
            ui.showNeutral()
        }
        publishMode()
        if (menuFor != snapshot.preedit) dropMenu()
        if (!lookupOpen) return
        if (snapshot.commit.isNotEmpty()) {
            closeLookup()
        } else {
            if (hasPreedit) ensureMenu(snapshot.preedit)
            publishLookup()
        }
    }

    /** Preparing is only for the load. Once the engine is up, the strip is candidates or neutral, and neutral shows 查. */
    private fun publishStrip() {
        if (asciiMode) {
            hasPreedit = false
            preeditLength = 0
            shownSnapshot = null
            ui.showNeutral()
            return
        }
        val snapshot = client.readSnapshot()
        if (snapshot.preedit.isNotEmpty()) {
            shown = snapshot.preedit
            hasPreedit = true
            preeditLength = snapshot.preedit.length
            shownSnapshot = snapshot
            ui.showCandidates(snapshot)
        } else {
            plain = false
            shown = ""
            hasPreedit = false
            preeditLength = 0
            shownSnapshot = null
            ui.showNeutral()
        }
    }

    private fun endCompositionAsText() {
        val keep = hasPreedit
        val source = if (plain && shown.isNotEmpty()) shown else if (keep) client.readSnapshot().preedit else ""
        val spelling = if (keep) spellingWithoutSyllableSpaces(source) else ""
        // Clear the flag first. finishComposingText reports a selection update, and that update
        // would otherwise treat the spelling as a cursor jump and delete it.
        hasPreedit = false
        preeditLength = 0
        committing = true
        try {
            if (keep) {
                if (plain) {
                    // The letters are already in the field. Drop syllable spaces only.
                    if (spelling != shown) replacePlain(spelling)
                } else {
                    // The underline shows Rime's syllable spaces. Plain Latin text should not keep them.
                    if (spelling.isNotEmpty()) editor.setComposingText(spelling)
                    editor.finishComposingText()
                }
            }
            client.clearComposition()
        } finally {
            committing = false
            plain = false
            shown = ""
        }
        dropMenu()
    }

    private fun showPreedit(preedit: String) {
        if (plain) replacePlain(preedit) else editor.setComposingText(preedit)
        shown = preedit
        hasPreedit = preedit.isNotEmpty()
        preeditLength = preedit.length
    }

    /** Firefox removed the composing span. Keep the letters as normal text. */
    private fun holdSpellingAsPlainText() {
        if (plain || shown.isEmpty()) return
        val spelling = shown
        plain = true
        committing = true
        try {
            if (editor.textBeforeCursor(spelling.length).endsWith(spelling)) {
                editor.finishComposingText()
            } else {
                editor.commitRaw(spelling)
            }
        } finally {
            committing = false
        }
    }

    private fun replacePlain(preedit: String) {
        val previous = shown
        committing = true
        try {
            if (previous.isNotEmpty() && editor.textBeforeCursor(previous.length).endsWith(previous)) {
                editor.deleteBefore(previous.length)
            }
            if (preedit.isNotEmpty()) editor.commitRaw(preedit)
        } finally {
            committing = false
        }
    }

    private fun deleteShown() {
        if (shown.isEmpty()) return
        committing = true
        try {
            if (editor.textBeforeCursor(shown.length).endsWith(shown)) editor.deleteBefore(shown.length)
        } finally {
            committing = false
        }
    }

    private fun erasePreedit() {
        if (plain) deleteShown() else editor.setComposingText("")
    }

    private fun spellingBeforeCursor(): Boolean {
        return shown.isNotEmpty() && editor.textBeforeCursor(shown.length).endsWith(shown)
    }

    /** The cursor left the spelling. Leave whatever text is there and forget the word. */
    private fun abandonPreedit() {
        client.clearComposition()
        hasPreedit = false
        preeditLength = 0
        plain = false
        shown = ""
        dropMenu()
    }

    private fun dropPreedit() {
        if (plain) deleteShown() else if (hasPreedit) editor.setComposingText("")
        client.clearComposition()
        hasPreedit = false
        preeditLength = 0
        plain = false
        shown = ""
        dropMenu()
    }

    private fun lockAscii() {
        client.setOption("ascii_mode", true)
        asciiMode = true
        publishMode()
    }

    private fun onScriptToggle(): Boolean {
        if (failed || asciiLocked) return true
        if (preparing || !ready) {
            simplified = !script.read()
            script.save(simplified)
            publishMode()
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
        dropMenu()
    }

    private fun publishLookup() {
        val generation = ++lookupGeneration
        if (ink.isEmpty()) {
            val model = buildLookup(
                menu, emptyList(), hanzi, simplified, radical, padOpen, widened, hasPreedit,
            )
            shownLookup = model
            ui.showLookup(model)
            return
        }
        // No spelling ranks every character. Show the line first. A late score for an older
        // line must not replace the strokes drawn since.
        val inkNow = ink.map { it.copyOf() }
        val menuNow = menu
        val simplifiedNow = simplified
        val radicalNow = radical
        val padNow = padOpen
        val widenedNow = widened
        val spellingNow = hasPreedit
        ui.showInk(inkNow)
        starter.start {
            synchronized(lookupLock) {
                if (generation != lookupGeneration) return@start
                val model = try {
                    buildLookup(
                        menuNow, inkNow, hanzi, simplifiedNow, radicalNow, padNow, widenedNow, spellingNow,
                    )
                } catch (_: RuntimeException) {
                    buildLookup(
                        menuNow, emptyList(), hanzi, simplifiedNow, radicalNow, padNow, widenedNow, spellingNow,
                    ).copy(strokes = inkNow)
                }
                deliver {
                    if (generation != lookupGeneration || !lookupOpen) return@deliver
                    shownLookup = model
                    ui.showLookup(model)
                }
            }
        }
    }

    private fun closeLookup(restoreStrip: Boolean = true) {
        passed = false
        if (!lookupOpen) return
        lookupOpen = false
        padOpen = false
        widened = false
        radical = 0
        ink.clear()
        shownLookup = null
        ui.hideLookup()
        if (!restoreStrip || !ready || failed || preparing) return
        val cached = shownSnapshot
        if (!asciiMode && hasPreedit && cached != null && cached.preedit.isNotEmpty()) {
            ui.showCandidates(cached)
        } else {
            ui.showNeutral()
        }
    }

    /**
     * The page walk stays off the UI thread. A loop of page hops there drops the input connection.
     * A second 查 for the same spelling reuses [menu].
     */
    private fun ensureMenu(preedit: String) {
        if (menuFor == preedit) return
        menuFor = preedit
        menu = emptyList()
        val generation = ++menuGeneration
        starter.start {
            val collected = client.readMenu()
            deliver {
                if (generation != menuGeneration) return@deliver
                if (client.readSnapshot().preedit != preedit) return@deliver
                menu = collected
                menuFor = preedit
                if (lookupOpen) publishLookup()
            }
        }
    }

    private fun dropMenu() {
        menuGeneration++
        menu = emptyList()
        menuFor = null
    }

    private fun publishMode() {
        val label = if (simplified) "中文·简" else "中文·繁"
        PinyinSpaceLabel.text = label
        ui.onModeChanged(label)
    }

    private data class PendingKey(val codePoint: Int, val keyCode: Int, val symbols: Boolean)

    private companion object {
        const val LEAVE_WAIT_MS = 200L
    }
}

/** Rime inserts a space between syllables in the preedit. The user did not type it. */
internal fun spellingWithoutSyllableSpaces(preedit: String): String = buildString(preedit.length) {
    for (ch in preedit) if (ch != ' ') append(ch)
}
