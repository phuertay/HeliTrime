package helium314.keyboard.latin.pinyin

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinInputControllerTest {
    private val editor = RecordingEditor()
    private val client = FakeRimeClient()
    private val controller = PinyinInputController(client, editor)

    @Test
    fun englishSubtypeCreatesNoSession() {
        controller.onSubtype(null)
        controller.onStartInput("s", "u", "b", restarting = false, forceAscii = false)
        assertEquals(0, controller.sessionsCreated())
    }

    @Test
    fun nihaoSetsPreeditAndSelectCommits() {
        startChinese()
        for (key in "nihao") controller.onEventCode(key.code, shifted = false)
        assertEquals("nihao", controller.lastSnapshot.preedit)
        assertEquals("你好", controller.lastSnapshot.candidates[0].text)
        controller.onSelectCandidate(0)
        assertEquals("", controller.lastSnapshot.preedit)
        assertEquals("你好", editor.committed)
    }

    @Test
    fun backspaceShrinksPreedit() {
        startChinese()
        controller.onEventCode('n'.code, false)
        controller.onEventCode('i'.code, false)
        controller.onEventCode(KeyCode.DELETE, false)
        assertEquals("n", controller.lastSnapshot.preedit)
    }

    @Test
    fun cursorJumpClearsPreedit() {
        startChinese()
        controller.onEventCode('n'.code, false)
        controller.onCursorMoved(belated = false)
        assertEquals("", controller.lastSnapshot.preedit)
        assertTrue(editor.finished)
    }

    @Test
    fun belatedCursorMoveDoesNotClear() {
        startChinese()
        controller.onEventCode('n'.code, false)
        editor.finished = false
        controller.onCursorMoved(belated = true)
        assertEquals("n", controller.lastSnapshot.preedit)
    }

    @Test
    fun unconsumedShiftReturnsToCaller() {
        startChinese()
        assertFalse(controller.onEventCode(KeyCode.SHIFT, false))
    }

    @Test
    fun downSwipeFinishesLettersAndSetsAsciiOnce() {
        startChinese()
        controller.onEventCode('n'.code, false)
        assertTrue(controller.onVerticalSpaceSwipe(1))
        assertTrue(controller.asciiMode)
        assertEquals("", controller.lastSnapshot.preedit)
        assertTrue(editor.finished)
        assertTrue(controller.onVerticalSpaceSwipe(2))
        assertTrue(controller.asciiMode)
        controller.onEndSpaceSwipe()
        assertTrue(controller.onVerticalSpaceSwipe(1))
        assertFalse(controller.asciiMode)
    }

    @Test
    fun upwardStepDoesNotFlip() {
        startChinese()
        assertFalse(controller.onVerticalSpaceSwipe(-1))
        assertFalse(controller.asciiMode)
    }

    private fun startChinese() {
        controller.onSubtype("luna_pinyin")
        controller.onStartInput("s", "u", "b", restarting = false, forceAscii = false)
    }
}

private class RecordingEditor : PinyinEditor {
    var committed = ""
    var composingText = ""
    var finished = false
    override fun commit(text: String) { committed += text }
    override fun setComposing(text: String) { composingText = text; finished = false }
    override fun finishComposing() { composingText = ""; finished = true }
}

private class FakeRimeClient : RimeClient {
    private var preedit = ""
    private var commit = ""
    override fun startup(sharedDir: String, userDir: String, prebuiltDir: String) = true
    override fun createSession() = 1L
    override fun destroySession(session: Long) {}
    override fun selectSchema(session: Long, schemaId: String) = true
    override fun setOption(session: Long, option: String, value: Boolean) {}
    override fun processKey(session: Long, keycode: Int, mask: Int): Boolean {
        when (keycode) {
            RimeKeyMapper.XK_BACKSPACE -> if (preedit.isNotEmpty()) preedit = preedit.dropLast(1)
            RimeKeyMapper.XK_SPACE -> selectCandidate(session, 0)
            else -> if (keycode in 'a'.code..'z'.code) preedit += keycode.toChar()
        }
        return true
    }
    override fun selectCandidate(session: Long, index: Int): Boolean {
        if (preedit == "nihao" && index == 0) commit = "你好"
        preedit = ""
        return true
    }
    override fun commitComposition(session: Long) = selectCandidate(session, 0)
    override fun clearComposition(session: Long) { preedit = "" }
    override fun readSnapshot(session: Long): RimeSnapshot {
        val candidates = if (preedit == "nihao") listOf(RimeCandidate("你好", "ni hao")) else emptyList()
        val text = commit
        commit = ""
        return RimeSnapshot(text, preedit, preedit.length, 0, true, 0, candidates)
    }
}
