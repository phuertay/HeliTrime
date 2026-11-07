package helium314.keyboard.latin.pinyin

interface PinyinEditor {
    fun setComposingText(text: String)
    fun finishComposingText()
    fun commitText(text: String)

    /** Live field text, not the keyboard's cache. Firefox can disagree with the cache. */
    fun textBeforeCursor(n: Int): String

    /** True when the spelling is still somewhere in the field. */
    fun contains(text: String): Boolean

    /** Inserts text with no composing span and no autospace. */
    fun commitRaw(text: String)

    fun deleteBefore(n: Int)
}

interface PinyinUi {
    fun showPreparing(message: String)
    fun showFailure(message: String)
    fun showCandidates(snapshot: RimeSnapshot)
    fun showNeutral()
    fun onModeChanged(label: String)
    fun showLookup(model: LookupModel) {}
    fun hideLookup() {}
}

fun interface PinyinStarter {
    fun start(block: () -> Unit)
}
