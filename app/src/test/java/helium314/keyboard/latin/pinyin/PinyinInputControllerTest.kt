package helium314.keyboard.latin.pinyin

import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.internal.KeyboardCodesSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.InputStyle
import helium314.keyboard.latin.suggestions.SuggestedWordInfo
import helium314.keyboard.latin.suggestions.SuggestedWords
import helium314.keyboard.latin.dictionary.Dictionary
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import android.widget.LinearLayout
import java.util.ArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class PinyinInputControllerTest {
    private var lookupRows: List<LookupRow> = emptyList()
    private var lookupPad = false
    private var lookupStrokes = 0
    private var strip = ""
    private var stripMenu = listOf<String>()

    @Test
    fun numpadIsNotSentToRime() {
        assertTrue(keyboardIsSymbols(KeyboardElement.NUMPAD))
        assertTrue(keyboardIsSymbols(KeyboardElement.PHONE))
        assertFalse(keyboardIsSymbols(KeyboardElement.ALPHABET))
    }

    @Test
    fun englishSubtypeConstructsNoRimeSession() {
        assertFalse(isRimeSubtype("AsciiCapable,EmojiCapable,KeyboardLayoutSet=qwerty"))
        assertEquals(0, RimeSessionFactory.created)
    }

    @Test
    fun nihaoSelectBackspaceCursorAndUnconsumedKey() {
        val client = FakeRimeClient()
        val editor = FakeEditor()
        val controller = controller(client, editor)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        for (letter in "nihao") controller.onEvent(letter.code, 0, false)
        assertEquals("nihao", editor.composing)
        assertTrue(controller.onUpdateSelection(belated = true))
        assertEquals("nihao", editor.composing)
        controller.onPick(0)
        assertEquals("你好", editor.committed)
        assertEquals("", editor.composing)

        for (letter in "nihao") controller.onEvent(letter.code, 0, false)
        controller.onEvent(0, KeyCode.DELETE, false)
        assertEquals("niha", editor.composing)

        editor.beforeOverride = "x"
        controller.onUpdateSelection(belated = false, cursorInside = false, spanLength = 4)
        assertEquals("", editor.composing)
        assertFalse(controller.onEvent(0, KeyCode.DELETE, false))

        for (letter in "ni") controller.onEvent(letter.code, 0, false)
        controller.onUpdateSelection(belated = false, cursorWhereWeSetIt = true)
        assertEquals("ni", editor.composing)
        editor.composing = ""
        editor.beforeOverride = ""
        controller.onUpdateSelection(belated = false, cursorWhereWeSetIt = false, spanLength = -1)
        editor.beforeOverride = null
        assertEquals("你好ni", editor.committed)
        controller.onEvent('h'.code, 0, false)
        assertEquals("你好nih", editor.committed)
        assertEquals("", editor.composing)
        controller.restorePreedit()
        assertEquals("你好nih", editor.committed)

        assertFalse(controller.onEvent(-1, KeyCode.EMOJI, false))
    }

    @Test
    fun firefoxRemovesTheUnderlineAndTheNextLetterExtendsTheSpelling() {
        val client = FakeRimeClient()
        val editor = FakeEditor()
        val controller = controller(client, editor)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        controller.onEvent('n'.code, 0, false)
        editor.composing = ""
        editor.beforeOverride = ""
        controller.onUpdateSelection(belated = false, cursorInside = false, spanLength = 1)
        editor.beforeOverride = null
        assertEquals("n", editor.committed)
        assertEquals("", editor.composing)
        controller.onEvent('i'.code, 0, false)
        assertEquals("ni", editor.committed)
        assertEquals("", editor.composing)
    }

    @Test
    fun switchingToEnglishKeepsTheSpelling() {
        val globeClient = FakeRimeClient()
        val globeEditor = FakeEditor()
        val globe = controller(globeClient, globeEditor)
        globeEditor.onFinished = { globe.onUpdateSelection(belated = false) }
        globe.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        globe.onEvent('j'.code, 0, false)
        globe.onEvent('i'.code, 0, false)
        globe.onLeaveSubtype()
        assertEquals("ji", globeEditor.finishedText)
        assertFalse(globeEditor.wipedAfterFinish)
    }

    @Test
    fun leavingChineseDropsSyllableSpaces() {
        assertEquals("nihao", spellingWithoutSyllableSpaces("ni hao"))
        assertEquals("xi'an", spellingWithoutSyllableSpaces("xi 'an"))
        val client = FakeRimeClient()
        val editor = FakeEditor()
        val controller = controller(client, editor)
        editor.onFinished = { controller.onUpdateSelection(belated = false) }
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        client.shown = "ni hao"
        controller.onEvent('n'.code, 0, false)
        assertEquals("ni hao", editor.composing)
        controller.onLeaveSubtype()
        assertEquals("nihao", editor.finishedText)
        assertEquals("", editor.composing)
    }

    @Test
    fun passwordFieldTypesLatinAndDoesNotCompose() {
        val client = FakeRimeClient()
        val editor = FakeEditor()
        val controller = controller(client, editor)
        controller.onStartInput(keepSession = false, forceAscii = true, incognitoNow = false)
        assertTrue(client.getOption("ascii_mode"))
        assertEquals("中文·简", PinyinSpaceLabel.text)
        assertFalse(controller.onEvent('a'.code, 0, false))
        assertEquals("", editor.composing)
        assertTrue(editorForcesAscii(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))
        assertTrue(editorForcesAscii(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertTrue(editorForcesAscii(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(editorForcesAscii(InputType.TYPE_CLASS_TEXT))
    }

    @Test
    fun commaScriptToggleFlipsSimplifiedAndTheNextSessionKeepsIt() {
        assertEquals(KeyCode.RIME_SCRIPT, KeyboardCodesSet.getCode("key_rime_script"))
        assertTrue(commaOffersScriptToggle(true, InputType.TYPE_CLASS_TEXT))
        assertFalse(commaOffersScriptToggle(false, InputType.TYPE_CLASS_TEXT))
        assertFalse(commaOffersScriptToggle(true, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(commaOffersScriptToggle(true, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(commaOffersScriptToggle(true, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(commaOffersScriptToggle(true, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))

        val store = MemoryScriptStore()
        val client = FakeRimeClient()
        val editor = FakeEditor()
        val controller = controller(client, editor, store)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        assertTrue(client.getOption("zh_hans"))
        assertEquals("中文·简", PinyinSpaceLabel.text)
        controller.onEvent('n'.code, 0, false)
        assertTrue(controller.onEvent(Event.NOT_A_CODE_POINT, KeyCode.RIME_SCRIPT, false))
        assertFalse(client.getOption("zh_hans"))
        assertTrue(client.getOption("zh_hant"))
        assertFalse(client.getOption("zh_hant_hk"))
        assertFalse(client.getOption("ascii_mode"))
        assertEquals("n", editor.composing)
        assertEquals("", editor.committed)
        assertEquals("中文·繁", PinyinSpaceLabel.text)
        assertTrue(controller.onEvent('i'.code, 0, false))
        assertEquals("ni", editor.composing)

        val next = FakeRimeClient()
        controller(next, FakeEditor(), store).onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        assertFalse(next.getOption("zh_hans"))
        assertEquals("中文·繁", PinyinSpaceLabel.text)
    }

    @Test
    fun lockedAsciiAndEnglishModeKeepTheScriptKeyFromInserting() {
        val lockedClient = FakeRimeClient()
        val locked = controller(lockedClient, FakeEditor(), MemoryScriptStore())
        locked.onStartInput(keepSession = false, forceAscii = true, incognitoNow = false)
        assertTrue(locked.onEvent(Event.NOT_A_CODE_POINT, KeyCode.RIME_SCRIPT, false))
        assertTrue(lockedClient.getOption("zh_hans"))

        val client = FakeRimeClient()
        val controller = controller(client, FakeEditor(), MemoryScriptStore())
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        assertTrue(controller.onEvent(Event.NOT_A_CODE_POINT, KeyCode.RIME_SCRIPT, false))
        assertFalse(client.getOption("zh_hans"))
        assertEquals("中文·繁", PinyinSpaceLabel.text)
    }

    @Test
    fun keptSessionReappliesARestoredScript() {
        val store = MemoryScriptStore(true)
        val client = FakeRimeClient()
        val controller = controller(client, FakeEditor(), store)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        assertTrue(client.getOption("zh_hans"))
        store.value = false
        controller.onStartInput(keepSession = true, forceAscii = false, incognitoNow = false)
        assertFalse(client.getOption("zh_hans"))
        assertTrue(client.getOption("zh_hant"))
        assertFalse(client.getOption("ascii_mode"))
        assertEquals("中文·繁", PinyinSpaceLabel.text)
    }

    @Test
    fun latinStripIsNotARimeMenuAndATapSelectsThePinyinIndex() {
        val latin = SuggestedWords(
            ArrayList(listOf(latinInfo("the"), latinInfo("then"), latinInfo("they"))),
            null, null, true, false, false,
            InputStyle.TYPING, SuggestedWords.NOT_A_SEQUENCE_NUMBER,
        )
        assertFalse(RimeCandidateRow.isRime(latin))
        assertEquals(3, latin.size)

        val menu = rimeSuggestedWords(RimeSnapshot(
            preedit = "ni hao",
            candidates = listOf(RimeCandidate("你好", "ni hao"), RimeCandidate("倪浩", "ni hao")),
            isLastPage = true,
        ))
        assertTrue(RimeCandidateRow.isRime(menu))
        val parent = LinearLayout(RuntimeEnvironment.getApplication())
        var picked = -1
        RimeCandidateRow.bind(parent, menu, { picked = it.mCandidateIndex }, {})
        val scroll = parent.getChildAt(0) as android.widget.HorizontalScrollView
        val row = scroll.getChildAt(0) as LinearLayout
        row.getChildAt(1).performClick()
        assertEquals(1, picked)
        assertFalse(scroll.isFocusable)
    }

    @Test
    fun candidateSlidePagesOnlyPastTheEdge() {
        assertEquals(CandidateSlide.STAY, candidateSlide(0f, 2000f, 36f, 8f, 50f, true, true))
        assertEquals(CandidateSlide.NEXT, candidateSlide(-36f, 0f, 36f, 8f, 50f, true, true))
        assertEquals(CandidateSlide.PREVIOUS, candidateSlide(36f, 0f, 36f, 8f, 50f, true, true))
        assertEquals(CandidateSlide.STAY, candidateSlide(-36f, 0f, 36f, 8f, 50f, true, false))
        assertEquals(CandidateSlide.STAY, candidateSlide(36f, 0f, 36f, 8f, 50f, false, true))
        assertEquals(CandidateSlide.NEXT, candidateSlide(-10f, -400f, 36f, 8f, 50f, false, true))
        assertEquals(CandidateSlide.STAY, candidateSlide(-10f, -400f, 36f, 8f, 50f, false, false))
        assertEquals(CandidateSlide.STAY, candidateSlide(-4f, -4000f, 36f, 8f, 50f, true, true))
    }

    @Test
    fun stripScrollsWhenTheRowIsWiderAndTheFingerIsNotAtTheEdge() {
        assertFalse(stripCanScroll(20f, 0, 100, 180))
        assertTrue(stripCanScroll(-20f, 0, 100, 180))
        assertTrue(stripCanScroll(20f, 10, 100, 180))
        assertFalse(stripCanScroll(-20f, 80, 100, 180))
        assertTrue(stripCanScroll(20f, 80, 100, 180))
        assertFalse(stripCanScroll(-20f, 0, 100, 100))
    }

    @Test
    fun slidingTheCandidateRowMovesItAndTurnsThePage() {
        var picked = -1
        var pages = 0
        var backward = false
        val parent = openRow(pageNo = 0, lastPage = false, onPick = { picked = it.mCandidateIndex }, onPage = {
            pages += 1
            backward = it
        })
        val scroll = parent.getChildAt(0) as HorizontalScrollView
        val row = scroll.getChildAt(0) as android.widget.LinearLayout
        val hit = row.getChildAt(0)
        val x = hit.left + hit.width / 2f
        val y = scroll.height / 2f
        drag(scroll, x, y, x - 220f)
        assertTrue(row.translationX < -40f)
        lift(scroll, x - 220f, y)
        assertEquals(1, pages)
        assertFalse(backward)
        assertEquals(-1, picked)

        val first = openRow(pageNo = 0, lastPage = false, onPick = {}, onPage = { pages += 1 })
        val firstScroll = first.getChildAt(0) as HorizontalScrollView
        val firstRow = firstScroll.getChildAt(0) as android.widget.LinearLayout
        drag(firstScroll, x, y, x + 220f)
        assertTrue(firstRow.translationX > 40f)
        lift(firstScroll, x + 220f, y)
        assertEquals(1, pages)

        val middle = openRow(pageNo = 1, lastPage = false, onPick = {}, onPage = {
            pages += 1
            backward = it
        })
        val middleScroll = middle.getChildAt(0) as HorizontalScrollView
        drag(middleScroll, x, y, x + 220f)
        lift(middleScroll, x + 220f, y)
        assertEquals(2, pages)
        assertTrue(backward)
    }

    @Test
    fun emptyLookupOpensThePadAndASpellingStaysOnTheGrid() {
        val bare = controller(FakeRimeClient(), FakeEditor())
        bare.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        bare.onOpenLookup()
        assertTrue(lookupPad)

        val spelled = controller(FakeRimeClient(), FakeEditor())
        spelled.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        spelled.onEvent('n'.code, 0, false)
        spelled.onOpenLookup()
        assertFalse(lookupPad)
    }

    @Test
    fun drawingWithNoLettersKeepsEveryStrokeUntilTheScoreReturns() {
        val queued = ArrayList<() -> Unit>()
        val controller = controller(
            FakeRimeClient(),
            FakeEditor(),
            hanzi = object : HanziIndex {
                override fun radical(codePoint: Int) = 0
                override fun strokeCount(codePoint: Int) = 0
                override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int) =
                    listOf('人'.code)
                override fun common() = intArrayOf('人'.code)
                override fun radicalLabel(radical: Int, simplified: Boolean) = ""
            },
            starter = PinyinStarter { queued.add(it) },
        )
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        while (queued.isNotEmpty()) queued.removeAt(0).invoke()
        controller.onOpenLookup()
        assertTrue(lookupPad)
        controller.onLookupStroke(floatArrayOf(0f, 0f, 40f, 0f))
        assertEquals(1, lookupStrokes)
        controller.onLookupStroke(floatArrayOf(0f, 8f, 0f, 40f))
        assertEquals(2, lookupStrokes)
        assertEquals(2, queued.size)
        queued.removeAt(0).invoke()
        assertEquals(2, lookupStrokes)
        queued.removeAt(0).invoke()
        assertEquals(2, lookupStrokes)
        assertEquals(listOf("人"), lookupRows.map { it.text })
        controller.onUndoStroke()
        assertEquals(1, lookupStrokes)
        queued.removeAt(0).invoke()
        assertEquals(1, lookupStrokes)
        controller.onClearInk()
        assertEquals(0, lookupStrokes)
    }

    @Test
    fun padSpaceStaysPutAndAnOutsideCharacterDropsTheSpelling() {
        val client = FakeRimeClient()
        val editor = FakeEditor()
        val controller = controller(client, editor, hanzi = object : HanziIndex {
            override fun radical(codePoint: Int) = 0
            override fun strokeCount(codePoint: Int) = 0
            override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int) =
                pool.filter { it == '人'.code }.toList()
            override fun common() = intArrayOf('人'.code)
            override fun radicalLabel(radical: Int, simplified: Boolean) = ""
        })
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        controller.onEvent('n'.code, 0, false)
        controller.onOpenLookup()
        controller.onOpenPad()
        controller.onLookupStroke(floatArrayOf(0f, 0f, 8f, 8f))
        assertTrue(controller.onEvent(' '.code, 0, false))
        assertEquals("n", editor.composing)
        assertEquals("", editor.committed)
        controller.onEvent(0, KeyCode.DELETE, false)
        assertEquals(listOf("n"), lookupRows.map { it.text })
        controller.onLookupStroke(floatArrayOf(0f, 0f, 8f, 8f))
        controller.onWidenLookup()
        controller.onLookupPick(lookupRows.first { it.text == "人" })
        assertEquals("人", editor.committed)
        assertEquals("", editor.composing)
    }

    @Test
    fun pressCommitsBeforeTheFieldLosesFocus() {
        val editor = FakeEditor()
        val controller = controller(FakeRimeClient(), editor)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        controller.onOpenLookup()
        controller.onLookupPress(LookupRow("人", -1, -1))
        controller.onFinishInput()
        assertEquals("人", editor.committed)
    }

    @Test
    fun aCancelledClickDoesNotCommitTwice() {
        val editor = FakeEditor()
        val controller = controller(FakeRimeClient(), editor)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        controller.onOpenLookup()
        controller.onLookupPress(LookupRow("人", -1, -1))
        controller.onLookupPick(LookupRow("人", -1, -1))
        controller.onFinishInput()
        assertEquals("人", editor.committed)
    }

    @Test
    fun lookupKeepsACharacterPastPageForty() {
        val client = FakeRimeClient()
        val editor = FakeEditor()
        client.pages = List(45) { page ->
            listOf(RimeCandidate(if (page == 44) "系" else "字$page", ""))
        }
        val controller = controller(client, editor)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        controller.onEvent('j'.code, 0, false)
        controller.onEvent('i'.code, 0, false)
        controller.onOpenLookup()
        val row = lookupRows.first { it.text == "系" }
        assertEquals(44, row.page)
        val after = client.snapshots
        controller.onCloseLookup()
        controller.onOpenLookup()
        assertEquals(after + 1, client.snapshots)
        controller.onLookupPress(row)
        assertEquals("系", editor.committed)
    }

    @Test
    fun secondLookupReusesTheMenuAndStartupLeavesTheNeutralStrip() {
        val client = FakeRimeClient()
        val controller = controller(client, FakeEditor())
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        assertEquals("", strip)
        controller.onEvent('j'.code, 0, false)
        controller.onEvent('i'.code, 0, false)
        controller.onOpenLookup()
        val afterFirst = client.snapshots
        controller.onCloseLookup()
        controller.onOpenLookup()
        assertEquals(afterFirst + 1, client.snapshots)
        controller.onCloseLookup()
        val beforeScript = client.snapshots
        controller.onEvent(0, KeyCode.RIME_SCRIPT, false)
        assertTrue(client.snapshots > beforeScript + 1)
        val afterScript = client.snapshots
        controller.onOpenLookup()
        assertEquals(afterScript + 1, client.snapshots)
    }

    @Test
    fun stripListsEveryCandidateTheGridWouldShow() {
        val client = FakeRimeClient()
        client.pages = listOf(
            listOf(RimeCandidate("你", "ni"), RimeCandidate("泥", "ni")),
            listOf(RimeCandidate("呢", "ne"), RimeCandidate("拟", "ni")),
        )
        val editor = FakeEditor()
        val controller = controller(client, editor)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        controller.onEvent('n'.code, 0, false)
        assertEquals(listOf("你", "泥", "呢", "拟"), stripMenu)
        val words = rimeSuggestedWords(stripMenu.mapIndexed { index, text ->
            MenuChar(text, index / 2, index % 2)
        })
        assertTrue(words.mRimeLastPage)
        assertEquals(0, words.mRimePageNo)
        assertEquals(4, words.size)
        assertTrue(controller.onPick(1, 1))
        assertEquals("拟", editor.committed)
    }

    @Test
    fun globeLeavesTheSubtypeSwitchAndDoesNotOpenAnEnglishMode() {
        val client = FakeRimeClient()
        val editor = FakeEditor()
        val controller = controller(client, editor)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        controller.onEvent('j'.code, 0, false)
        controller.onEvent('i'.code, 0, false)
        assertFalse(controller.onEvent(Event.NOT_A_CODE_POINT, KeyCode.LANGUAGE_SWITCH, false))
        assertFalse(client.getOption("ascii_mode"))
        assertEquals("中文·简", PinyinSpaceLabel.text)
        assertEquals("ji", editor.composing)
        assertEquals("", editor.finishedText)

        val locked = controller(FakeRimeClient(), FakeEditor())
        locked.onStartInput(keepSession = false, forceAscii = true, incognitoNow = false)
        assertFalse(locked.onEvent(Event.NOT_A_CODE_POINT, KeyCode.LANGUAGE_SWITCH, false))
        assertEquals("中文·简", PinyinSpaceLabel.text)
    }

    @Test
    fun emptyLookupKeepsTheStripAndSurvivesAFirefoxRestart() {
        val pending = ArrayList<() -> Unit>()
        val client = FakeRimeClient()
        val controller = controller(client, FakeEditor(), later = { _, block -> pending.add(block) })
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        assertFalse(controller.shouldKeepStrip())
        controller.onOpenLookup()
        assertTrue(controller.shouldKeepStrip())
        assertTrue(controller.isLookupOpen())
        controller.onLookupStroke(floatArrayOf(0f, 0f, 40f, 0f))
        assertEquals(1, lookupStrokes)
        controller.onFinishInput()
        assertTrue(controller.isLookupOpen())
        assertEquals(1, pending.size)
        controller.onStartInput(keepSession = true, forceAscii = false, incognitoNow = false)
        pending.removeAt(0).invoke()
        assertTrue(controller.isLookupOpen())
        assertEquals(1, lookupStrokes)
        assertTrue(client.hasSession())
        controller.onFinishInput()
        pending.removeAt(0).invoke()
        assertFalse(controller.isLookupOpen())
        assertFalse(client.hasSession())
    }

    @Test
    fun aRestartShowsTheDrawnPadAgain() {
        val pending = ArrayList<() -> Unit>()
        val controller = controller(
            FakeRimeClient(), FakeEditor(),
            deliver = { pending.add(it) },
            hanzi = object : HanziIndex {
                override fun radical(codePoint: Int) = 0
                override fun strokeCount(codePoint: Int) = 0
                override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int) =
                    listOf('人'.code)
                override fun common() = intArrayOf('人'.code)
                override fun radicalLabel(radical: Int, simplified: Boolean) = ""
            },
        )
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        pending.removeAt(0).invoke()
        controller.onOpenLookup()
        controller.onLookupStroke(floatArrayOf(0f, 0f, 40f, 0f))
        lookupRows = emptyList()
        lookupStrokes = 0
        controller.onEditorRestarted()
        assertTrue(controller.isLookupOpen())
        assertTrue(lookupPad)
        assertEquals(1, lookupStrokes)
        pending.removeAt(0).invoke()
        assertEquals(listOf("人"), lookupRows.map { it.text })
        assertEquals(1, lookupStrokes)
    }

    @Test
    fun sameEditorRestartKeepsTheDrawingAndItsCharacters() {
        val pending = ArrayList<() -> Unit>()
        val later = ArrayList<() -> Unit>()
        val client = FakeRimeClient()
        val firefox = "org.mozilla.firefox|4|1"
        val controller = controller(
            client, FakeEditor(),
            hanzi = personHanzi(),
            deliver = { pending.add(it) },
            later = { _, block -> later.add(block) },
        )
        controller.onStartInput(false, false, false, firefox)
        pending.removeAt(0).invoke()
        controller.onOpenLookup()
        controller.onLookupStroke(floatArrayOf(0f, 0f, 40f, 0f))
        controller.onFinishInput()
        lookupRows = emptyList()
        lookupStrokes = 0
        // restarting is false. Firefox and Pleco both report a new field here.
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false, editorKey = firefox)
        assertTrue(controller.isLookupOpen())
        assertTrue(controller.continuesOpenLookup(firefox))
        assertTrue(client.hasSession())
        later.removeAt(0).invoke()
        assertTrue(controller.isLookupOpen())
        controller.onEditorRestarted()
        assertTrue(lookupPad)
        assertEquals(1, lookupStrokes)
        pending.removeAt(0).invoke()
        assertEquals(listOf("人"), lookupRows.map { it.text })
        assertEquals(1, lookupStrokes)
    }

    @Test
    fun aDifferentEditorDropsTheDrawing() {
        val pending = ArrayList<() -> Unit>()
        val pleco = "com.pleco.chinesesystem|1|1"
        val controller = controller(
            FakeRimeClient(), FakeEditor(),
            hanzi = personHanzi(),
            deliver = { pending.add(it) },
            later = { _, block -> },
        )
        controller.onStartInput(false, false, false, pleco)
        pending.removeAt(0).invoke()
        controller.onOpenLookup()
        controller.onLookupStroke(floatArrayOf(0f, 0f, 40f, 0f))
        controller.onFinishInput()
        controller.onStartInput(false, false, false, "org.mozilla.firefox|4|1")
        assertFalse(controller.isLookupOpen())
        assertFalse(controller.continuesOpenLookup(pleco))
        pending.removeAt(0).invoke()
        assertEquals(emptyList(), lookupRows.map { it.text })
    }

    @Test
    fun editorKeyIgnoresTextFlags() {
        val info = EditorInfo()
        info.packageName = "org.mozilla.firefox"
        info.fieldId = 4
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val plain = lookupEditorKey(info)
        info.inputType = info.inputType or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        assertEquals(plain, lookupEditorKey(info))
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        assertNotEquals(plain, lookupEditorKey(info))
        info.packageName = "com.pleco.chinesesystem"
        info.inputType = InputType.TYPE_CLASS_TEXT
        assertNotEquals(plain, lookupEditorKey(info))
    }

    @Test
    fun aDrawingPickIsRememberedUnlessIncognito() {
        val file = java.io.File.createTempFile("ink-pick", ".txt")
        val memory = HandwritingMemory(file)
        val controller = controller(FakeRimeClient(), FakeEditor(), inkMemory = memory)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        controller.onOpenLookup()
        controller.onLookupStroke(floatArrayOf(0f, 0f, 40f, 0f))
        controller.onLookupPick(LookupRow("人", -1, -1))
        assertEquals(HandwritingMemory.BONUS, memory.bonus('人'.code))
        val quiet = HandwritingMemory(file)
        val hidden = controller(FakeRimeClient(), FakeEditor(), inkMemory = quiet)
        hidden.onStartInput(keepSession = false, forceAscii = false, incognitoNow = true)
        hidden.onOpenLookup()
        hidden.onLookupStroke(floatArrayOf(0f, 0f, 40f, 0f))
        hidden.onLookupPick(LookupRow("的", -1, -1))
        assertEquals(0, quiet.bonus('的'.code))
    }

    @Test
    fun englishAndPasswordHideTheLookup() {
        val locked = controller(FakeRimeClient(), FakeEditor())
        locked.onStartInput(keepSession = false, forceAscii = true, incognitoNow = false)
        assertFalse(locked.lookupAvailable())
        locked.onOpenLookup()
        locked.onOpenPad()
        assertFalse(locked.onEvent(' '.code, 0, false))
    }

    private fun controller(
        client: FakeRimeClient,
        editor: FakeEditor,
        script: PinyinScriptStore = MemoryScriptStore(),
        hanzi: HanziIndex = EmptyHanzi,
        starter: PinyinStarter = PinyinStarter { it() },
        deliver: (block: () -> Unit) -> Unit = { it() },
        later: (Long, () -> Unit) -> Unit = { _, block -> block() },
        inkMemory: HandwritingMemory = HandwritingMemory.NONE,
    ): PinyinInputController {
        return PinyinInputController(
            client = client,
            editor = editor,
            ui = object : PinyinUi {
                override fun showPreparing(message: String) { strip = message }
                override fun showFailure(message: String) { strip = message }
                override fun showCandidates(snapshot: RimeSnapshot) { strip = snapshot.preedit }
                override fun showMenu(menu: List<MenuChar>) { stripMenu = menu.map { it.text } }
                override fun showNeutral() {
                    strip = ""
                    stripMenu = emptyList()
                }
                override fun onModeChanged(label: String) {}
                override fun showLookup(model: LookupModel) {
                    lookupRows = model.rows
                    lookupPad = model.pad
                    lookupStrokes = model.strokes.size
                }
                override fun showInk(strokes: List<FloatArray>) {
                    lookupStrokes = strokes.size
                }
            },
            preparingText = "Preparing pinyin",
            failureText = "Pinyin failed to load",
            starter = starter,
            deliver = deliver,
            script = script,
            hanzi = hanzi,
            later = later,
            inkMemory = inkMemory,
        )
    }

    private fun personHanzi() = object : HanziIndex {
        override fun radical(codePoint: Int) = 0
        override fun strokeCount(codePoint: Int) = 0
        override fun rank(ink: IntArray, pool: IntArray, simplified: Boolean, radical: Int) =
            listOf('人'.code)
        override fun common() = intArrayOf('人'.code)
        override fun radicalLabel(radical: Int, simplified: Boolean) = ""
    }

    private fun latinInfo(word: String): SuggestedWordInfo {
        return SuggestedWordInfo(
            word, "", 0, SuggestedWordInfo.KIND_TYPED,
            Dictionary.DICTIONARY_USER_TYPED,
            SuggestedWordInfo.NOT_AN_INDEX,
            SuggestedWordInfo.NOT_A_CONFIDENCE,
        )
    }

    private fun openRow(
        pageNo: Int,
        lastPage: Boolean,
        onPick: (SuggestedWordInfo) -> Unit,
        onPage: (Boolean) -> Unit,
    ): ViewGroup {
        val menu = rimeSuggestedWords(RimeSnapshot(
            preedit = "ni",
            candidates = listOf(RimeCandidate("你", "ni"), RimeCandidate("尼", "ni")),
            pageNo = pageNo,
            isLastPage = lastPage,
        ))
        val parent = FrameLayout(RuntimeEnvironment.getApplication())
        RimeCandidateRow.bind(parent, menu, onPick, onPage)
        val width = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY)
        parent.measure(width, height)
        parent.layout(0, 0, 600, 120)
        return parent
    }

    private fun drag(scroll: View, fromX: Float, y: Float, toX: Float) {
        val down = 1_000L
        scroll.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, fromX, y, 0))
        val steps = 8
        for (step in 1..steps) {
            val x = fromX + (toX - fromX) * step / steps
            scroll.dispatchTouchEvent(MotionEvent.obtain(down, down + step * 16L, MotionEvent.ACTION_MOVE, x, y, 0))
        }
    }

    private fun lift(scroll: View, x: Float, y: Float) {
        scroll.dispatchTouchEvent(MotionEvent.obtain(1_000L, 1_200L, MotionEvent.ACTION_UP, x, y, 0))
    }
}

private class FakeEditor : PinyinEditor {
    var composing = ""
    var committed = ""
    var finished = false
    var finishedText = ""
    var wipedAfterFinish = false
    var onFinished: (() -> Unit)? = null
    var beforeOverride: String? = null

    override fun setComposingText(text: String) {
        if (finished && text.isEmpty()) wipedAfterFinish = true
        composing = text
    }

    override fun finishComposingText() {
        finished = true
        finishedText = composing
        committed += composing
        composing = ""
        onFinished?.invoke()
    }

    override fun commitText(text: String) {
        committed += text
        composing = ""
    }

    override fun textBeforeCursor(n: Int): String {
        beforeOverride?.let { return it.takeLast(n) }
        return (committed + composing).takeLast(n)
    }

    override fun contains(text: String): Boolean {
        if (text.isEmpty()) return false
        if (composing.contains(text) || committed.contains(text)) return true
        return beforeOverride?.contains(text) == true
    }

    override fun commitRaw(text: String) {
        committed += text
        composing = ""
    }

    override fun deleteBefore(n: Int) {
        if (n <= 0) return
        if (composing.length >= n) {
            composing = composing.dropLast(n)
            return
        }
        val rest = n - composing.length
        composing = ""
        committed = committed.dropLast(rest.coerceAtMost(committed.length))
    }
}

private class FakeRimeClient : RimeClient {
    override var failed: Boolean = false
    var snapshots = 0
    var pages: List<List<RimeCandidate>>? = null
    private var page = 0
    private var session = false
    private val options = HashMap<String, Boolean>()
    private var raw = ""
    private var commit = ""
    var shown: String? = null

    override fun hasSession() = session

    override fun ensure(incognito: Boolean, schemaId: String) {
        session = true
    }

    override fun deleteIncognitoData() {}

    override fun destroySession() {
        session = false
        raw = ""
    }

    override fun processKey(keysym: Int, mask: Int): Boolean {
        when (keysym) {
            RimeKeyMapper.XK_BACKSPACE -> if (raw.isNotEmpty()) raw = raw.dropLast(1)
            RimeKeyMapper.XK_SPACE -> if (candidates().isNotEmpty()) {
                commit = candidates()[0].text
                raw = ""
            }
            else -> if (keysym in 'a'.code..'z'.code) raw += keysym.toChar()
        }
        return true
    }

    override fun selectCandidate(index: Int): Boolean {
        val list = currentCandidates()
        if (index !in list.indices) return false
        commit = list[index].text
        raw = ""
        return true
    }

    override fun changePage(backward: Boolean): Boolean {
        val list = pages ?: return false
        val next = if (backward) page - 1 else page + 1
        if (next !in list.indices) return false
        page = next
        return true
    }
    override fun commitComposition(): Boolean = selectCandidate(0)
    override fun clearComposition() { raw = "" }
    override fun setOption(option: String, value: Boolean) {
        options[option] = value
        val scriptOn = options["zh_hans"] == true || options["zh_hant"] == true
            || options["zh_hant_hk"] == true || options["zh_hant_tw"] == true
        if (option.startsWith("zh_") && !scriptOn) options["ascii_mode"] = true
    }
    override fun getOption(option: String) = options[option] == true
    override fun shutdown() { session = false }

    override fun readSnapshot(): RimeSnapshot {
        snapshots += 1
        val text = commit
        commit = ""
        val list = currentCandidates()
        return RimeSnapshot(
            commit = text,
            preedit = shown ?: raw,
            cursor = (shown ?: raw).length,
            pageNo = if (pages != null) page else 0,
            isLastPage = pages == null || page == pages!!.lastIndex,
            asciiMode = getOption("ascii_mode"),
            composing = raw.isNotEmpty(),
            candidates = list,
        )
    }

    private fun currentCandidates(): List<RimeCandidate> {
        pages?.let { return it[page] }
        return candidates()
    }

    private fun candidates(): List<RimeCandidate> {
        if (raw == "nihao") return listOf(RimeCandidate("你好", "ni hao"))
        if (raw.isEmpty()) return emptyList()
        return listOf(RimeCandidate(raw, raw))
    }
}
