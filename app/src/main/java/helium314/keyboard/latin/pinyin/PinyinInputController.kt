package helium314.keyboard.latin.pinyin

import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import kotlin.jvm.JvmOverloads

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
    private var editorKey = ""
    private val lookupLock = Any()
    private val pending = ArrayList<PendingKey>()

    /** Firefox and Pleco finish the input, then start it again with restarting false. */
    fun continuesOpenLookup(editorKey: String): Boolean {
        return lookupOpen && this.editorKey.isNotEmpty() && editorKey == this.editorKey
    }

    fun onStartInput(
        keepSession: Boolean,
        forceAscii: Boolean,
        incognitoNow: Boolean,
        editorKey: String? = null,
    ) {
        inkMemory.learning = !incognitoNow
        // Firefox on the Cursor page, and Pleco's text field, finish the input and start
        // it again while 查 is open. The framework reports that as a new field. The pad,
        // the ink, and the score stay when the editor is the same one. A different field drops it.
        val incoming = editorKey ?: this.editorKey
        val sameEditor = this.editorKey.isNotEmpty() && incoming == this.editorKey
        val editorChanged = editorKey != null && this.editorKey.isNotEmpty() && editorKey != this.editorKey
        if (editorKey != null) this.editorKey = editorKey
        val incognitoChanged = ready && incognito != incognitoNow
        if (lookupOpen && (leaveToken != 0 || editorChanged)) {
            if (leaveToken != 0) leaveToken++
            if (!keepSession && !sameEditor) finishNow()
        }
        if ((keepSession || (lookupOpen && sameEditor)) && ready && !incognitoChanged && !failed) {
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
                    adoptTrailingSpelling()
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

    @JvmOverloads
    fun onPick(index: Int, page: Int = 0): Boolean {
        if (!ready || failed || asciiMode || index < 0) return false
        if (page <= 0) {
            client.selectCandidate(index)
            applySnapshot()
            return true
        }
        val text = client.selectOnPage(page, index)
        if (text.isEmpty()) return false
        commitPlain(text)
        val snapshot = client.readSnapshot()
        if (snapshot.preedit.isNotEmpty()) {
            showPreedit(snapshot.preedit)
            shownSnapshot = snapshot
            hasPreedit = true
            preeditLength = snapshot.preedit.length
            dropMenu()
            ui.showCandidates(snapshot)
            ensureMenu(snapshot.preedit)
        } else {
            dropMenu()
            shownSnapshot = null
            ui.showNeutral()
        }
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
            showStripFor(snapshot)
            ensureMenu(snapshot.preedit)
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
        if (hasPreedit) {
            // shown is enough. readSnapshot waits behind the page walk and leaves the grid empty.
            val preedit = shown.ifEmpty { client.readSnapshot().preedit }
            if (preedit.isNotEmpty()) ensureMenu(preedit) else dropMenu()
            if (menu.isEmpty()) seedMenuFromShown()
        } else {
            dropMenu()
        }
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
        val spelling = shown
        hasPreedit = false
        preeditLength = 0
        plain = false
        shown = ""
        try {
            // Finish the underline first. Firefox replaces a spaced spelling with only the
            // first character of a multi-character commit.
            editor.finishComposingText()
            val remove = lettersRemovedForCommit(editor.textBeforeCursor(spelling.length.coerceAtLeast(1)), spelling)
            if (remove > 0) editor.deleteBefore(remove)
            editor.commitText(text)
        } finally {
            committing = false
        }
    }

    /** Letters typed on the Latin keyboard become the pinyin spelling. */
    private fun adoptTrailingSpelling() {
        if (asciiMode || asciiLocked || hasPreedit) return
        val spelling = trailingPinyin(editor.textBeforeCursor(24))
        if (spelling.isEmpty()) return
        committing = true
        try {
            editor.deleteBefore(spelling.length)
            for (ch in spelling) client.processKey(RimeKeyMapper.lowercaseKeysym(ch.code), 0)
        } finally {
            committing = false
        }
        applySnapshot()
    }

    private fun applySnapshot() {
        val snapshot = client.readSnapshot()
        if (snapshot.commit.isNotEmpty()) commitPlain(snapshot.commit)
        if (!asciiMode && snapshot.preedit.isNotEmpty()) {
            showPreedit(snapshot.preedit)
            shownSnapshot = snapshot
            showStripFor(snapshot)
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
        if (!asciiMode && hasPreedit && snapshot.preedit.isNotEmpty()) ensureMenu(snapshot.preedit)
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
            showStripFor(snapshot)
            ensureMenu(snapshot.preedit)
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
        if (shown.isEmpty()) return false
        if (editor.textBeforeCursor(shown.length).endsWith(shown)) return true
        // Firefox keeps "zhege" when the underline was "zhe ge". That is still this spelling.
        val compact = spellingWithoutSyllableSpaces(shown)
        return compact != shown && compact.isNotEmpty()
            && editor.textBeforeCursor(compact.length).endsWith(compact)
    }

    /** The page already on screen. 查 can open before the rest of the walk arrives. */
    private fun seedMenuFromShown() {
        if (menu.isNotEmpty()) return
        val snap = shownSnapshot ?: return
        if (snap.candidates.isEmpty()) return
        if (shown.isNotEmpty() && snap.preedit != shown && spellingWithoutSyllableSpaces(snap.preedit) != spellingWithoutSyllableSpaces(shown)) {
            return
        }
        menu = snap.candidates.mapIndexed { index, candidate ->
            MenuChar(candidate.text, snap.pageNo, index, candidate.comment)
        }
    }

    private fun showStripFor(snapshot: RimeSnapshot) {
        if (menuFor == snapshot.preedit && menu.isNotEmpty()) ui.showMenu(menu)
        else ui.showCandidates(snapshot)
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
            if (menuFor == cached.preedit && menu.isNotEmpty()) ui.showMenu(menu)
            else ui.showCandidates(cached)
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
            val collected = collectMenu(preedit) { move -> client.menuPage(preedit, move) }
            deliver {
                if (generation != menuGeneration || collected == null) return@deliver
                if (client.readSnapshot().preedit != preedit) return@deliver
                menu = collected
                menuFor = preedit
                if (!lookupOpen && collected.isNotEmpty() && hasPreedit) ui.showMenu(collected)
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

/**
 * How many characters at the end of [before] belong to [spelling].
 * Firefox may have kept only the first syllable of "zhe ge".
 */
internal fun lettersRemovedForCommit(before: String, spelling: String): Int {
    if (spelling.isEmpty() || before.isEmpty()) return 0
    val compact = spellingWithoutSyllableSpaces(spelling)
    val forms = if (compact == spelling) listOf(spelling) else listOf(spelling, compact)
    var best = 0
    for (form in forms) {
        val limit = minOf(before.length, form.length)
        var n = limit
        while (n > best) {
            if (before.endsWith(form.substring(0, n))) {
                best = n
                break
            }
            n--
        }
    }
    val tail = spelling.substringAfterLast(' ')
    if (tail != spelling && tail.isNotEmpty() && before.endsWith(tail)) best = maxOf(best, tail.length)
    return best
}

/** The Latin word at the cursor, when it is pinyin. Empty otherwise. */
internal fun trailingPinyin(before: String): String {
    var start = before.length
    while (start > 0) {
        val ch = before[start - 1]
        if (ch !in 'a'..'z' && ch !in 'A'..'Z' && ch != '\'') break
        start--
    }
    val word = before.substring(start)
    if (word.isEmpty() || word.length > 16 || word.all { it == '\'' }) return ""
    return if (canTypePinyin(word.lowercase())) word else ""
}

internal fun canTypePinyin(raw: String): Boolean {
    val text = raw.lowercase()
    if (text.isEmpty() || text.length > 16) return false
    val n = text.length
    val reached = BooleanArray(n + 1)
    reached[0] = true
    for (i in 0 until n) {
        if (!reached[i]) continue
        if (text[i] == '\'') {
            reached[i + 1] = true
            continue
        }
        val limit = minOf(6, n - i)
        for (len in 1..limit) {
            if (text.substring(i, i + len) in PINYIN_SYLLABLES) reached[i + len] = true
        }
    }
    if (reached[n]) return true
    for (i in 0 until n) {
        if (!reached[i]) continue
        val tail = text.substring(i)
        if (tail.length <= 6 && PINYIN_SYLLABLES.any { it.startsWith(tail) }) return true
    }
    return false
}

private val PINYIN_SYLLABLES = """
a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu
chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo
da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo
e ei en eng er fa fan fang fei fen feng fo fou fu
ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo
la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lue lun luo lv lve
ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nve nuo nv
o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu
shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo
ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
wa wai wan wang wei wen weng wo wu
xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu
zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo
""".trim().split(Regex("\\s+")).toHashSet()
