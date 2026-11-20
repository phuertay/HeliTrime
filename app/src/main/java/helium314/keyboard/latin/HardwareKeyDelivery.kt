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
         * Lines of `package.name=PASS_THROUGH` or `package.name=IME` (optional `|SETTLE` / `|MS|15`).
         * Unknown packages fall back to [defaultMode].
         */
        @JvmStatic
        fun forPackage(defaultMode: HardwareKeyDeliveryMode, overrides: String?, packageName: String?): HardwareKeyDeliveryMode {
            return HardwareKeyAppOverride.find(overrides, packageName)?.mode ?: defaultMode
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

/** One package's hardware-delivery override, including IME pace when mode is [HardwareKeyDeliveryMode.IME]. */
data class HardwareKeyAppOverride(
    val packageName: String,
    val mode: HardwareKeyDeliveryMode,
    val pace: HardwareKeyImePace = HardwareKeyImePace.SETTLE,
    val paceMs: Int = 15,
) {
    fun toPrefLine(): String = when {
        mode == HardwareKeyDeliveryMode.PASS_THROUGH -> "$packageName=PASS_THROUGH"
        pace == HardwareKeyImePace.MS -> "$packageName=IME|MS|$paceMs"
        else -> "$packageName=IME|SETTLE"
    }

    companion object {
        @JvmStatic
        fun parseLine(raw: String): HardwareKeyAppOverride? {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return null
            val eq = line.indexOf('=')
            if (eq <= 0) return null
            val pkg = line.substring(0, eq).trim()
            if (pkg.isEmpty()) return null
            val rest = line.substring(eq + 1).trim()
            val parts = rest.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val mode = HardwareKeyDeliveryMode.fromPref(parts[0])
            if (mode == HardwareKeyDeliveryMode.PASS_THROUGH) {
                return HardwareKeyAppOverride(pkg, mode)
            }
            val pace = if (parts.size >= 2) HardwareKeyImePace.fromPref(parts[1]) else HardwareKeyImePace.SETTLE
            val ms = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 100)
                ?: 15
            return HardwareKeyAppOverride(pkg, mode, pace, ms)
        }

        @JvmStatic
        fun parseAll(overrides: String?): List<HardwareKeyAppOverride> {
            if (overrides.isNullOrBlank()) return emptyList()
            val seen = linkedMapOf<String, HardwareKeyAppOverride>()
            for (raw in overrides.lineSequence()) {
                val item = parseLine(raw) ?: continue
                seen[item.packageName.lowercase()] = item
            }
            return seen.values.toList()
        }

        @JvmStatic
        fun serialize(overrides: List<HardwareKeyAppOverride>): String =
            overrides.joinToString("\n") { it.toPrefLine() }

        @JvmStatic
        fun find(overrides: String?, packageName: String?): HardwareKeyAppOverride? {
            if (packageName.isNullOrEmpty() || overrides.isNullOrBlank()) return null
            return parseAll(overrides).firstOrNull { it.packageName.equals(packageName, ignoreCase = true) }
        }

        fun upsert(overrides: String?, item: HardwareKeyAppOverride): String {
            val map = parseAll(overrides).associateByTo(linkedMapOf()) { it.packageName.lowercase() }
            map[item.packageName.lowercase()] = item
            return serialize(map.values.toList())
        }

        fun remove(overrides: String?, packageName: String): String {
            val kept = parseAll(overrides).filterNot { it.packageName.equals(packageName, ignoreCase = true) }
            return serialize(kept)
        }
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
