package helium314.keyboard.latin.pinyin

import android.content.SharedPreferences
import android.view.inputmethod.InputMethodSubtype
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue.RIME_SCHEMA
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.mainLayoutNameOrQwerty

private const val STOCK_LAYOUT = "pinyin_qwerty"
private const val LAYOUT_MARKER = "KeyboardLayoutSet=MAIN:"

/** Pinyin uses the enabled HeliBoard language's main layout. `pinyin_qwerty` is only the resource default. */
fun layoutForPinyin(stored: String?, latinLayout: String?): String {
    if (!latinLayout.isNullOrEmpty()) return latinLayout
    return stored ?: "qwerty"
}

/** Layout of the enabled HeliBoard subtype. Chinese is not that subtype. */
fun primaryLayout(subtypes: List<InputMethodSubtype>): String? =
    subtypes.firstOrNull { !it.containsExtraValueKey(RIME_SCHEMA) }?.mainLayoutNameOrQwerty()

/** Language-list switch stays on when the saved pinyin subtype is the Tokel copy, not the resource row. */
fun rowShowsEnabled(enabled: List<InputMethodSubtype>, row: InputMethodSubtype): Boolean {
    if (row in enabled) return true
    if (!row.containsExtraValueKey(RIME_SCHEMA)) return false
    val language = row.locale().language
    return enabled.any { it.containsExtraValueKey(RIME_SCHEMA) && it.locale().language == language }
}

/**
 * Point the pinyin subtype at the enabled HeliBoard language's main layout and store it as an additional subtype.
 * Stock HeliBoard then restores a custom layout it already backs up, and ignores `RimeSchema`.
 * @return null when nothing changed
 */
internal fun alignSubtypeStrings(
    enabled: String,
    additional: String,
    selected: String,
): Triple<String, String, String>? {
    val latin = latinLayoutName(enabled) ?: return null
    if (latin == STOCK_LAYOUT) return null
    if (!enabled.contains("$RIME_SCHEMA=")) return null
    val newEnabled = rewriteMainLayouts(enabled, latin)
    val rime = newEnabled.split(';').filter { it.contains("$RIME_SCHEMA=") }
    if (rime.isEmpty()) return null
    val newAdditional = (additional.split(';').filter { it.isNotEmpty() && !it.contains("$RIME_SCHEMA=") } + rime)
        .joinToString(";")
    val newSelected = if (selected.contains("$RIME_SCHEMA=")) rime.first() else selected
    if (newEnabled == enabled && newAdditional == additional && newSelected == selected) return null
    return Triple(newEnabled, newAdditional, newSelected)
}

internal fun latinLayoutName(enabled: String): String? =
    enabled.split(';').firstOrNull {
        it.isNotEmpty() && it.contains(LAYOUT_MARKER) && !it.contains("$RIME_SCHEMA=")
    }?.let { layoutAfterMarker(it) }

internal fun rewriteMainLayouts(enabled: String, latin: String): String =
    enabled.split(';').joinToString(";") { segment ->
        if (!segment.contains("$RIME_SCHEMA=") || !segment.contains(LAYOUT_MARKER)) segment
        else replaceLayout(segment, latin)
    }

internal fun replaceLayout(segment: String, latin: String): String {
    val start = segment.indexOf(LAYOUT_MARKER)
    if (start < 0) return segment
    val valueStart = start + LAYOUT_MARKER.length
    val comma = segment.indexOf(',', valueStart).let { if (it < 0) segment.length else it }
    return segment.substring(0, valueStart) + latin + segment.substring(comma)
}

private fun layoutAfterMarker(segment: String): String? {
    val start = segment.indexOf(LAYOUT_MARKER)
    if (start < 0) return null
    val valueStart = start + LAYOUT_MARKER.length
    val comma = segment.indexOf(',', valueStart).let { if (it < 0) segment.length else it }
    return segment.substring(valueStart, comma).ifEmpty { null }
}

fun syncPinyinSubtype(prefs: SharedPreferences) {
    val enabled = prefs.getString(Settings.PREF_ENABLED_SUBTYPES, "") ?: return
    val additional = prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, "") ?: ""
    val selected = prefs.getString(Settings.PREF_SELECTED_SUBTYPE, "") ?: ""
    val aligned = alignSubtypeStrings(enabled, additional, selected) ?: return
    prefs.edit()
        .putString(Settings.PREF_ENABLED_SUBTYPES, aligned.first)
        .putString(Settings.PREF_ADDITIONAL_SUBTYPES, aligned.second)
        .putString(Settings.PREF_SELECTED_SUBTYPE, aligned.third)
        .apply()
}

fun disablePinyinSubtype(prefs: SharedPreferences) {
    fun strip(value: String) = value.split(';').filter { it.isNotEmpty() && !it.contains("$RIME_SCHEMA=") }.joinToString(";")
    val enabled = strip(prefs.getString(Settings.PREF_ENABLED_SUBTYPES, "") ?: "")
    val additional = strip(prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, "") ?: "")
    val selected = prefs.getString(Settings.PREF_SELECTED_SUBTYPE, "") ?: ""
    val editor = prefs.edit()
        .putString(Settings.PREF_ENABLED_SUBTYPES, enabled)
        .putString(Settings.PREF_ADDITIONAL_SUBTYPES, additional)
    if (selected.contains("$RIME_SCHEMA=")) {
        val fallback = enabled.split(';').firstOrNull { it.isNotEmpty() }
        if (fallback != null) editor.putString(Settings.PREF_SELECTED_SUBTYPE, fallback)
    }
    editor.apply()
}
