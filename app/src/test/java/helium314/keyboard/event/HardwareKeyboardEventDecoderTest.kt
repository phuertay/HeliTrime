package helium314.keyboard.event

import android.view.KeyEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class HardwareKeyboardEventDecoderTest {
    private val decoder = HardwareKeyboardEventDecoder(1)

    @Test
    fun bareBackspaceIsDelete() {
        val event = decoder.decodeHardwareKey(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
        )
        assertEquals(KeyCode.DELETE, event.keyCode)
    }

    @Test
    fun ctrlBackspaceKeepsDeleteAndMeta() {
        // Delivery layer passes Ctrl/Alt/Meta chords to the editor; the decoder still
        // reports DELETE with meta so callers can see the chord without remapping.
        val downTime = 0L
        val keyEvent = KeyEvent(
            downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL,
            0, KeyEvent.META_CTRL_ON
        )
        val event = decoder.decodeHardwareKey(keyEvent)
        assertEquals(KeyCode.DELETE, event.keyCode)
        assertEquals(KeyEvent.META_CTRL_ON, event.metaState and KeyEvent.META_CTRL_ON)
    }
}
