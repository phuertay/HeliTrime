package helium314.keyboard.latin.pinyin

import helium314.keyboard.latin.utils.InputTypeUtils

/** Field types that must stay in ascii_mode for the whole session. */
object PinyinFields {
    @JvmStatic
    fun forceAscii(inputType: Int): Boolean {
        return InputTypeUtils.isAnyPasswordInputType(inputType)
                || InputTypeUtils.isUriOrEmailType(inputType)
    }
}
