# Bold Keys

A private, on-device keyboard for Android with a physical-keyboard feel: lit, shaded keys, secondary symbols on every key, a trackpad
for the cursor, and autocorrect that runs entirely on the phone. The keyboard does not upload typed text for processing or remote inference.

## Install

Download the APK from the [latest release](https://github.com/JayMandava/bold-keys/releases/latest), allow installs from your browser or file manager, open it, and
follow the two steps on screen to turn the keyboard on. Needs Android 13 or newer on an arm64 phone.

## Features

- **Keys that feel real:** each key is drawn as a lit 3D surface, with a haptic click, black or white themes and an optional backlight
  (white, amber, blue or green; on, off or following the light sensor).
- **Four rows, no number row:** every key has a second symbol, reached by holding or tapping alt (tap it for one symbol; double-tap, or hold it for half a second and let go, to keep it on for a run of numbers). The sym key opens a picker of symbols (three pages), and the right-hand key opens the emoji picker; both stay open until you close them, and scroll with the trackpad or your finger. A new text field always starts on the letter keys.
- **Trackpad:** drag on the bar to move the cursor. Hold shift and drag to select. A quick tap takes the first suggestion.
- **Caps lock:** hold Shift for about half a second, or double-tap it, to keep letters capitalised. The bar under its arrow shows the lock; tap Shift to turn it off. A quick single tap still capitalises just the next letter.
- **Autocorrect and suggestions:** a dictionary tuned to where thumbs land (a miss next to the right key costs less than a random one),
  completions as you type, next-word suggestions, and double space for a full stop after a word or emoji. Backspace right after a correction brings your word
  back and teaches it. Words you type twice are learned; you can add, remove and clear them in Settings.
- **Emoji:** tap the right-hand key (the grid of dots, as on the original's Speed key) for every emoji your phone can show, in Unicode's groups, with skin tones (hold an emoji with a dot and slide to a tone; set a default in Settings). "more" jumps to the next group. The picker stays open after each emoji (so a run of them is one visit) and has a delete arrow of its own; its first row, "Recent", holds the seven you use most and most lately.
- **Speed key:** hold the right-hand key and tap a letter to open an app, a link or type a snippet you set in Settings. Nothing is
  shown on the keys; a plain tap opens the emoji picker (or acts as Shift if that picker is disabled).
- **QWERTY, QWERTZ or AZERTY:** choose the layout in Settings > Typing. AZERTY swaps A and Z with Q and W and moves M beside N; QWERTZ swaps Z and Y. The spelling model follows the layout, so
  a slip to a neighbouring key still counts as a slip. Choose the dictionary separately under Language (Automatic gives French with AZERTY, German with QWERTZ, English with QWERTY).
- **Key size:** Small, Normal, Large or XL in Settings > Appearance, for bigger buttons (or a shorter keyboard). The keys get taller; the width is always the screen's.
- **German:** a German dictionary and next-word hints with QWERTZ. Hold A, O, U or S for `ä`, `ö`, `ü` and `ß` (German puts these first; the French forms are still there), and `fur` offers and corrects to `für`.
- **French:** a French dictionary and next-word hints, with accent-aware correction (`ete` becomes `été`, `ca` becomes `ça`) and `l'`, `j'`, `qu'` handled. The optional context model stays English-only for now.
- **Accents on hold:** hold A, E, I, O, U, C, Y, N or S and slide to an accented form (é è ê ë, à â ä, ç, ñ and more); the plain letter is replaced when you let go.
- **American or British spelling:** colour/color, realise/realize and similar pairs follow the one you choose.
- **Context model (optional):** a small language model checks each word against the sentence around it. It picks the right fix for a
  misspelling and catches real words used for another (their/there, then/than, to/too). It only scores words the dictionary proposes and
  never writes text. Off by default. See [docs/how-it-works.md](docs/how-it-works.md).
- **Private by design:** typing and inference run locally. Password and email fields disable corrections/suggestions; web-address fields may show suggestions but are not rewritten. Editors requesting `IME_FLAG_NO_PERSONALIZED_LEARNING`, and password/email/web-address fields, do not update learned words or emoji history/tone preferences. Optional downloads and external providers are described below.
- **Currency key:** the key beside M shows and types your region's sign (£, €, ₹ ...) or any of fourteen you choose in Settings. Its small speaker is real: hold the key and scroll the trackpad up or down to change the media volume (a quick tap still types the sign; switch it off in Settings). No special permission is needed.
- **Material 3 Expressive settings,** and text emoticons that turn into emoji (optional).

## Privacy and external providers

Model downloads contact Hugging Face over HTTPS only after you tap Download, and may follow delivery redirects. No typed text is attached; servers can see normal request metadata such as IP address, request time, headers and model path. Downloads are checked against a fixed SHA-256 and size. “Wi-Fi only” uses Android's metered-network status.

AndroidX EmojiCompat may ask a system provider for an emoji font. That provider may use cached data or networking depending on the device; the keyboard does not supply typed text in that request. Opening a Speed-key link or the privacy policy hands the link to another app. Model import uses Android's document picker; the selected provider may be cloud-backed. Those providers have their own network behavior and policies.

Learned words, preferences, shortcuts and emoji history are kept in app-private storage, without app-level file encryption. Password/email/address and no-personalized-learning protection depends on the editor reporting its input type/flags correctly. Settings > About contains the privacy-policy link and offline Licenses & credits. No analytics, ads, billing or crash-upload SDK is included in the resolved production dependencies.

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

Run the focused keyboard regression tests (Robolectric, no connected phone required) and Android lint with:

```
./gradlew testDebugUnitTest lintDebug
```

For production releases and nonpublishing AAB validation, see [Protected cloud releases](docs/CLOUD_RELEASE.md). Review [third-party distribution blockers](docs/THIRD_PARTY_REVIEW.md) before approving any signing/distribution run. Release builds never fall back to a debug signing key; `-PunsignedRelease=true` explicitly creates an unsigned candidate for separate production signing.

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

Application code: Apache License 2.0. Third-party data, fonts, libraries and optional models keep their own terms. See [LICENSE](LICENSE), [NOTICE](NOTICE), the offline Licenses & credits screen, and [distribution review](docs/THIRD_PARTY_REVIEW.md).
