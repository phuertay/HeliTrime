package helium314.keyboard.latin

import android.os.Handler
import android.os.Looper
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.utils.AppsUtils
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            com.microsoft.office.onenote=IME|MS|25
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
        val oneNote = HardwareKeyAppOverride.find(overrides, "com.microsoft.office.onenote")
        assertEquals(HardwareKeyDeliveryMode.IME, oneNote!!.mode)
        assertEquals(HardwareKeyImePace.MS, oneNote.pace)
        assertEquals(25, oneNote.paceMs)
    }

    @Test
    fun fromPrefMapsOldModesToIme() {
        assertEquals(HardwareKeyDeliveryMode.PASS_THROUGH, HardwareKeyDeliveryMode.fromPref(null))
        assertEquals(HardwareKeyDeliveryMode.IME, HardwareKeyDeliveryMode.fromPref("IME"))
        assertEquals(HardwareKeyDeliveryMode.IME, HardwareKeyDeliveryMode.fromPref("COALESCE"))
        assertEquals(HardwareKeyDeliveryMode.PASS_THROUGH, HardwareKeyDeliveryMode.fromPref("PASS_THROUGH"))
    }

    @Test
    fun serializeRoundTripKeepsPace() {
        val list = listOf(
            HardwareKeyAppOverride("org.mozilla.firefox", HardwareKeyDeliveryMode.IME, HardwareKeyImePace.SETTLE, 24),
            HardwareKeyAppOverride("com.microsoft.office.onenote", HardwareKeyDeliveryMode.IME, HardwareKeyImePace.MS, 30),
            HardwareKeyAppOverride("com.microsoft.office.word", HardwareKeyDeliveryMode.PASS_THROUGH),
        )
        val text = HardwareKeyAppOverride.serialize(list)
        val parsed = HardwareKeyAppOverride.parseAll(text)
        assertEquals(list, parsed)
        assertNull(HardwareKeyAppOverride.find(text, "missing.app"))
    }

    @Test
    fun upsertAndRemove() {
        var text = HardwareKeyAppOverride.upsert(
            null,
            HardwareKeyAppOverride("a.b", HardwareKeyDeliveryMode.IME, HardwareKeyImePace.MS, 10)
        )
        text = HardwareKeyAppOverride.upsert(
            text,
            HardwareKeyAppOverride("a.b", HardwareKeyDeliveryMode.PASS_THROUGH)
        )
        assertEquals(HardwareKeyDeliveryMode.PASS_THROUGH, HardwareKeyAppOverride.find(text, "a.b")!!.mode)
        text = HardwareKeyAppOverride.remove(text, "a.b")
        assertTrue(text.isEmpty())
    }

    @Test
    fun fuzzyFilterMatchesNameAndPackageTokens() {
        val apps = listOf(
            Triple("org.mozilla.firefox", "Firefox", null),
            Triple("com.microsoft.office.onenote", "OneNote", null),
            Triple("com.microsoft.office.word", "Word", null),
        )
        assertEquals(listOf(apps[0]), AppsUtils.fuzzyFilterApps(apps, "fire"))
        assertEquals(listOf(apps[1]), AppsUtils.fuzzyFilterApps(apps, "one note"))
        assertEquals(listOf(apps[1], apps[2]), AppsUtils.fuzzyFilterApps(apps, "microsoft office"))
        assertEquals(apps, AppsUtils.fuzzyFilterApps(apps, "  "))
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

    @Test
    fun backspacesDoNotAbsorbEachOther() {
        val dispatched = mutableListOf<String>()
        val q = HardwareKeyPaceQueue(Handler(Looper.getMainLooper())) { e ->
            dispatched += if (e.keyCode == KeyCode.DELETE) "DEL" else e.codePoint.toChar().toString()
        }
        q.configure(HardwareKeyImePace.MS, 10)
        q.enqueue(letter('a'.code))
        // 'a' already out; three deletes must all reach the editor (steno correction).
        q.enqueue(delete())
        q.enqueue(delete())
        q.enqueue(delete())
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
        assertEquals(listOf("a", "DEL", "DEL", "DEL"), dispatched)
    }

    @Test
    fun flushDeliversPendingInOrderThenIdles() {
        val dispatched = mutableListOf<Int>()
        val q = HardwareKeyPaceQueue(Handler(Looper.getMainLooper())) { e ->
            dispatched += e.codePoint
        }
        q.configure(HardwareKeyImePace.MS, 50)
        q.enqueue(letter('a'.code))
        q.enqueue(letter('b'.code))
        q.enqueue(letter('c'.code))
        assertEquals(listOf('a'.code), dispatched)
        assertTrue(q.isActive())
        q.flush()
        assertEquals(listOf('a'.code, 'b'.code, 'c'.code), dispatched)
        assertFalse(q.isActive())
        // A delayed advance from before flush must not fire again.
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        assertEquals(listOf('a'.code, 'b'.code, 'c'.code), dispatched)
    }

    @Test
    fun flushOnSettleLeavesNothingQueued() {
        val dispatched = mutableListOf<Int>()
        val q = HardwareKeyPaceQueue(Handler(Looper.getMainLooper())) { e ->
            dispatched += e.codePoint
        }
        q.configure(HardwareKeyImePace.SETTLE, 15)
        q.enqueue(letter('x'.code))
        q.enqueue(letter('y'.code))
        q.flush()
        assertEquals(listOf('x'.code, 'y'.code), dispatched)
        assertFalse(q.isActive())
        q.onSelectionSettled()
        assertEquals(listOf('x'.code, 'y'.code), dispatched)
    }

}
