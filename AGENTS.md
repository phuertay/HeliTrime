# Agent task: set up the Android SDK, then add 26-key pinyin

This file is the task. Do both jobs, in this order. Do not start Job 2 until Job 1's checks pass. Do not stop after Job 1.

The product decision is already made. Do not reopen it.

- Engine is librime, the library inside [Trime](https://github.com/osfans/trime). Do not import Trime's keyboard, theme, or `TrimeInputMethodService`.
- Schema is `luna_pinyin`, simplified Chinese on for `zh_CN`.
- The globe key leaves the Chinese subtype for the next subtype (usually English, with suggestions and glide).
- On the Chinese keyboard, a swipe down on space toggles librime `ascii_mode` (中文 / 英文). Shift stays a shift key. Holding `?123` is not the switch.
- `InputLogic.java` gets no pinyin branches. Latin typing stays on its current path.
- v1 is 26-key only. No 9-key, Wubi, schema import, or fuzzy-pinyin setting.

This repository is `github.com/phuertay/HeliTrime`, a HeliBoard tree. Implement here. Do not open a pull request against `HeliBorg/HeliBoard`. That project's `AI_USAGE.md` rejects AI-written pull requests. Disclose AI assistance in this fork's commit messages.

Match the surrounding code. Classes and methods in this tree are camelCase (`LatinIME`, `onEvent`). New files go in that style. Do not rename existing identifiers.

## Job 1. Environment

The Cloud Agent environment is dashboard-managed (`environmentJsonPath` is null). A committed file does not install the SDK for the next boot until the user Saves a proposed environment. Install on this VM first so Job 2 can compile.

### What is already on a default image

JDK 21, CMake, Python 3, and KVM. JDK 21 is acceptable. The GitHub workflows use JDK 17; do not downgrade a working 21. `compileSdk` is 37. `ndkVersion` in `app/build.gradle.kts` is `28.0.13004108`. AGP is 9.1.1. Gradle wrapper is 9.7.1.

### What is missing until this script runs

`ANDROID_HOME` is unset. There is no SDK and no NDK. `local.properties` is gitignored and must be written on the machine.

### Install

From the repository root:

```bash
bash tools/setup_android_sdk.bash
```

The script is idempotent. Run it a second time and require a zero exit. It:

- installs cmake, ninja, python3, g++, wget, unzip (librime's build needs these later);
- installs command-line tools `commandlinetools-linux-15859902_latest.zip` (sha256 `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`) under `/opt/android-sdk/cmdline-tools/latest`;
- installs `platforms;android-37`, `ndk;28.0.13004108`, and `platform-tools`;
- writes `/etc/profile.d/android-sdk.sh` and `local.properties`;
- symlinks `sdkmanager` into `/usr/local/bin`.

`*.sh` is gitignored, which is why the script is `.bash`. Do not rename it to `.sh` and expect it to stay in git.

If `sdkmanager` asks for a build-tools package while compiling, install that exact package. Do not bump `compileSdk` or the NDK pin to chase a newer version.

New login shells pick up `/etc/profile.d/android-sdk.sh`. The current shell may not. Export `ANDROID_HOME=/opt/android-sdk` and put `cmdline-tools/latest/bin` and `platform-tools` on `PATH` before Gradle.

### Prove the existing app

```bash
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
chmod +x ./gradlew
./gradlew testRunTestsUnitTest
```

That is the CI task in `.github/workflows/build-test-auto.yml`. It must pass before any pinyin code exists. A failure here is an environment problem, not a reason to edit `InputLogic`.

### Save the environment for the next boot

After the Gradle task passes and the setup script has succeeded twice:

1. Snapshot the VM with the cloud environment snapshot tool, alone, and wait until it is ready.
2. Trigger a draft environment build whose `environmentJson` is only:

```json
{
  "install": "bash tools/setup_android_sdk.bash"
}
```

Pass the ready snapshot id on that build. There is no `start` command. Nothing in this app is a daemon.

3. When the build succeeds, propose that same `install` string with the build id. Tell the user to click Save.

Do not wait on Save before Job 2. This VM already has the SDK. Save only helps the next boot. Do not put secrets in the proposal. Egress is unrestricted; do not request an allowlist.

## Job 2. Pinyin

Land the work in the order below. Each phase has an exit check. Do not skip ahead. Commit on a branch named `cursor/<description>-352d`. Push when a phase's check is green, not after every edit.

### Phase 0. Cut

New code lives in a `:rime` Android library (its own CMake `externalNativeBuild`) and in `app/src/main/java/helium314/keyboard/latin/pinyin/`. Gradle allows one native build per module. `app/src/main/jni/Android.mk` stays the Latin dictionary library. Do not fold librime into that makefile.

Lazy-load the `.so` the first time a Rime subtype is used. A missing library must not crash English.

Reference build: Trime tag `v3.3.11`, which uses librime 1.17.0. Use that tag's native build as the recipe. Copy the JNI idea (startup, process key, commit, context, select, deploy, set option). Do not vendor Trime's UI.

### Phase 1. Prove librime

Build librime the way that Trime tag builds it: Boost, OpenCC, LevelDB, yaml-cpp, marisa, glog, and `libiconv` as a shared library. First ABI is `arm64-v8a` only.

Exit check, in order:

1. A host Linux build of librime, given `rime-prelude` + `rime-luna-pinyin` + `rime-essay`, accepts the keys `n i h a o` and returns `你好` as the first candidate. Space or select-candidate 0 commits it.
2. The same assertion as an instrumented Android test on an arm64 emulator. KVM is available. Install `system-images;android-34;google_apis;x86_64` only if the host image cannot run `arm64-v8a`; prefer an image that matches the ABI under test. This test is not the keyboard.

Record the git commit of each data repo in `app/src/main/assets/rime/VERSION`.

### Phase 2. Session wrapper

`RimeSession` is the only type that calls JNI. Methods: startup, deploy, createSession, destroySession, processKey, readSnapshot, selectCandidate, commitComposition, clearComposition, setOption. A snapshot is immutable: commit text, preedit, cursor, candidates with optional comment. `setOption("ascii_mode")` is the 中/英 switch. `setOption("simplification", true)` is the zh_CN default.

Every call hops to one `HandlerThread` and returns. `InputLogicHandler`'s suggest thread must never touch this object.

`PinyinInputController` depends on a `RimeClient` interface. Robolectric tests use a fake. They do not load the `.so`.

Exit check: the fake-session tests in phase 5's list pass.

### Phase 3. Schema data

Ship prelude, `luna_pinyin` schema and dict, `essay.txt`, and the OpenCC tables the simplifier needs. Compile tables on the host with `rime_deployer` and ship `build/` so the phone does not compile the dict on first keypress. Copy shared data plus prebuilt tables into `filesDir` when `VERSION` changes.

Paths:

- shared: `filesDir/rime/shared`
- user: `filesDir/rime/user` (`userdb`, `user.yaml`)

Those paths are process-global. Changing the user dir means restarting the engine.

If `build/` is missing, deploy on device, show "Preparing pinyin" on the strip, and queue keystrokes.

Licenses, kept next to the data:

| Piece | License |
|---|---|
| librime | BSD-3 |
| OpenCC | Apache-2.0 |
| libiconv | LGPL, shared library only |
| rime-prelude, rime-luna-pinyin, rime-essay | LGPL-3 |
| Trime JNI ideas | GPL-3, same as this app |
| essay.txt sources | see that repo's `AUTHORS` (Chewing LGPL, OpenCC Apache-2.0, Android Pinyin Apache-2.0, moedict CC0) |

### Phase 4. Doors

`PinyinInputController` sits next to `mInputLogic`. It is active only when the subtype extra contains `RimeSchema`. Add `RIME_SCHEMA` on `Constants.ExtraValue`, same pattern as `KeyboardLayoutSet`. Do not use `CombiningRules`.

| Call site | When the subtype is Rime |
|---|---|
| `LatinIME.onEvent` | Controller first. Unconsumed keys keep `onCodeInput`. |
| `KeyboardActionListenerImpl.onKeyDown` | Same controller. Hardware keys skip `onEvent` and call `InputLogic.onCodeInput` today. Korean already special-cases this method. |
| `onVerticalSpaceSwipe` | First positive step toggles `ascii_mode` once per gesture. Upward steps keep the user's vertical swipe action. |
| `onEndSpaceSwipe` | Clear that one-shot latch. |
| `LatinIME.onTextInput` | Multi-character popup keys. |
| `pickSuggestionManually` | Kind `KIND_RIME` calls `selectCandidate`. Never `commitChosenWord`. |
| `onUpdateSelection` | A real cursor move or a selection clears composition. Skip `WordComposer` recorrection. Ignore the move `setComposingText` itself caused (`RichInputConnection`'s belated-update check). |
| `onStartInputViewInternal` / `onFinishInput` | Create or clear the session. Clear drops the preedit and does not commit it. |
| `switchToNextSubtype` | Drop the preedit, then the existing subtype cycle. |
| `onStartBatchInput` | Ignore glide. Commit nothing. |
| `setNeutralSuggestionStrip` | While a preedit or menu is showing, do not cover the row with the clipboard chip. |

Exit check: an English subtype constructs zero `RimeSession` objects. `./gradlew testRunTestsUnitTest` still passes.

### Phase 5. Keys

Digits are handled in the controller. Trime selects in its own UI. Stock desktop Rime binds number-select to Control+digit. Match Trime.

Down is a positive `steps` value: `PointerTracker.onKeySwipe` uses `dY / sPointerStep`, and screen y grows downward. After a vertical swipe, finger-up calls `onEndSpaceSwipe` and does not type a space. The fast-typing guard in `onKeySwipe` stays, so a sloppy tap is not a swipe. Vertical space swipe defaults to `NONE` (`Defaults.PREF_SPACE_VERTICAL_SWIPE`). Horizontal stays move-cursor.

| Key | 中文 (`ascii_mode` off) | 英文 (`ascii_mode` on) |
|---|---|---|
| Swipe down on space | `finishComposingText` so the underlined letters become ordinary text, then `clearComposition`, then `ascii_mode` on | `ascii_mode` off. Ignored when the field forced 英文 |
| Space tap | `XK_space`. The schema commits candidate 1 | A space character |
| Swipe left or right on space | Existing horizontal action | Same |
| a-z | Lowercase keysym. Preedit grows | Commit the letter |
| Shift+letter | Lowercase keysym. Mode stays 中文. Keycaps may draw as capitals | Uppercase Latin letter |
| Backspace | `XK_BackSpace`, including repeat | Latin delete |
| Enter | `commitComposition`. No editor action | Existing action key |
| 1-9 | `selectCandidate` on the current page | Insert the digit |
| Apostrophe | Pass through. It splits syllables | Insert `'` |
| Other punctuation | Pass through. The schema usually commits, then inserts | Insert the character |
| Symbols layer | End composition, then the symbol | Unchanged |
| Emoji, globe, toolbar | Not sent to librime | Not sent to librime |

Double-space period, autospace, and autocorrect stay off for the whole `zh_CN` subtype, including 英文. English suggestions are the English subtype, via the globe key. The space bar label follows the mode: 中文 or 英文.

`KeyboardState.kt` stays unchanged.

Exit check: fake-session tests. `n i h a o` sets the preedit. Select 0 commits and clears it. Backspace shrinks it. A cursor jump clears it. An unconsumed key is returned to the caller. The first positive vertical step finishes the letters as Latin and sets `ascii_mode`. A second step in that gesture does not flip back. `onEndSpaceSwipe` arms the next gesture. An upward step does not flip.

### Phase 6. Candidate row

`config_suggestions_count_in_strip` is 3. `SuggestedWords.MAX_SUGGESTIONS` is 18. The "more" popup is a Latin word list. Do not reuse `MoreSuggestions`.

Step A. Add `SuggestedWordInfo` kind `KIND_RIME` and an input style so `setSuggestedWords` does not treat the row as punctuation or as an empty dictionary. `pickSuggestionManually` uses the candidate index.

Step B. When that kind is showing, `SuggestionStripView` hides the toolbar and shows a horizontal scroller: variable width, hanzi, the pinyin comment in secondary text, page controls from Rime's `page_no` and `is_last_page`. Latin keeps the 3-slot strip. 英文 shows the usual empty strip.

Exit check: a Latin suggestion strip still shows three words. A pinyin menu scrolls and a tap selects that index.

### Phase 7. Subtype and layout

Add `zh_CN` to `app/src/main/res/xml/method.xml`. Language tag `zh-Hans-CN`. Extra values: `AsciiCapable`, `EmojiCapable`, `KeyboardLayoutSet` for the new layout, `RimeSchema=luna_pinyin`. Pick a stable subtype id the way the existing ids were picked, and do not collide. `SubtypeSettings` builds the language list from `method.xml`.

Layout: copy qwerty and add an apostrophe key that does not require the symbols layer. Put it in `app/src/main/assets/layouts/main` and point `KeyboardLayoutSet` at it.

On start of a normal `zh_CN` field: `simplification` true, `ascii_mode` false. No new settings screen.

Do not register `zh` in the spell-checker XML.

Strings: subtype label, "Preparing pinyin", and the load-failure line. Do not add translations. Weblate owns those. New resource strings in the default `values/` are fine.

Exit check: `zh_CN` appears in the language list and selects this layout.

### Phase 8. Fields

`finishInput` and the globe key call `clearComposition`. `finishInput` also destroys the session. They do not commit a half-typed spelling.

Rotation and `onStartInputView(restarting=true)` keep the session, the preedit, and `ascii_mode`. Process death reloads from the user dir and redeploys only when `VERSION` or `build/` is missing. The next normal text field starts in 中文.

Password, visible-password, email, and URI force `ascii_mode` for that session. A space swipe does not leave 英文. Candidates stay hidden.

### Phase 9. Privacy and failure

Incognito must cover pinyin learning. librime writes `userdb` under the process-global user dir. While incognito is on, restart the engine with the user dir pointed at a cache folder, and delete that folder when incognito turns off. Do not redeploy the schema on each key.

The personal-dictionary screen stays the Latin word list. v1 does not import it into a Rime user dict. Say that in the subtype summary.

Load failure, deploy failure, or a missing schema: the subtype stays selectable, the strip shows one error line, and letters fall through to `InputLogic` as raw pinyin. Catch `UnsatisfiedLinkError` at the load site.

### Phase 10. Size

Trime 3.3.11 is about 9 MB per ABI for the whole app. This app ships four ABIs, and the debug workflow drops `main_ro.dict` to stay under GitHub's 25 MB zip limit. Measure the `.so` and the prebuilt tables before enabling x86 or x86_64.

Debug CI stays arm64-only for `:rime`. Release follows `abiFilters` only after the size is known. F-Droid has to compile librime from source. A checked-in `.so` is not the recipe. Proguard needs keep rules for the JNI class. Minify is on for release and for the normal debug variant.

Exit check: `./gradlew testRunTestsUnitTest` passes, and a debug assemble for arm64 produces an APK whose extra native size is written down in the pull request.

## Files

| Path | Change |
|---|---|
| `:rime` module | CMake, JNI, librime. `app` depends on it. |
| `.../pinyin/RimeSession.kt` | Thread hop, snapshots, `setOption`. |
| `.../pinyin/RimeKeyMapper.kt` | Key code and `KeyEvent` to keysym plus mask. |
| `.../pinyin/PinyinInputController.kt` | Policy, commit, preedit, space-swipe latch. |
| `app/src/main/assets/rime/` | Schema, dict, essay, OpenCC, prebuilt `build/`, `VERSION`. |
| `app/src/main/assets/layouts/main/pinyin_qwerty` | Qwerty plus apostrophe. |
| `LatinIME.java` | Doors in phase 4. Globe key drops the preedit. |
| `KeyboardActionListenerImpl.kt` | Hardware branch. Downward space swipe toggles before the user's vertical action. |
| `SuggestionStripView.kt` | Scroller for `KIND_RIME`. Space label 中文 / 英文. |
| `SuggestedWords.java` | New kind and input style. |
| `Constants.java` | `RIME_SCHEMA`. |
| `res/xml/method.xml` | `zh_CN` subtype and stable id. |
| default `strings.xml` | Subtype label, preparing, failed. |
| `InputLogic.java` | No edits. |
| `KeyboardState.kt` | No edits. |
| `jni/Android.mk` | No edits. |
| spell-checker XML | No `zh` locale. |

## Device checks before calling it done

- `n i h a o` shows `你好` on the first page. Space commits it.
- Backspace edits the preedit and does not delete a committed character.
- Enter while composing commits and does not fire the editor action.
- A downward space swipe does not also insert a space.
- A password field inserts ASCII, and a downward swipe stays in 英文.
- Incognito: a phrase typed there is absent from the normal `userdb` after process death.
- English: suggestions, autocorrect, glide, clipboard chip, symbols, Shift, horizontal space swipe, language key.

A native speaker still has to judge candidate order for real sentences. Say that in the pull request. Do not claim the dictionary ranking was tuned.

## Left out on purpose

9-key and a T9 schema. Wubi and Cangjie. Importing a user schema directory. Fuzzy pinyin. A traditional toggle (`simplification` off) for `zh_TW` / `zh_HK`. A second 中/英 gesture such as holding `?123`. Next-word prediction plugins.

## Done

Job 1's Gradle task passed, the setup script passed twice, and the environment proposal was made if the cloud tools were available.

Job 2's phase checks passed, including the host `你好` test and the instrumented test. `InputLogic.java` has no diff. The pull request describes the APK size change and the untested linguistic quality.
