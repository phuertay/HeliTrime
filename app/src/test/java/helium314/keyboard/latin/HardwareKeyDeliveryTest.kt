package helium314.keyboard.latin

import android.os.Handler
import android.os.Looper
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HardwareKeyDeliveryTest {
    private fun delivery(
        committed: MutableList<String>,
        deleted: MutableList<Unit> = mutableListOf(),
        enters: MutableList<Unit> = mutableListOf(),
    ): HardwareKeyDelivery {
        val d = HardwareKeyDelivery(
            handler = Handler(Looper.getMainLooper()),
            commitText = { committed += it },
            deleteOne = { deleted += Unit },
            sendEnter = { enters += Unit },
        )
        return d
    }

    @Test
    fun coalesceFlushesAfterIdle() {
        val committed = mutableListOf<String>()
        val d = delivery(committed)
        d.onCodePoint('f'.code, HardwareKeyDeliveryMode.COALESCE, 40, 15, false)
        d.onCodePoint('o'.code, HardwareKeyDeliveryMode.COALESCE, 40, 15, false)
        d.onCodePoint('x'.code, HardwareKeyDeliveryMode.COALESCE, 40, 15, false)
        ShadowLooper.idleMainLooper(39, TimeUnit.MILLISECONDS)
        assertTrue(committed.isEmpty())
        ShadowLooper.idleMainLooper(2, TimeUnit.MILLISECONDS)
        assertEquals(listOf("fox"), committed)
    }

    @Test
    fun coalesceDeleteShrinksBuffer() {
        val committed = mutableListOf<String>()
        val deleted = mutableListOf<Unit>()
        val d = delivery(committed, deleted)
        d.onCodePoint('a'.code, HardwareKeyDeliveryMode.COALESCE, 40, 15, false)
        d.onCodePoint('b'.code, HardwareKeyDeliveryMode.COALESCE, 40, 15, false)
        d.onDelete(HardwareKeyDeliveryMode.COALESCE, 40, 15, false)
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        assertEquals(listOf("a"), committed)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun doseCommitsOneCodePointAtATime() {
        val committed = mutableListOf<String>()
        val d = delivery(committed)
        d.onCodePoint('a'.code, HardwareKeyDeliveryMode.DOSE, 40, 10, false)
        d.onCodePoint('b'.code, HardwareKeyDeliveryMode.DOSE, 40, 10, false)
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
        assertEquals(listOf("a"), committed)
        ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
        assertEquals(listOf("a", "b"), committed)
    }

    @Test
    fun enterFlushesThenSendsEnter() {
        val committed = mutableListOf<String>()
        val enters = mutableListOf<Unit>()
        val d = delivery(committed, enters = enters)
        d.onCodePoint('x'.code, HardwareKeyDeliveryMode.COALESCE, 100, 15, false)
        d.onEnter(false)
        assertEquals(listOf("x"), committed)
        assertEquals(1, enters.size)
    }

    @Test
    fun batchEditWrapsCommit() {
        val events = mutableListOf<String>()
        val d = HardwareKeyDelivery(
            handler = Handler(Looper.getMainLooper()),
            commitText = { events += "commit:$it" },
            deleteOne = {},
            sendEnter = {},
        )
        d.setBatchEditHooks(
            begin = { events += "begin" },
            end = { events += "end" },
        )
        d.onCodePoint('z'.code, HardwareKeyDeliveryMode.COALESCE, 10, 15, true)
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        assertEquals(listOf("begin", "commit:z", "end"), events)
    }

    @Test
    fun fromPrefDefaultsToIme() {
        assertEquals(HardwareKeyDeliveryMode.IME, HardwareKeyDeliveryMode.fromPref(null))
        assertEquals(HardwareKeyDeliveryMode.COALESCE, HardwareKeyDeliveryMode.fromPref("coalesce"))
        assertEquals(HardwareKeyDeliveryMode.DOSE, HardwareKeyDeliveryMode.fromPref("DOSE"))
        assertEquals(HardwareKeyDeliveryMode.PASS_THROUGH, HardwareKeyDeliveryMode.fromPref("PASS_THROUGH"))
    }
}
