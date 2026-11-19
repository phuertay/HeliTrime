package helium314.keyboard.latin

import android.os.Handler
import helium314.keyboard.latin.common.StringUtils

enum class HardwareKeyDeliveryMode {
    IME,          // existing InputLogic path
    PASS_THROUGH, // app gets KeyEvents
    COALESCE,     // buffer until idle, then one commitText
    DOSE;         // pace one code point at a time

    companion object {
        @JvmStatic
        fun fromPref(value: String?): HardwareKeyDeliveryMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: IME
    }
}

/**
 * Queues hardware characters and flushes them to the editor.
 * Coalesce: idle timeout then one commit.
 * Dose: one code point every intervalMs.
 */
class HardwareKeyDelivery(
    private val handler: Handler,
    private val commitText: (String) -> Unit,
    private val deleteOne: () -> Unit,
    private val sendEnter: () -> Unit,
) {
    private val buffer = StringBuilder()
    private val flushRunnable = Runnable { flush() }
    private val doseRunnable = Runnable { doseOne() }
    @Volatile private var batchEdit = false
    private var beginBatch: (() -> Unit)? = null
    private var endBatch: (() -> Unit)? = null
    private var doseScheduled = false
    private var lastDoseMs = 15L

    fun setBatchEditHooks(begin: () -> Unit, end: () -> Unit) {
        beginBatch = begin
        endBatch = end
    }

    fun clear() {
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(doseRunnable)
        doseScheduled = false
        buffer.setLength(0)
    }

    /** Append a printing code point (or space). Schedules flush per mode. */
    fun onCodePoint(codePoint: Int, mode: HardwareKeyDeliveryMode, coalesceMs: Int, doseMs: Int, useBatchEdit: Boolean) {
        batchEdit = useBatchEdit
        when (mode) {
            HardwareKeyDeliveryMode.COALESCE -> {
                buffer.appendCodePoint(codePoint)
                handler.removeCallbacks(flushRunnable)
                handler.postDelayed(flushRunnable, coalesceMs.toLong().coerceAtLeast(0L))
            }
            HardwareKeyDeliveryMode.DOSE -> {
                lastDoseMs = doseMs.toLong().coerceAtLeast(0L)
                buffer.appendCodePoint(codePoint)
                if (!doseScheduled) {
                    doseScheduled = true
                    handler.postDelayed(doseRunnable, lastDoseMs)
                }
            }
            else -> {
                // caller should not use this for IME/pass-through
                commitNow(StringUtils.newSingleCodePointString(codePoint))
            }
        }
    }

    /** Backspace: drop last buffered code point if any, else deleteOne after flush. */
    fun onDelete(mode: HardwareKeyDeliveryMode, coalesceMs: Int, doseMs: Int, useBatchEdit: Boolean) {
        batchEdit = useBatchEdit
        if (buffer.isNotEmpty()) {
            val last = buffer.offsetByCodePoints(buffer.length, -1)
            buffer.setLength(last)
            when (mode) {
                HardwareKeyDeliveryMode.COALESCE -> {
                    handler.removeCallbacks(flushRunnable)
                    if (buffer.isNotEmpty()) handler.postDelayed(flushRunnable, coalesceMs.toLong().coerceAtLeast(0L))
                }
                HardwareKeyDeliveryMode.DOSE -> {
                    lastDoseMs = doseMs.toLong().coerceAtLeast(0L)
                    if (buffer.isNotEmpty() && !doseScheduled) {
                        doseScheduled = true
                        handler.postDelayed(doseRunnable, lastDoseMs)
                    }
                }
                else -> {}
            }
            return
        }
        // nothing buffered: flush timers and delete in the editor
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(doseRunnable)
        doseScheduled = false
        deleteOne()
    }

    /** Enter: flush buffer first, then sendEnter. */
    fun onEnter(useBatchEdit: Boolean) {
        batchEdit = useBatchEdit
        flush()
        sendEnter()
    }

    fun flush() {
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(doseRunnable)
        doseScheduled = false
        if (buffer.isEmpty()) return
        val text = buffer.toString()
        buffer.setLength(0)
        commitNow(text)
    }

    private fun doseOne() {
        doseScheduled = false
        if (buffer.isEmpty()) return
        val firstEnd = buffer.offsetByCodePoints(0, 1)
        val piece = buffer.substring(0, firstEnd)
        buffer.delete(0, firstEnd)
        commitNow(piece)
        if (buffer.isNotEmpty()) {
            doseScheduled = true
            handler.postDelayed(doseRunnable, lastDoseMs)
        }
    }

    private fun commitNow(text: String) {
        if (text.isEmpty()) return
        val begin = beginBatch
        val end = endBatch
        if (batchEdit && begin != null && end != null) {
            begin()
            try { commitText(text) } finally { end() }
        } else {
            commitText(text)
        }
    }
}
