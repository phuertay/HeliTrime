package helium314.keyboard.latin

import android.os.Handler
import android.os.Looper
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HardwareKeyDeliveryTest {
    private fun letter(code: Int) =
        Event.createHardwareKeypressEvent(code, code, 0, null, false)

    private fun delete() =
        Event.createHardwareKeypressEvent(Event.NOT_A_CODE_POINT, KeyCode.DELETE, 0, null, false)

    @Test
    fun forPackageOverridesDefault() {
        val overrides = """
            org.mozilla.firefox=IME
            com.microsoft.office.word=PASS_THROUGH
            # comment
            com.microsoft.office.onenote=IME
        """.trimIndent()
        assertEquals(
            HardwareKeyDeliveryMode.IME,
            HardwareKeyDeliveryMode.forPackage(HardwareKeyDeliveryMode.PASS_THROUGH, overrides, "org.mozilla.firefox")
        )
        assertEquals(
            HardwareKeyDeliveryMode.PASS_THROUGH,
            HardwareKeyDeliveryMode.forPackage(HardwareKeyDeliveryMode.IME, overrides, "com.microsoft.office.word")
        )
        assertEquals(
            HardwareKeyDeliveryMode.PASS_THROUGH,
            HardwareKeyDeliveryMode.forPackage(HardwareKeyDeliveryMode.PASS_THROUGH, overrides, "com.other.app")
        )
    }

    @Test
    fun fromPrefMapsOldModesToIme() {
        assertEquals(HardwareKeyDeliveryMode.PASS_THROUGH, HardwareKeyDeliveryMode.fromPref(null))
        assertEquals(HardwareKeyDeliveryMode.IME, HardwareKeyDeliveryMode.fromPref("IME"))
        assertEquals(HardwareKeyDeliveryMode.IME, HardwareKeyDeliveryMode.fromPref("COALESCE"))
        assertEquals(HardwareKeyDeliveryMode.PASS_THROUGH, HardwareKeyDeliveryMode.fromPref("PASS_THROUGH"))
    }

    @Test
    fun msPaceDispatchesOneEventAtATime() {
        val dispatched = mutableListOf<Int>()
        val q = HardwareKeyPaceQueue(Handler(Looper.getMainLooper())) { e ->
            dispatched += e.codePoint
        }
        q.configure(HardwareKeyImePace.MS, 10)
        q.enqueue(letter('a'.code))
        q.enqueue(letter('b'.code))
        assertEquals(listOf('a'.code), dispatched)
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
        assertEquals(listOf('a'.code, 'b'.code), dispatched)
        // Trailing MS interval after the last dispatch must drain before idle.
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
        assertFalse(q.isActive())
    }

    @Test
    fun settleWaitsForSelectionEcho() {
        val dispatched = mutableListOf<Int>()
        val q = HardwareKeyPaceQueue(Handler(Looper.getMainLooper())) { e ->
            dispatched += e.codePoint
        }
        q.configure(HardwareKeyImePace.SETTLE, 15)
        q.enqueue(letter('x'.code))
        q.enqueue(letter('y'.code))
        assertEquals(listOf('x'.code), dispatched)
        q.onSelectionSettled()
        assertEquals(listOf('x'.code, 'y'.code), dispatched)
    }

    @Test
    fun backspaceAbsorbsQueuedLetter() {
        val dispatched = mutableListOf<String>()
        val q = HardwareKeyPaceQueue(Handler(Looper.getMainLooper())) { e ->
            dispatched += if (e.keyCode == KeyCode.DELETE) "DEL" else e.codePoint.toChar().toString()
        }
        q.configure(HardwareKeyImePace.MS, 50)
        q.enqueue(letter('a'.code))
        // 'a' is already dispatched (waiting). Queue 'b', then delete absorbs 'b'.
        q.enqueue(letter('b'.code))
        q.enqueue(delete())
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        assertEquals(listOf("a"), dispatched)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        assertFalse(q.isActive())
    }

    @Test
    fun backspaceEnqueuedWhenQueueEmptyAfterDispatch() {
        val dispatched = mutableListOf<String>()
        val q = HardwareKeyPaceQueue(Handler(Looper.getMainLooper())) { e ->
            dispatched += if (e.keyCode == KeyCode.DELETE) "DEL" else e.codePoint.toChar().toString()
        }
        q.configure(HardwareKeyImePace.MS, 10)
        q.enqueue(letter('a'.code))
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
        q.enqueue(delete())
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
        assertEquals(listOf("a", "DEL"), dispatched)
    }

}
