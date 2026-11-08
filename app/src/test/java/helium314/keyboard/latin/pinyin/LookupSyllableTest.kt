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

        panel.setModel(model(pad = true, LookupRow("人", -1, -1, listOf("rén"))))
        val writing = texts(panel)
        assertTrue("rén" in writing && "人" in writing, writing.toString())
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
