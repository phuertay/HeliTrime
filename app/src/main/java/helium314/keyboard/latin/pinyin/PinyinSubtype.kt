package helium314.keyboard.latin.pinyin

import helium314.keyboard.latin.common.Constants

fun isRimeSubtype(extraValue: String?): Boolean {
    return extraValue != null && extraValue.contains(Constants.Subtype.ExtraValue.RIME_SCHEMA)
}
