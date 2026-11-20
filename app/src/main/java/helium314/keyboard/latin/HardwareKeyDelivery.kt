package helium314.keyboard.latin

import android.os.Handler
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import java.util.ArrayDeque

enum class HardwareKeyDeliveryMode {
    PASS_THROUGH,
    IME;

    companion object {
        @JvmStatic
        fun fromPref(value: String?): HardwareKeyDeliveryMode = when (value?.uppercase()) {
            "IME", "COALESCE", "DOSE" -> IME // old prefs
            else -> PASS_THROUGH
        }

        /**
         * Lines of `package.name=PASS_THROUGH` or `package.name=IME`.
         * Unknown packages fall back to [defaultMode].
         */
        @JvmStatic
        fun forPackage(defaultMode: HardwareKeyDeliveryMode, overrides: String?, packageName: String?): HardwareKeyDeliveryMode {
            if (packageName.isNullOrEmpty() || overrides.isNullOrBlank()) return defaultMode
            for (raw in overrides.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val pkg = line.substring(0, eq).trim()
                if (!pkg.equals(packageName, ignoreCase = true)) continue
                return fromPref(line.substring(eq + 1).trim())
            }
            return defaultMode
        }
    }
}

enum class HardwareKeyImePace {
    SETTLE,
    MS;

    companion object {
        @JvmStatic
        fun fromPref(value: String?): HardwareKeyImePace =
            if (value.equals("MS", ignoreCase = true)) MS else SETTLE
    }
}

/**
 * Feeds hardware [Event]s into the IME one at a time.
 * Backspaces that still sit in the queue shrink the queue instead of deleting in the app.
 */
class HardwareKeyPaceQueue(
    private val handler: Handler,
    private val dispatch: (Event) -> Unit,
) {
    private val queue = ArrayDeque<Event>()
    private var waiting = false
    private var pace = HardwareKeyImePace.SETTLE
    private var intervalMs = 15L
    private val advanceRunnable = Runnable { advanceAfterWait() }

    fun configure(pace: HardwareKeyImePace, intervalMs: Int) {
        this.pace = pace
        this.intervalMs = intervalMs.toLong().coerceAtLeast(0L)
    }

    fun clear() {
        handler.removeCallbacks(advanceRunnable)
        queue.clear()
        waiting = false
    }

    fun isActive(): Boolean = waiting || queue.isNotEmpty()

    fun enqueue(event: Event) {
        if (isDelete(event) && absorbDelete()) return
        queue.addLast(event)
        if (!waiting) releaseNext()
    }

    /** Selection echo from the last dispatched edit. SETTLE mode releases the next event. */
    fun onSelectionSettled() {
        if (pace != HardwareKeyImePace.SETTLE || !waiting) return
        handler.removeCallbacks(advanceRunnable)
        waiting = false
        releaseNext()
    }

    private fun absorbDelete(): Boolean {
        val last = queue.lastOrNull() ?: return false
        if (!isAbsorbable(last)) return false
        queue.removeLast()
        return true
    }

    private fun releaseNext() {
        val event = queue.pollFirst()
        if (event == null) {
            waiting = false
            return
        }
        waiting = true
        when (pace) {
            HardwareKeyImePace.MS -> {
                dispatch(event)
                // Always wait the interval so a following enqueue is paced, then
                // advanceAfterWait clears waiting when the queue is empty.
                handler.postDelayed(advanceRunnable, intervalMs)
            }
            HardwareKeyImePace.SETTLE -> {
                dispatch(event)
                // Fallback if the editor never echoes selection.
                val fallback = intervalMs.coerceAtLeast(40L)
                handler.postDelayed(advanceRunnable, fallback)
            }
        }
    }

    private fun advanceAfterWait() {
        waiting = false
        if (queue.isEmpty()) return
        releaseNext()
    }

    private fun isDelete(event: Event) = event.keyCode == KeyCode.DELETE

    private fun isAbsorbable(event: Event): Boolean =
        event.keyCode == KeyCode.DELETE || event.codePoint > 0
}
