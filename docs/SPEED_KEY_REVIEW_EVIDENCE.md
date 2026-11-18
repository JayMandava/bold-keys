# Speed key lifecycle and visual follow-up

This follow-up adds tests and CI diagnostics around the Contact/Direct Call implementation introduced in `056c59ace1eeee4b7d058020b23aee84221001e4`; it does not change production keyboard or settings code.

## Lifecycle evidence

`ShortcutLifecycleTest` drives the real `SetupActivity`, opens the actual Speed key editor and calls `ActivityController.recreate()`. It delivers saved request codes through the recreated activity's ActivityResultRegistry path. Seven tests cover:

- Contact and specific-number results returned after recreation.
- Contact and number selections made before recreation surviving without re-reading the closed selected-row cursor.
- A cancelled picker after recreation preserving the prior stored assignment.
- Both permission grant and denial delivered after recreation, preserving the old assignment and launching no call or dialer. Grant requires a subsequent explicit Save.

Provider rows and telephone targets are synthetic. The provider double asserts access is confined to the selected URI. No real contact record or telephone call is used.

## Rendered evidence

`ShortcutEditorLayoutTest` drives the actual settings editor and checks control reachability on API 33 at 360 × 800 dp / 100% text and 320 × 640 dp / 200% text. The production theme, wrapping choices and scrollable editor are used without layout substitutions.

The opt-in instrumentation class `SpeedKeyEditorCapture` in `tools/qa/standardization/src` captures actual emulator dialog windows using Compose's frame-commit/PixelCopy capture path. It never opens a picker, requests Phone permission or calls. It uses only Synthetic Person and a synthetic number. Both editors are captured at their initial viewport and scrolled controls; Direct Call also captures the selected number. Each PNG has matching full Compose semantics. These are private review artifacts, not a release.

Build the isolated x86 QA artifacts:

```sh
./gradlew --no-daemon --max-workers=4 -I tools/qa/standardization/init.gradle \
  -PunsignedRelease=true -PtestAbi=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest
```

On an authorized disposable API 33 emulator, install both debug APKs and run only `ai.boldkeys.SpeedKeyEditorCapture`. Capture profiles use display 720 × 1600 at density 320 / font scale 1.0, and 640 × 1280 at density 320 / font scale 2.0. Outputs are app-private `files/speed-key-captures`; retrieve using `adb exec-out run-as ai.boldkeys ...`. The accompanying deliverable records the exact candidate, APK checksum, emulator fingerprint, capture settings and limitations. This harness can also be used on an emulator with hardware acceleration; the cloud review emulator uses software acceleration.

Robolectric native snapshots were investigated but excluded because an isolated view-draw capture could be blank and frame-commit capture timed out. The unit tests retain reachability assertions; rendered screenshot evidence comes from the emulator harness. A cropped batch preview initially suggested title clipping, but inspection of the raw PNGs disproved it: the fixed title pixels are unchanged before and after scrolling. No product layout change was needed.

## Checks and CI investigation

The local workflow-equivalent command passed with 184 debug and 184 release API 33 tests, debug/release lint, and debug APK assembly. The workflow's 17 Python release checks also passed. Final candidate checks are rerun after committing this follow-up. API 36 runtime testing remains blocked by the runtime dependency download described in `SPEED_KEY_CONTACTS.md`.

The original PR Android run `44222251344` failed in its build/test step. Its only check annotation was generic exit code 1. Downloading logs was denied by the environment's enforced destination policy for GitHub's log-storage hosts; no root cause can be inferred from that annotation. The workflow now preserves Gradle output in its existing one-day QA artifact and emits its last 80 lines through a failure check annotation, without changing permissions, checks or action pins. The follow-up PR run is monitored separately.

## Completed emulator review

Both real emulator instrumentation runs passed (`OK (1 test)` for each): 360 dp / 100% text and 320 dp / 200% text. Ten unretouched window PNGs and matching full semantics were retrieved. Pixel review found readable text, wrapped choices, reachable controls and intact fixed titles/footers. Direct Call's selected number and permission explanation are captured separately at large text. Phone permission stayed denied; no permission request, contact picker launch or call was made by the capture harness.

The captures and successful local debug/release aggregate checks were made against implementation candidate `849ee43036ba61f63f1a0b5440696aca95a20227`. That candidate's GitHub Android CI also passed: run `44224608581`. This final evidence-note correction changes documentation only; the production, unit-test and instrumentation source trees remain identical. The delivered provenance records both the final review SHA and capture SHA, screenshot hashes and runtime details; final-head CI is monitored separately.
