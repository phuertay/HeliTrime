package helium314.keyboard.latin.pinyin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PinyinSubtypePrefsTest {
    private val english = "en-US§KeyboardLayoutSet=MAIN:custom.Latn.4mliee7g.,SecondaryLocales=es"
    private val stock = "zh-Hans-CN§KeyboardLayoutSet=MAIN:pinyin_qwerty,RimeSchema=luna_pinyin"
    private val tokel = "zh-Hans-CN§KeyboardLayoutSet=MAIN:custom.Latn.4mliee7g.,RimeSchema=luna_pinyin"

    @Test fun pinyinTakesTheEnglishLayout() {
        val aligned = alignSubtypeStrings("$english;$stock", "de§KeyboardLayoutSet=MAIN:qwerty", english)
        assertEquals("$english;$tokel", aligned?.first)
        assertEquals("de§KeyboardLayoutSet=MAIN:qwerty;$tokel", aligned?.second)
        assertEquals(english, aligned?.third)
    }

    @Test fun alreadyAlignedIsLeftAlone() {
        assertNull(alignSubtypeStrings("$english;$tokel", "de§KeyboardLayoutSet=MAIN:qwerty;$tokel", english))
    }

    @Test fun selectedPinyinFollowsTheLayout() {
        val aligned = alignSubtypeStrings("$english;$stock", "", stock)
        assertEquals(tokel, aligned?.third)
    }

    @Test fun pinyinUsesTheEnabledHeliboardLayout() {
        val spanish = "es§KeyboardLayoutSet=MAIN:custom.Latn.135kjryqgb."
        val aligned = alignSubtypeStrings("$english;$stock", spanish, english)
        assertEquals("$english;$tokel", aligned?.first)
        assertEquals("$spanish;$tokel", aligned?.second)
    }
}
