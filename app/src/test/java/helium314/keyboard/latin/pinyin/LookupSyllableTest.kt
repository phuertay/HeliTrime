package helium314.keyboard.latin.pinyin

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.settings.Settings
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LookupSyllableTest {
    @Before
    fun loadSettings() {
        val context = ApplicationProvider.getApplicationContext<App>()
        Settings.init(context)
        Settings.getInstance().loadSettings(context)
    }

    @Test
    fun pickerAndWritingShowTheSyllableUnderEachCharacter() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val panel = LookupPanel(context)
        panel.setModel(model(pad = false, LookupRow("你好", 0, 0, listOf("nǐ", "hǎo")), LookupRow("语", 0, 1, listOf("yǔ"))))
        val grid = texts(panel)
        assertTrue("nǐ" in grid && "hǎo" in grid && "yǔ" in grid, grid.toString())
        assertTrue("写" !in grid && "×" !in grid, grid.toString())
        assertEquals(listOf("写", "×"), texts(panel.actionBar()))

        panel.setModel(model(pad = true, LookupRow("人", -1, -1, listOf("rén"))))
        val writing = texts(panel)
        assertTrue("rén" in writing && "人" in writing, writing.toString())
        assertEquals(listOf("⌫", "清", "全", "简", "⌨", "×"), texts(panel.actionBar()))
        assertSyllableClearsTheCharacter(panel, "人", "rén")
    }

    @Test
    fun syllableSitsUnderTheCharacterInsteadOfCoveringIt() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val panel = LookupPanel(context)
        panel.setModel(model(pad = false, LookupRow("语", 0, 0, listOf("yǔ"))))
        assertSyllableClearsTheCharacter(panel, "语", "yǔ")
    }

    @Test
    fun writingPadUsesTheFreedHeight() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val panel = LookupPanel(context)
        panel.setModel(model(pad = true, LookupRow("人", -1, -1, listOf("rén"))))
        layOut(panel, 1080, 800)
        val ink = find(panel) { it.tag == LookupPanel.INK_TAG }
        assertTrue(ink != null && ink.height == 800 && ink.width == ink.height, "ink ${ink?.width}x${ink?.height}")
    }

    private fun assertSyllableClearsTheCharacter(panel: LookupPanel, hanzi: String, reading: String) {
        layOut(panel, panel.resources.displayMetrics.widthPixels.coerceAtLeast(1080), 800)
        val label = find(panel) { it is TextView && it.text.toString() == hanzi } as? TextView
        val syllable = find(panel) { it is TextView && it.text.toString() == reading } as? TextView
        assertTrue(
            label != null && syllable != null && syllable.top >= label.bottom,
            "syllable top ${syllable?.top} hanzi bottom ${label?.bottom} hanzi height ${label?.height}",
        )
    }

    private fun layOut(panel: LookupPanel, width: Int, height: Int) {
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, width, height)
    }

    private fun find(view: View, match: (View) -> Boolean): View? {
        if (match(view)) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            find(view.getChildAt(index), match)?.let { return it }
        }
        return null
    }

    private fun model(pad: Boolean, vararg rows: LookupRow) = LookupModel(
        rows.toList(), emptyList(), 0, pad, false, true, true, emptyList(),
    )

    private fun texts(view: View): List<String> {
        if (view is TextView) return listOf(view.text.toString())
        if (view !is ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
    }
}
