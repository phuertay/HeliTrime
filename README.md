# Helitrime

Helitrime is an offline Android keyboard. It is [HeliBoard](https://github.com/HeliBorg/HeliBoard) with 26-key pinyin and a way to look up a character you cannot type. The package is `helitrime001.keyboard`. This tree tracks HeliBoard `main` at `3e91b4f1` (2026-11-07).

The app does not request the internet permission.

[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get the APK from GitHub" height="80">](https://github.com/phuertay/HeliTrime/releases/latest)

The APK is signed with the key checked into this repository. Install it over the previous Helitrime beta. HeliBoard from F-Droid is a different app (`helium314.keyboard`) and does not include this pinyin.

## Pinyin

Add **Chinese (Pinyin)** in the language list. The layout is qwerty plus an apostrophe, so syllable breaks do not need the symbols layer.

| Action | Result |
|---|---|
| Type letters | The spelling stays on screen and candidates appear |
| Space | Commits the first candidate |
| 1–9 | Commits that candidate on the page |
| Tap a candidate | Commits it. The small text under a character is its syllable |
| Backspace | Edits the spelling. It does not delete a character you already committed |
| Enter | Commits the composition and does not send the editor action |
| Apostrophe | Splits syllables (`xi'an`) |
| z/zh, c/ch, s/sh, n/l, an/ang, en/eng, in/ing | Count as the same sound |
| Long-press comma, then 简/繁 | Switches simplified and traditional. The space bar reads 中文·简 or 中文·繁 |
| 查 | Looks a character up by radical or by handwriting |
| Globe | Leaves Chinese for the next language |

Password, visible-password, email, and URI fields stay in ASCII for that field. A phrase typed in incognito is not kept in the normal pinyin user dictionary.

The personal dictionary screen is still the Latin word list. It is not imported into the pinyin dictionary.

Candidates come from [luna_pinyin](https://github.com/rime/rime-luna-pinyin) through [librime](https://github.com/rime/librime). Phrase order also uses the compact [octagram](https://github.com/lotem/rime-octagram-data) model for traditional text, then the simplifier. That order has not been hand-tuned. There is no 9-key layout, Wubi, or Cangjie.

If the dictionaries are not ready, the strip says “Preparing pinyin” and the keystrokes wait. If the engine fails to load, the strip says so and the letters fall through as plain text.

## Everything else from HeliBoard

<ul>
  <li>Add dictionaries for suggestions and spell check</li>
  <ul>
    <li>build your own, or get them <a href="https://codeberg.org/Helium314/aosp-dictionaries#dictionaries">here</a> (quality may vary)</li>
    <li>additional dictionaries for emojis or scientific symbols can be used to provide suggestions</li>
    <li>Korean layouts need <a href="https://github.com/openboard-team/openboard/commit/83fca9533c03b9fecc009fc632577226bbd6301f">this dictionary</a></li>
  </ul>
  <li>Themes, colors, and a background image, including day/night and Android 12 dynamic colors</li>
  <li>Custom <a href="layouts.md">layouts</a></li>
  <li>Multilingual typing, clipboard history, one-handed mode, a split keyboard, and a number pad</li>
  <li>Glide typing only with a closed-source library, which is not in the app</li>
  <li>Backup and restore of settings and learned words</li>
</ul>

HeliBoard’s [wiki](https://github.com/HeliBorg/HeliBoard/wiki) still covers layouts, gestures, and the FAQ. Those pages describe HeliBoard, not the pinyin keys above.

## License

Helitrime, like HeliBoard and OpenBoard, is [GPL-3.0](LICENSE). The AOSP keyboard code is [Apache-2.0](LICENSE-Apache-2.0). The icon is [CC BY-SA 4.0](LICENSE-CC-BY-SA-4.0).

| Piece | License |
|---|---|
| librime, librime-octagram | BSD-3-Clause |
| OpenCC | Apache-2.0 |
| libiconv | LGPL, shared library only |
| rime-prelude, rime-luna-pinyin, rime-essay, rime-octagram-data | LGPL-3.0 |

## Credits

- [HeliBoard](https://github.com/HeliBorg/HeliBoard) and its [contributors](https://github.com/HeliBorg/HeliBoard/graphs/contributors)
- [OpenBoard](https://github.com/openboard-team/openboard), [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/), [LineageOS](https://review.lineageos.org/admin/repos/LineageOS/android_packages_inputmethods_LatinIME), [Simple Keyboard](https://github.com/rkkr/simple-keyboard), [Indic Keyboard](https://gitlab.com/indicproject/indic-keyboard), [FlorisBoard](https://github.com/florisboard/florisboard/)
- [librime](https://github.com/rime/librime), [librime-octagram](https://github.com/lotem/librime-octagram), [rime-luna-pinyin](https://github.com/rime/rime-luna-pinyin), [rime-prelude](https://github.com/rime/rime-prelude), [rime-essay](https://github.com/rime/rime-essay), [rime-octagram-data](https://github.com/lotem/rime-octagram-data), [OpenCC](https://github.com/BYVoid/OpenCC)
- Icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) with contributions from [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic)

HeliBoard is funded through the [NGI Mobifree Fund](https://nlnet.nl/mobifree). That funding is for HeliBoard’s gesture-typing work, described on the [NLnet project page](https://nlnet.nl/project/GestureTyping).
