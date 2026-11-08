package helium314.keyboard.latin.pinyin

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants

/** Key codes and hardware events become librime keysyms. Shift is not a mask in 中文. */
object RimeKeyMapper {
    const val XK_SPACE = 0x20
    const val XK_BACKSPACE = 0xff08
    const val XK_RETURN = 0xff0d

    fun lowercaseKeysym(codePoint: Int): Int {
        return if (codePoint in 'A'.code..'Z'.code) codePoint + 32 else codePoint
    }

    fun isDelete(keyCode: Int): Boolean = keyCode == KeyCode.DELETE

    fun isEnter(codePoint: Int): Boolean = codePoint == Constants.CODE_ENTER

    fun isSpace(codePoint: Int): Boolean = codePoint == Constants.CODE_SPACE
}
