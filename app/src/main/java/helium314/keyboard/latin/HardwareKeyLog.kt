// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/** Records cabled-keyboard keys so a phone session can be saved from About. */
object HardwareKeyLog {
    private const val TAG = "HardwareKey"
    private const val MAX_BYTES = 1_500_000
    private val io = Executors.newSingleThreadExecutor()
    private val lock = ReentrantLock()
    private var file: File? = null
    private var lastEventTime = 0L

    @JvmStatic
    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "hardware-key.log")
        line(
            "build ${BuildConfig.VERSION_NAME} ${Build.BRAND} ${Build.MODEL} ${Build.DEVICE} sdk=${Build.VERSION.SDK_INT}"
        )
    }

    @JvmStatic
    fun key(
        action: String,
        event: KeyEvent,
        editorPackage: String?,
        firefox: Boolean,
        hardwareEnabled: Boolean,
        consumed: Boolean,
    ) {
        if (event.deviceId < 0) return
        val gap = if (lastEventTime == 0L) -1L else event.eventTime - lastEventTime
        lastEventTime = event.eventTime
        val device = try {
            InputDevice.getDevice(event.deviceId)
        } catch (_: Throwable) {
            null
        }
        val name = device?.name?.replace('\n', ' ') ?: "?"
        line(
            "$action key=${KeyEvent.keyCodeToString(event.keyCode)}(${event.keyCode}) " +
                "scan=${event.scanCode} meta=0x${event.metaState.toString(16)} u=${event.unicodeChar} " +
                "rep=${event.repeatCount} gap=$gap dev=${event.deviceId} name=\"$name\" " +
                "vid=${device?.vendorId ?: -1} pid=${device?.productId ?: -1} " +
                "src=0x${event.source.toString(16)} flags=0x${event.flags.toString(16)} " +
                "pkg=$editorPackage ff=${if (firefox) 1 else 0} hw=${if (hardwareEnabled) 1 else 0} " +
                "consumed=${if (consumed) 1 else 0}"
        )
    }

    @JvmStatic
    fun input(which: String, info: EditorInfo?, restarting: Boolean) {
        if (info == null) {
            line("$which restarting=${if (restarting) 1 else 0} pkg=?")
            return
        }
        line(
            "$which restarting=${if (restarting) 1 else 0} pkg=${info.packageName} " +
                "type=0x${info.inputType.toString(16)} ime=0x${info.imeOptions.toString(16)} " +
                "field=${info.fieldId} sel=${info.initialSelStart},${info.initialSelEnd}"
        )
    }

    @JvmStatic
    fun selection(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        spanStart: Int,
        spanEnd: Int,
    ) {
        line("sel $oldStart,$oldEnd -> $newStart,$newEnd span=$spanStart,$spanEnd")
    }

    /** Both the current file and the rotated one, for About → Save log. */
    @JvmStatic
    fun read(context: Context): String {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        lock.lock()
        try {
            return listOf("hardware-key.log.old", "hardware-key.log").mapNotNull { name ->
                val log = File(dir, name)
                if (!log.isFile) null else try {
                    log.readText()
                } catch (_: Exception) {
                    null
                }
            }.joinToString("\n")
        } finally {
            lock.unlock()
        }
    }

    private fun line(text: String) {
        Log.i(TAG, text)
        val target = file ?: return
        io.execute {
            lock.lock()
            try {
                if (target.exists() && target.length() > MAX_BYTES) {
                    val old = File(target.parentFile, "hardware-key.log.old")
                    if (old.exists()) old.delete()
                    target.renameTo(old)
                }
                target.appendText(text + "\n")
            } catch (_: Exception) {
            } finally {
                lock.unlock()
            }
        }
    }
}
