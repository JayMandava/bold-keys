# Isolated suggestion review on the phone

This package is for the separate physical-device tester. Cloud validation is complete; physical results are still pending.

- APK: `bold-keys-suggestions-review-0.6.2-code10.apk`
- Application ID: `ai.boldkeys.suggestions.review`
- Version: `0.6.2-suggestions-review`, code10; arm64; minimum Android13/API33, target API36
- Launcher/IME label: `Bold Keys Suggestions Review`
- APK SHA-256: `0b1d5c874c5eadefe29413e0d178e390e2c25713ba9a3775666ef393c23f71ea`

Existing production `ai.boldkeys` (reported0.6.0/code8) and `ai.boldkeys.debug` (reported0.6.2-nsfw-phone/code13) are distinct packages. Do not install this APK under either of those IDs, uninstall them, clear their data or treat their versions as the current cloud source. Do not download/import a model for this test.

## Transfer, verify and install

Unzip the review package and verify its `SHA256SUMS`. Set the correct device serial and absolute APK path; the install intentionally omits replacement and data-clear flags:

```bash
shasum -a 256 -c SHA256SUMS
adb devices -l
SERIAL='the-verified-device-serial'
APK='/absolute/path/bold-keys-suggestions-review-0.6.2-code10.apk'
adb -s "$SERIAL" install "$APK"
adb -s "$SERIAL" shell dumpsys package ai.boldkeys.suggestions.review
adb -s "$SERIAL" shell am start -n ai.boldkeys.suggestions.review/ai.boldkeys.SetupActivity
```

If the isolated package already exists, stop installation and inspect its provenance before replacing it. Enable and select the uniquely labeled review keyboard through its setup screen. Record the prior selected IME so it can be restored afterwards. The service component is `ai.boldkeys.suggestions.review/ai.boldkeys.BoldKeysService`.

## Synthetic acceptance checks

Use a disposable empty test editor; avoid personal messages, real credentials or production app data. Keep the model off. Record Android version, app version/ID, layout and suggestion/autocorrect preferences. Do not collect logs or screenshots containing editor text beyond the synthetic fixtures.

1. With English suggestions enabled, type each preceding context and partial word. The expected word should be among the two visible suggestions: `pull re`→request, `unit te`→tests, `database mi`→migration, `kotlin co`→coroutines, `sounds go`→good, `thanks ag`→again, `code rev`→review, `memory lea`→leak, `local lea`→learning. Inspect typing, cursor movement, deletion and selection responsiveness during ordinary typing.
2. In ordinary fields, repeatedly choose a known word and then a synthetic new word, e.g. `please zorbword`. Confirm the word/pair preference persists after dismissing/reopening the keyboard and that the chosen word is not immediately replaced. Test suggestion taps and accessibility activation; confirm an already consumed choice is not counted twice.
3. In the review app's Personal dictionary, remove the synthetic word `zorbword`; confirm its learned pair stops returning. Use **Clear all learning**, reopen the keyboard, and verify old learned words/preferences/pairs do not return. Common words and pairs supplied by the shipped dictionary/tables may still appear. Repeat clearing; test clearing shortly after typing. This clears the isolated app only.
4. In synthetic password, numeric-password and email test fields, confirm no suggestions appear or activate. In URL and `IME_FLAG_NO_PERSONALIZED_LEARNING` fields, exercise typing, suggestions, Enter and correction undo; confirm these actions do not add local words/pairs. Existing learned suggestions may still display in URL/incognito. Compare the isolated learning file before/after if the tester uses `run-as`; do not inspect the production packages.
5. Test a 25-character and 80-character synthetic word, apostrophes, an empty field, pasted punctuation and repeated actions. Confirm no crash or partial-suffix learning. Known deliberate words and correction undo should remain intact.
6. For French, learn/select `bien` after `j'ai ` and confirm the pair is useful after that context. With British spelling, check `paint co` can offer `colour`. Verify switching language keeps learning/clear scopes separate.
7. Confirm the Blackberry-style layout, two-word strip and existing Large/XL geometry remain unchanged. Record actual phone latency observations separately from cloud JVM timing.

Restore the previously selected IME when done. Return pass/fail observations and any synthetic reproduction steps to the parent. Do not publish, release or touch Play Console settings.

The bundled `bold-keys-unsigned-review-0.6.2-code10.aab` is only unsigned build evidence. It uses the production ID and old version code and is not suitable for installation or upload.
