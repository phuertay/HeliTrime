package helium314.keyboard.latin.pinyin

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.prefs
import kotlin.concurrent.thread

fun createPinyinController(ime: LatinIME): PinyinInputController {
    val editor = object : PinyinEditor {
        override fun setComposingText(text: String) {
            ime.pinyinConnection().setComposingText(text, 1)
        }
        override fun finishComposingText() {
            ime.pinyinConnection().finishComposingText()
        }
        override fun commitText(text: String) {
            ime.pinyinConnection().commitText(text, 1)
        }
    }
    val ui = object : PinyinUi {
        override fun showPreparing(message: String) {
            ime.setSuggestions(rimeSuggestedWords(message))
        }
        override fun showFailure(message: String) {
            ime.setSuggestions(rimeSuggestedWords(message))
        }
        override fun showCandidates(snapshot: RimeSnapshot) {
            ime.setSuggestions(rimeSuggestedWords(snapshot))
        }
        override fun showNeutral() {
            ime.setNeutralSuggestionStrip()
        }
        override fun onModeChanged(label: String) {
            ime.pinyinInvalidateKeyboard()
        }
    }
    val main = Handler(Looper.getMainLooper())
    return PinyinInputController(
        client = RimeSessionFactory.open(ime.applicationContext),
        editor = editor,
        ui = ui,
        preparingText = ime.getString(R.string.pinyin_preparing),
        failureText = ime.getString(R.string.pinyin_load_failed),
        starter = PinyinStarter { block -> thread(name = "rime-start", isDaemon = true) { block() } },
        deliver = { block -> main.post(block) },
        script = PrefScriptStore(ime.prefs()),
    )
}

/** Comma long-press offers 简/繁 on a pinyin subtype. Password, email, and URI stay 英文. */
fun commaOffersScriptToggle(hasRimeSchema: Boolean, inputType: Int): Boolean =
    hasRimeSchema && !editorForcesAscii(inputType)

interface PinyinScriptStore {
    fun read(): Boolean
    fun save(simplified: Boolean)
}

class MemoryScriptStore(var value: Boolean = true) : PinyinScriptStore {
    override fun read() = value
    override fun save(simplified: Boolean) { value = simplified }
}

private class PrefScriptStore(private val prefs: SharedPreferences) : PinyinScriptStore {
    override fun read() = prefs.getBoolean(KEY, true)
    override fun save(simplified: Boolean) { prefs.edit().putBoolean(KEY, simplified).apply() }
    private companion object { const val KEY = "pinyin_simplified" }
}

fun editorForcesAscii(inputType: Int): Boolean {
    return InputTypeUtils.isPasswordInputType(inputType)
        || InputTypeUtils.isVisiblePasswordInputType(inputType)
        || InputTypeUtils.isUriOrEmailType(inputType)
}

/** Symbols and the numpad insert the key. They do not drive librime. */
fun keyboardIsSymbols(element: KeyboardElement?): Boolean {
    return element == KeyboardElement.SYMBOLS || element == KeyboardElement.SYMBOLS_SHIFTED
        || element?.isNumberLayout == true
}
