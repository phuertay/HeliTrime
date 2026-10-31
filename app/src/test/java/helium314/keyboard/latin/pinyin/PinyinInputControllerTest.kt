package helium314.keyboard.latin.pinyin

import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class PinyinInputControllerTest {
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

        controller.onUpdateSelection(belated = false)
        assertEquals("", editor.composing)
        assertFalse(controller.onEvent(0, KeyCode.DELETE, false))

        assertFalse(controller.onEvent(-1, KeyCode.EMOJI, false))
    }

    @Test
    fun downwardSpaceSwipeTogglesOncePerGesture() {
        val client = FakeRimeClient()
        val editor = FakeEditor()
        val controller = controller(client, editor)
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        controller.onEvent('n'.code, 0, false)
        controller.onEvent('i'.code, 0, false)
        controller.onVerticalSpaceSwipe(1)
        assertTrue(editor.finished)
        assertEquals("ni", editor.finishedText)
        assertTrue(client.getOption("ascii_mode"))
        controller.onVerticalSpaceSwipe(2)
        assertTrue(client.getOption("ascii_mode"))
        controller.onVerticalSpaceSwipe(-1)
        assertTrue(client.getOption("ascii_mode"))
        controller.onEndSpaceSwipe()
        controller.onVerticalSpaceSwipe(-3)
        assertTrue(client.getOption("ascii_mode"))
        controller.onEndSpaceSwipe()
        controller.onVerticalSpaceSwipe(1)
        assertFalse(client.getOption("ascii_mode"))
    }

    @Test
    fun upwardStepDoesNotToggle() {
        val client = FakeRimeClient()
        val controller = controller(client, FakeEditor())
        controller.onStartInput(keepSession = false, forceAscii = false, incognitoNow = false)
        controller.onVerticalSpaceSwipe(-1)
        assertFalse(client.getOption("ascii_mode"))
    }

    @Test
    fun lockedAsciiIgnoresDownwardSwipe() {
        val client = FakeRimeClient()
        val controller = controller(client, FakeEditor())
        controller.onStartInput(keepSession = false, forceAscii = true, incognitoNow = false)
        assertTrue(client.getOption("ascii_mode"))
        controller.onVerticalSpaceSwipe(1)
        controller.onEndSpaceSwipe()
        controller.onVerticalSpaceSwipe(1)
        assertTrue(client.getOption("ascii_mode"))
        assertFalse(controller.onEvent('a'.code, 0, false))
    }

    @Test
    fun latinStripIsNotARimeMenuAndATapSelectsThePinyinIndex() {
        val latin = SuggestedWords(
            ArrayList(listOf(latinInfo("the"), latinInfo("then"), latinInfo("they"))),
            null, null, true, false, false,
            SuggestedWords.INPUT_STYLE_TYPING, SuggestedWords.NOT_A_SEQUENCE_NUMBER,
        )
        assertFalse(RimeCandidateRow.isRime(latin))
        assertEquals(3, latin.size())

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
    }

    private fun controller(client: FakeRimeClient, editor: FakeEditor): PinyinInputController {
        return PinyinInputController(
            client = client,
            editor = editor,
            ui = object : PinyinUi {
                override fun showPreparing(message: String) {}
                override fun showFailure(message: String) {}
                override fun showCandidates(snapshot: RimeSnapshot) {}
                override fun showNeutral() {}
                override fun onModeChanged(label: String) {}
            },
            preparingText = "Preparing pinyin",
            failureText = "Pinyin failed to load",
            starter = PinyinStarter { it() },
            deliver = { it() },
        )
    }

    private fun latinInfo(word: String): SuggestedWordInfo {
        return SuggestedWordInfo(
            word, "", 0, SuggestedWordInfo.KIND_TYPED,
            Dictionary.DICTIONARY_USER_TYPED,
            SuggestedWordInfo.NOT_AN_INDEX,
            SuggestedWordInfo.NOT_A_CONFIDENCE,
        )
    }
}

private class FakeEditor : PinyinEditor {
    var composing = ""
    var committed = ""
    var finished = false
    var finishedText = ""

    override fun setComposingText(text: String) {
        composing = text
    }

    override fun finishComposingText() {
        finished = true
        finishedText = composing
        composing = ""
    }

    override fun commitText(text: String) {
        committed += text
        composing = ""
    }
}

private class FakeRimeClient : RimeClient {
    override var failed: Boolean = false
    private var session = false
    private val options = HashMap<String, Boolean>()
    private var raw = ""
    private var commit = ""

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
        val list = candidates()
        if (index !in list.indices) return false
        commit = list[index].text
        raw = ""
        return true
    }

    override fun changePage(backward: Boolean) = false
    override fun commitComposition(): Boolean = selectCandidate(0)
    override fun clearComposition() { raw = "" }
    override fun setOption(option: String, value: Boolean) { options[option] = value }
    override fun getOption(option: String) = options[option] == true
    override fun shutdown() { session = false }

    override fun readSnapshot(): RimeSnapshot {
        val text = commit
        commit = ""
        return RimeSnapshot(
            commit = text,
            preedit = raw,
            cursor = raw.length,
            isLastPage = true,
            asciiMode = getOption("ascii_mode"),
            composing = raw.isNotEmpty(),
            candidates = candidates(),
        )
    }

    private fun candidates(): List<RimeCandidate> {
        if (raw == "nihao") return listOf(RimeCandidate("你好", "ni hao"))
        if (raw.isEmpty()) return emptyList()
        return listOf(RimeCandidate(raw, raw))
    }
}
