package helium314.keyboard.latin.pinyin

interface PinyinEditor {
    fun setComposingText(text: String)
    fun finishComposingText()
    fun commitText(text: String)
}

interface PinyinUi {
    fun showPreparing(message: String)
    fun showFailure(message: String)
    fun showCandidates(snapshot: RimeSnapshot)
    fun showNeutral()
    fun onModeChanged(label: String)
}

fun interface PinyinStarter {
    fun start(block: () -> Unit)
}
