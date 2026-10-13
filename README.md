# Bold Keys

A private, on-device keyboard for Android with a physical-keyboard feel: lit, shaded keys, secondary symbols on every key, a trackpad
for the cursor, and autocorrect that runs entirely on the phone. Nothing you type leaves it.

## Install

Download the APK from the [latest release](https://github.com/JayMandava/bold-keys/releases/latest), allow installs from your browser or file manager, open it, and
follow the two steps on screen to turn the keyboard on. Needs Android 13 or newer on an arm64 phone.

## Features

- **Keys that feel real:** each key is drawn as a lit 3D surface, with a haptic click, black or white themes and an optional backlight
  (white, amber, blue or green; on, off or following the light sensor).
- **Four rows, no number row:** every key has a second symbol, reached by holding or tapping alt. The sym key opens a picker with symbols
  and emoji; it stays open until you close it.
- **Trackpad:** drag on the bar to move the cursor. Hold shift and drag to select. A quick tap takes the first suggestion.
- **Autocorrect and suggestions:** a dictionary tuned to where thumbs land (a miss next to the right key costs less than a random one),
  completions as you type, next-word suggestions, and double space for a full stop. Backspace right after a correction brings your word
  back and teaches it. Words you type twice are learned; you can add, remove and clear them in Settings.
- **Speed key:** hold the right-hand aA key and tap a letter to open an app, a link or type a snippet you set in Settings. Nothing is
  shown on the keys; a plain tap on that key is still Shift.
- **QWERTY or AZERTY:** choose the layout in Settings > Typing. AZERTY swaps A and Z with Q and W and moves M beside N, and the spelling model follows the layout, so
  a slip to a neighbouring key still counts as a slip. Choose the dictionary separately under Language (Automatic gives French with AZERTY, English with QWERTY).
- **French:** a French dictionary and next-word hints, with accent-aware correction (`ete` becomes `été`, `ca` becomes `ça`) and `l'`, `j'`, `qu'` handled. The optional context model stays English-only for now.
- **Accents on hold:** hold A, E, I, O, U, C, Y, N or S and slide to an accented form (é è ê ë, à â ä, ç, ñ and more); the plain letter is replaced when you let go.
- **American or British spelling:** colour/color, realise/realize and similar pairs follow the one you choose.
- **Context model (optional):** a small language model checks each word against the sentence around it. It picks the right fix for a
  misspelling and catches real words used for another (their/there, then/than, to/too). It only scores words the dictionary proposes and
  never writes text. Off by default. See [docs/how-it-works.md](docs/how-it-works.md).
- **Private by design:** the only network use is the Download button for a context model, and only when you tap it. Passwords, email and
  web-address fields are never corrected or suggested.
- **Material 3 Expressive settings,** and text emoticons that turn into emoji (optional).

## Context models

Settings > Context model lists the models you can use. Each download is checked against a fixed SHA-256 before it is kept, and
"Wi-Fi only" is on by default. You can also import any `.gguf` file.

| Model | Size | Notes |
|---|---|---|
| Gemma 3 270M (compact) | 241 MB | Recommended. About 40 ms to judge a word on a mid-range phone. |
| Gemma 3 270M (precise) | 291 MB | Same model, higher precision. |

On a held-out test of 2,000 cases, with the compact model: typos fixed 80.7% to 88.5%, and real-word slips fixed 88.0% with 0.6% of
correct words wrongly changed. Larger models fix more real-word slips but take 0.2-0.4 s a word and 1-3 GB, so they are import-only.

## Install

Build it (below) and install the APK, then open Bold Keys, enable it, and choose it as the keyboard from its home screen.

## Requirements

- Android 13 (API 33) or newer, arm64.

## Build

You need JDK 17, the Android SDK (platform 36) and NDK 27 with CMake. llama.cpp is a submodule.

```
git clone --recurse-submodules https://github.com/JayMandava/bold-keys.git
cd bold-keys
echo "sdk.dir=/path/to/Android/sdk" > local.properties    # add cmake.dir=... if CMake is not in the SDK
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build also contains two measurement screens, `EvalActivity` (dictionary) and `ContextEvalActivity` (context model).

## Credits

- Word frequencies: the OpenSubtitles-based [FrequencyWords](https://github.com/hermitdave/FrequencyWords) list (CC BY-SA 4.0); word-pair
  counts from Peter Norvig's n-gram data.
- Typeface: Nunito (SIL Open Font License).
- Inference: [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT). Models are downloaded, not bundled, and keep their own licences
  (Gemma is under the Gemma terms).
- Inspired by the classic BlackBerry Bold 9900 keyboard. BlackBerry is a trademark of its owner; this project is independent and is not
  affiliated with or endorsed by BlackBerry.

## Licence

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
