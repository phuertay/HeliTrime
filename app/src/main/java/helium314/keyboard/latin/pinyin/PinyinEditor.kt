package helium314.keyboard.latin.pinyin

interface PinyinEditor {
    fun commit(text: String)
    fun setComposing(text: String)
    fun finishComposing()
}
