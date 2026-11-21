package helium314.keyboard.event

import android.view.KeyEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EventFunctionalKeyTest {
    @Test
    fun shiftAloneDoesNotMakePrintableFunctional() {
        val question = Event.createHardwareKeypressEvent(
            '?'.code, KeyEvent.KEYCODE_SLASH, KeyEvent.META_SHIFT_ON, null, false
        )
        assertFalse(question.isFunctionalKeyEvent)

        val capitalI = Event.createHardwareKeypressEvent(
            'I'.code, KeyEvent.KEYCODE_I, KeyEvent.META_SHIFT_ON, null, false
        )
        assertFalse(capitalI.isFunctionalKeyEvent)
    }

    @Test
    fun ctrlStillMakesEventFunctional() {
        val ctrlDel = Event.createHardwareKeypressEvent(
            Event.NOT_A_CODE_POINT, KeyCode.DELETE, KeyEvent.META_CTRL_ON, null, false
        )
        assertTrue(ctrlDel.isFunctionalKeyEvent)

        val bareDel = Event.createHardwareKeypressEvent(
            Event.NOT_A_CODE_POINT, KeyCode.DELETE, 0, null, false
        )
        assertTrue(bareDel.isFunctionalKeyEvent)
    }
}
