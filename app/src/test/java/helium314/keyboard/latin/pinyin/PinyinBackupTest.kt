package helium314.keyboard.latin.pinyin

import android.content.Context
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.settings.preferences.heliboardFilePatterns
import helium314.keyboard.settings.preferences.pinyinBackupFilePatterns
import helium314.keyboard.settings.preferences.readJsonLinesToSettings
import helium314.keyboard.settings.preferences.settingsToJsonStream
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class PinyinBackupTest {
    @Test
    fun scriptPrefRoundTripsInsideTheExistingSections() {
        val out = ByteArrayOutputStream()
        settingsToJsonStream(mapOf(
            PREF_PINYIN_SIMPLIFIED to false,
            "auto_correction" to true,
            "keyboard_height" to 100,
        ), out)
        val text = out.toString(Charsets.UTF_8.name())
        val headers = listOf(
            "boolean settings",
            "int settings",
            "long settings",
            "float settings",
            "string settings",
            "string set settings",
        )
        var last = -1
        for (header in headers) {
            val at = text.indexOf(header)
            assertTrue(at > last, header)
            last = at
        }
        val prefs = prefs("pinyin-backup-roundtrip")
        prefs.edit().clear().commit()
        assertTrue(readJsonLinesToSettings(text.split("\n"), prefs))
        assertFalse(prefs.getBoolean(PREF_PINYIN_SIMPLIFIED, true))
        assertTrue(prefs.getBoolean("auto_correction", false))
        assertEquals(100, prefs.getInt("keyboard_height", 0))
    }

    @Test
    fun heliboardDumpWithoutTheScriptKeyStaysSimplified() {
        val out = ByteArrayOutputStream()
        settingsToJsonStream(mapOf("auto_correction" to true), out)
        val prefs = prefs("pinyin-backup-stock")
        prefs.edit().clear().putBoolean(PREF_PINYIN_SIMPLIFIED, false).commit()
        prefs.edit().clear().commit()
        assertTrue(readJsonLinesToSettings(out.toString(Charsets.UTF_8.name()).split("\n"), prefs))
        assertFalse(prefs.contains(PREF_PINYIN_SIMPLIFIED))
        assertTrue(prefs.getBoolean(PREF_PINYIN_SIMPLIFIED, true))
    }

    @Test
    fun rimeUserDirIsAnExtraZipEntry() {
        val user = "rime/user/user.yaml"
        val nested = "rime/user/userdb/db"
        val layout = "layouts/custom.foo.json"
        assertTrue(pinyinBackupFilePatterns.any { user.matches(it) })
        assertTrue(pinyinBackupFilePatterns.any { nested.matches(it) })
        assertFalse(heliboardFilePatterns.any { user.matches(it) })
        assertFalse(heliboardFilePatterns.any { nested.matches(it) })
        assertTrue(heliboardFilePatterns.any { layout.matches(it) })
    }

    private fun prefs(name: String) =
        RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE)
}
