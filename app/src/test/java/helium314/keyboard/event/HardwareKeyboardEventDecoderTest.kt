package helium314.keyboard.event

import android.view.KeyEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HardwareKeyboardEventDecoderTest {
    @Test
    fun shiftDoesNotStayOnTheKey() {
        assertEquals(0, HardwareKeyboardEventDecoder.withoutStickyShift(KeyEvent.META_SHIFT_ON))
        assertEquals(0, HardwareKeyboardEventDecoder.withoutStickyShift(
            KeyEvent.META_SHIFT_ON or KeyEvent.META_CAPS_LOCK_ON))
        val ctrlShift = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        assertEquals(ctrlShift, HardwareKeyboardEventDecoder.withoutStickyShift(ctrlShift))
    }

    @Test
    fun shiftBackspaceIsAPlainDelete() {
        val event = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0, KeyEvent.META_SHIFT_ON)
        val decoded = HardwareKeyboardEventDecoder(1).decodeHardwareKey(event)
        assertEquals(KeyCode.DELETE, decoded.keyCode)
        assertEquals(0, decoded.metaState)
        assertTrue(decoded.isHandled)
        assertFalse(decoded.isFunctionalKeyEvent && decoded.metaState != 0)
    }
}
