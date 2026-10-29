package helium314.keyboard.latin.pinyin

import android.view.KeyEvent

/** Latin key codes and hardware events to librime keysyms. */
object RimeKeyMapper {
    const val XK_SPACE = 0x20
    const val XK_BACKSPACE = 0xff08
    const val XK_RETURN = 0xff0d

    fun letterKeysym(codePoint: Int): Int {
        return if (codePoint in 'A'.code..'Z'.code) codePoint + 32 else codePoint
    }

    fun hardwareKeysym(event: KeyEvent): Int? {
        val unicode = event.unicodeChar
        if (unicode in 'A'.code..'Z'.code || unicode in 'a'.code..'z'.code) {
            return letterKeysym(unicode)
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_SPACE -> XK_SPACE
            KeyEvent.KEYCODE_DEL -> XK_BACKSPACE
            KeyEvent.KEYCODE_ENTER -> XK_RETURN
            else -> if (unicode > 0) unicode else null
        }
    }
}
