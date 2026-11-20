// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.latin.HardwareKeyAppOverride
import helium314.keyboard.latin.HardwareKeyDeliveryMode
import helium314.keyboard.latin.HardwareKeyImePace
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.AppsUtils
import helium314.keyboard.latin.utils.DeleteButton
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun HardwareKeyAppDeliveryPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "trigger recomposition on preference change")

    val raw = prefs.getString(Settings.PREF_HARDWARE_KEY_APP_DELIVERY, Defaults.PREF_HARDWARE_KEY_APP_DELIVERY).orEmpty()
    val overrides = remember(raw, b?.value) { HardwareKeyAppOverride.parseAll(raw) }
    val lastPackage = run {
        val pkg = prefs.getString(Settings.PREF_LAST_EDITOR_PACKAGE, "").orEmpty()
        if (pkg.isEmpty() || pkg == ctx.packageName) {
            if (pkg == ctx.packageName) prefs.edit { remove(Settings.PREF_LAST_EDITOR_PACKAGE) }
            ""
        } else pkg
    }
    val defaultPace = HardwareKeyImePace.fromPref(
        prefs.getString(Settings.PREF_HARDWARE_KEY_IME_PACE, Defaults.PREF_HARDWARE_KEY_IME_PACE)
    )
    val defaultPaceMs = prefs.getInt(Settings.PREF_HARDWARE_KEY_IME_PACE_MS, Defaults.PREF_HARDWARE_KEY_IME_PACE_MS)

    var packageInfos by remember { mutableStateOf(emptyList<Triple<String, String, Drawable?>>()) }
    var showPicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HardwareKeyAppOverride?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            packageInfos = AppsUtils.getPackagesWithNameAndIcon(ctx.packageManager)
                .sortedBy { it.second.lowercase() }
        }
    }

    fun labelFor(packageName: String): Pair<String, Drawable?> {
        val hit = packageInfos.firstOrNull { it.first.equals(packageName, ignoreCase = true) }
        return (hit?.second ?: packageName) to hit?.third
    }

    fun save(list: List<HardwareKeyAppOverride>) {
        prefs.edit { putString(Settings.PREF_HARDWARE_KEY_APP_DELIVERY, HardwareKeyAppOverride.serialize(list)) }
        (ctx.getActivity() as? SettingsActivity)?.prefChanged()
    }

    fun openEditor(packageName: String) {
        val existing = overrides.firstOrNull { it.packageName.equals(packageName, ignoreCase = true) }
        editing = existing ?: HardwareKeyAppOverride(
            packageName = packageName,
            mode = HardwareKeyDeliveryMode.IME,
            pace = defaultPace,
            paceMs = defaultPaceMs,
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = { }
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { if (lastPackage.isNotEmpty()) openEditor(lastPackage) },
                enabled = lastPackage.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.prefs_hardware_key_app_current))
            }
            Button(
                onClick = { showPicker = true },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.prefs_hardware_key_app_pick))
            }
        }
        if (lastPackage.isEmpty()) {
            Text(
                stringResource(R.string.prefs_hardware_key_app_current_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        } else {
            val (name, _) = labelFor(lastPackage)
            Text(
                stringResource(R.string.prefs_hardware_key_app_current_hint, name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        overrides.forEach { item ->
            val (name, icon) = labelFor(item.packageName)
            val summary = when {
                item.mode == HardwareKeyDeliveryMode.PASS_THROUGH ->
                    stringResource(R.string.prefs_hardware_key_delivery_pass_through)
                item.pace == HardwareKeyImePace.MS ->
                    stringResource(R.string.prefs_hardware_key_app_summary_ime_ms, item.paceMs)
                else ->
                    stringResource(R.string.prefs_hardware_key_app_summary_ime_settle)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { editing = item }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(32.dp)) {
                    if (icon != null) {
                        val px = 32.dpToPx(LocalResources.current)
                        Image(icon.toBitmap(px, px).asImageBitmap(), name)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(item.packageName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(summary, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                DeleteButton {
                    save(overrides.filterNot { it.packageName.equals(item.packageName, ignoreCase = true) })
                }
            }
        }
    }

    if (showPicker) {
        AppPackagePickerDialog(
            packages = packageInfos,
            onDismissRequest = { showPicker = false },
            onPicked = { pkg ->
                showPicker = false
                openEditor(pkg)
            },
        )
    }

    editing?.let { draft ->
        AppOverrideEditorDialog(
            draft = draft,
            displayName = labelFor(draft.packageName).first,
            onDismissRequest = { editing = null },
            onSave = { saved ->
                val next = overrides
                    .filterNot { it.packageName.equals(saved.packageName, ignoreCase = true) } + saved
                save(next)
                editing = null
            },
            onRemove = {
                save(overrides.filterNot { it.packageName.equals(draft.packageName, ignoreCase = true) })
                editing = null
            }
        )
    }
}

@Composable
private fun AppPackagePickerDialog(
    packages: List<Triple<String, String, Drawable?>>,
    onDismissRequest: () -> Unit,
    onPicked: (String) -> Unit,
) {
    var filter by remember { mutableStateOf(TextFieldValue()) }
    val filtered = remember(packages, filter.text) {
        AppsUtils.fuzzyFilterApps(packages, filter.text)
    }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.prefs_hardware_key_app_pick_title)) },
        content = {
            Column {
                TextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_search_key)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.height(360.dp)) {
                    items(filtered, key = { it.first }) { (packageName, name, icon) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPicked(packageName) }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(32.dp)) {
                                if (icon != null) {
                                    val px = 32.dpToPx(LocalResources.current)
                                    Image(icon.toBitmap(px, px).asImageBitmap(), name)
                                }
                            }
                            Column {
                                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                                    Text(name)
                                }
                                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                                    Text(packageName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButtonText = null,
        cancelButtonText = stringResource(R.string.dialog_close),
        onConfirmed = { },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    )
}

@Composable
private fun AppOverrideEditorDialog(
    draft: HardwareKeyAppOverride,
    displayName: String,
    onDismissRequest: () -> Unit,
    onSave: (HardwareKeyAppOverride) -> Unit,
    onRemove: () -> Unit,
) {
    var mode by remember { mutableStateOf(draft.mode) }
    var pace by remember { mutableStateOf(draft.pace) }
    var paceMs by remember { mutableFloatStateOf(draft.paceMs.toFloat()) }
    var showModePicker by remember { mutableStateOf(false) }
    var showPacePicker by remember { mutableStateOf(false) }

    val modeLabel = if (mode == HardwareKeyDeliveryMode.IME)
        stringResource(R.string.prefs_hardware_key_delivery_ime)
    else
        stringResource(R.string.prefs_hardware_key_delivery_pass_through)
    val paceLabel = if (pace == HardwareKeyImePace.MS)
        stringResource(R.string.prefs_hardware_key_ime_pace_ms_mode)
    else
        stringResource(R.string.prefs_hardware_key_ime_pace_settle)

    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(displayName) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(draft.packageName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Preference(
                    name = stringResource(R.string.prefs_hardware_key_delivery),
                    description = modeLabel,
                    onClick = { showModePicker = true }
                )
                if (mode == HardwareKeyDeliveryMode.IME) {
                    Preference(
                        name = stringResource(R.string.prefs_hardware_key_ime_pace),
                        description = paceLabel,
                        onClick = { showPacePicker = true }
                    )
                    if (pace == HardwareKeyImePace.MS) {
                        Text(stringResource(R.string.prefs_hardware_key_ime_pace_ms))
                        Text(stringResource(R.string.abbreviation_unit_milliseconds, paceMs.toInt().toString()))
                        Slider(
                            value = paceMs,
                            onValueChange = { paceMs = it },
                            valueRange = 0f..100f,
                        )
                    }
                }
            }
        },
        confirmButtonText = stringResource(android.R.string.ok),
        cancelButtonText = stringResource(android.R.string.cancel),
        neutralButtonText = stringResource(R.string.delete),
        onNeutral = onRemove,
        onConfirmed = {
            onSave(
                HardwareKeyAppOverride(
                    packageName = draft.packageName,
                    mode = mode,
                    pace = pace,
                    paceMs = paceMs.toInt().coerceIn(0, 100),
                )
            )
        }
    )

    if (showModePicker) {
        val items = listOf(
            stringResource(R.string.prefs_hardware_key_delivery_pass_through) to HardwareKeyDeliveryMode.PASS_THROUGH,
            stringResource(R.string.prefs_hardware_key_delivery_ime) to HardwareKeyDeliveryMode.IME,
        )
        ListPickerDialog(
            onDismissRequest = { showModePicker = false },
            items = items,
            selectedItem = items.first { it.second == mode },
            onItemSelected = {
                mode = it.second
                showModePicker = false
            },
            title = { Text(stringResource(R.string.prefs_hardware_key_delivery)) },
            getItemName = { it.first },
        )
    }
    if (showPacePicker) {
        val items = listOf(
            stringResource(R.string.prefs_hardware_key_ime_pace_settle) to HardwareKeyImePace.SETTLE,
            stringResource(R.string.prefs_hardware_key_ime_pace_ms_mode) to HardwareKeyImePace.MS,
        )
        ListPickerDialog(
            onDismissRequest = { showPacePicker = false },
            items = items,
            selectedItem = items.first { it.second == pace },
            onItemSelected = {
                pace = it.second
                showPacePicker = false
            },
            title = { Text(stringResource(R.string.prefs_hardware_key_ime_pace)) },
            getItemName = { it.first },
        )
    }
}
