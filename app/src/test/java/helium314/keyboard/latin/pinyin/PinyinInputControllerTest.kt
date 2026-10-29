package helium314.keyboard.latin.pinyin

import android.text.InputType
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

    @Test
    fun forcedAsciiIgnoresSpaceSwipe() {
        controller.onSubtype("luna_pinyin")
        controller.onStartInput("s", "u", "b", restarting = false, forceAscii = true)
        assertTrue(controller.asciiMode)
        assertTrue(controller.onVerticalSpaceSwipe(1))
        assertTrue(controller.asciiMode)
        controller.onEndSpaceSwipe()
        assertTrue(controller.onVerticalSpaceSwipe(1))
        assertTrue(controller.asciiMode)
    }

    @Test
    fun symbolsLayerEndsCompositionAndInsertsTheSymbol() {
        startChinese()
        controller.onEventCode('n'.code, shifted = false)
        assertTrue(controller.onEventCode('@'.code, shifted = false, symbols = true))
        assertEquals("", controller.lastSnapshot.preedit)
        assertEquals("@", editor.committed)
        assertFalse(client.keys.contains('@'.code))
    }

    @Test
    fun preparingQueuesKeysUntilTheSessionOpens() {
        controller.onSubtype("luna_pinyin")
        controller.beginPreparing()
        assertTrue(controller.isPreparing())
        assertTrue(controller.onEventCode('n'.code, shifted = false))
        assertEquals("", controller.lastSnapshot.preedit)
        controller.onStartInput("s", "u", "b", restarting = false, forceAscii = false)
        assertFalse(controller.isPreparing())
        assertEquals("n", controller.lastSnapshot.preedit)
    }

    @Test
    fun failedOpenCommitsQueuedLettersAndThenLetsKeysThrough() {
        controller.onSubtype("luna_pinyin")
        controller.beginPreparing()
        controller.onEventCode('n'.code, shifted = false)
        controller.onEventCode('i'.code, shifted = false)
        client.failStartup = true
        controller.onStartInput("s", "u", "b", restarting = false, forceAscii = false)
        assertTrue(controller.isFailed())
        assertEquals("ni", editor.committed)
        assertFalse(controller.onEventCode('h'.code, shifted = false))
    }

    @Test
    fun pageControlsMoveTheMenu() {
        startChinese()
        assertTrue(controller.onPage(1))
        assertEquals(1, controller.lastSnapshot.pageNo)
        assertTrue(controller.lastSnapshot.isLastPage)
        assertTrue(controller.onPage(-1))
        assertEquals(0, controller.lastSnapshot.pageNo)
        assertFalse(controller.lastSnapshot.isLastPage)
    }

    @Test
    fun leavingIncognitoReleasesTheCacheDir() {
        startChinese()
        val releasesAfterNormalStart = client.releases
        controller.onStartInput("s", "cache", "b", restarting = false, forceAscii = false, incognito = true)
        assertEquals(releasesAfterNormalStart, client.releases)
        assertEquals("cache", client.userDir)
        controller.onStartInput("s", "u", "b", restarting = false, forceAscii = false, incognito = false)
        assertEquals(releasesAfterNormalStart + 1, client.releases)
        assertEquals("u", client.userDir)
    }

    @Test
    fun webEmailAndPasswordForceAscii() {
        val webEmail = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val visible = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        assertTrue(PinyinFields.forceAscii(webEmail))
        assertTrue(PinyinFields.forceAscii(email))
        assertTrue(PinyinFields.forceAscii(uri))
        assertTrue(PinyinFields.forceAscii(password))
        assertTrue(PinyinFields.forceAscii(visible))
        assertFalse(PinyinFields.forceAscii(InputType.TYPE_CLASS_TEXT))
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
    private var page = 0
    var failStartup = false
    var userDir = ""
    var releases = 0
    val keys = ArrayList<Int>()
    override fun startup(sharedDir: String, userDir: String, prebuiltDir: String): Boolean {
        this.userDir = userDir
        return !failStartup
    }
    override fun releaseIncognitoDir() { releases += 1 }
    override fun createSession() = 1L
    override fun destroySession(session: Long) {}
    override fun selectSchema(session: Long, schemaId: String) = true
    override fun setOption(session: Long, option: String, value: Boolean) {}
    override fun processKey(session: Long, keycode: Int, mask: Int): Boolean {
        keys.add(keycode)
        when (keycode) {
            RimeKeyMapper.XK_BACKSPACE -> if (preedit.isNotEmpty()) preedit = preedit.dropLast(1)
            RimeKeyMapper.XK_SPACE -> selectCandidate(session, 0)
            RimeKeyMapper.XK_NEXT -> page += 1
            RimeKeyMapper.XK_PRIOR -> if (page > 0) page -= 1
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
        return RimeSnapshot(text, preedit, preedit.length, page, page > 0, 0, candidates)
    }
}
