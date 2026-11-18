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

`ShortcutEditorCaptureTest` runs the actual `SetupActivity` and production `BoldTheme`/`ShortcutDialog` with Robolectric API 33 native graphics. It captures the dialog's initial window by drawing its attached decor view into an ARGB bitmap. Profiles are 360 × 800 dp at 100% font size and 320 × 640 dp at 200%; individual dialog images can be shorter than the display. It asserts that scrolling reaches the selection/permission controls and that Save/Cancel remain displayed.

Reproduce PNGs and full semantics:

```sh
SPEED_KEY_CAPTURE_DIR=/workspace/speed-key-final-evidence \
./gradlew --no-daemon -PunsignedRelease=true :app:testDebugUnitTest \
  --tests ai.boldkeys.ShortcutEditorCaptureTest
```

Four initial-viewport PNGs cover Contact and Direct Call at each profile. At large text, clipping at the scroll viewport boundary is intentional; lower content is reached by scrolling. These are native-rendered test screenshots, not emulator/device window captures. System picker and permission overlays, OEM colors and real telephone behavior are not represented.

Experimental repeated/scrolled native snapshots produced stale clipping despite unchanged title layout bounds. Compose's frame-commit capture also timed out in this environment. Those experimental captures are excluded from the deliverable. No product layout change was made to compensate for a test-renderer artifact. The final initial-viewport captures must be inspected as pixels, alongside the reachability assertions.

## Checks and CI investigation

The local workflow-equivalent command passed with 184 debug and 184 release API 33 tests, debug/release lint, and debug APK assembly. The workflow's 17 Python release checks also passed. Final candidate checks are rerun after committing this follow-up. API 36 runtime testing remains blocked by the runtime dependency download described in `SPEED_KEY_CONTACTS.md`.

The original PR Android run `44222251344` failed in its build/test step. Its only check annotation was generic exit code 1. Downloading logs was denied by the environment's enforced destination policy for GitHub's log-storage hosts; no root cause can be inferred from that annotation. The workflow now preserves Gradle output in its existing one-day QA artifact and emits its last 80 lines through a failure check annotation, without changing permissions, checks or action pins. The follow-up PR run is monitored separately.
