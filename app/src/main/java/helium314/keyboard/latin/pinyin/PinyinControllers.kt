package helium314.keyboard.latin.pinyin

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
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
    val box = arrayOfNulls<PinyinInputController>(1)
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
        override fun showLookup(model: LookupModel) {
            val panel = ime.pinyinLookupPanel() ?: return
            if (panel.listener == null) panel.listener = ControllerLookup { box[0]!! }
            panel.setModel(model)
            panel.visibility = View.VISIBLE
        }
        override fun hideLookup() {
            ime.pinyinLookupPanel()?.visibility = View.GONE
        }
    }
    val main = Handler(Looper.getMainLooper())
    val controller = PinyinInputController(
        client = RimeSessionFactory.open(ime.applicationContext),
        editor = editor,
        ui = ui,
        preparingText = ime.getString(R.string.pinyin_preparing),
        failureText = ime.getString(R.string.pinyin_load_failed),
        starter = PinyinStarter { block -> thread(name = "rime-start", isDaemon = true) { block() } },
        deliver = { block -> main.post(block) },
        script = PrefScriptStore(ime.prefs()),
        hanzi = HanziTable.open(ime.applicationContext),
    )
    box[0] = controller
    return controller
}

private class ControllerLookup(
    private val controller: () -> PinyinInputController,
) : LookupListener {
    override fun onClose() = controller().onCloseLookup()
    override fun onPick(row: LookupRow) = controller().onLookupPick(row)
    override fun onRadical(radical: Int) = controller().onLookupRadical(radical)
    override fun onDraw() = controller().onOpenPad()
    override fun onLeavePad() = controller().onLeavePad()
    override fun onStroke(points: FloatArray) = controller().onLookupStroke(points)
    override fun onUndo() = controller().onUndoStroke()
    override fun onClear() = controller().onClearInk()
    override fun onWiden() = controller().onWidenLookup()
    override fun onScript() = controller().onLookupScript()
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

/**
 * Boolean inside the preference dump HeliBoard already backs up.
 * Stock restore stores the key and never reads it. A missing key is simplified.
 */
const val PREF_PINYIN_SIMPLIFIED = "pinyin_simplified"

private class PrefScriptStore(private val prefs: SharedPreferences) : PinyinScriptStore {
    override fun read() = prefs.getBoolean(PREF_PINYIN_SIMPLIFIED, true)
    override fun save(simplified: Boolean) {
        prefs.edit().putBoolean(PREF_PINYIN_SIMPLIFIED, simplified).apply()
    }
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
