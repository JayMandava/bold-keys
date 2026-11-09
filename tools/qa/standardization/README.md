# Settings standardization: opt-in emulator visual QA

This harness launches the **real `SetupActivity`** with its production theme and settings UI. No product source, shipping manifest, or normal Gradle configuration is changed. The Gradle init script adds instrumentation sources/dependencies only when explicitly selected.

## Build without an emulator

Use the repository's provisioned SDK, JDK 17, and preaccepted SDK licenses:

```sh
./gradlew --no-daemon -I tools/qa/standardization/init.gradle \
  -PunsignedRelease=true -PtestAbi=x86_64 \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

For a quicker compile-only smoke check, replace the final two tasks with `:app:compileDebugAndroidTestKotlin`.

## Capture on an already authorized disposable emulator

```sh
bash tools/qa/standardization/capture.sh
# If APKs have already been built with the init script:
SKIP_BUILD=1 bash tools/qa/standardization/capture.sh
```

The script refuses a non-emulator target. It does not start an emulator, enable KVM, change device permissions, grant microphone access, clear application data, download models, delete learning, or open external URLs. It changes only the disposable emulator's display size/density/font scale, restoring their previous values on exit. Use a fresh emulator so preexisting app data does not affect screenshots. The script installs the normal debug package `ai.boldkeys` and its test APK; do not supply review-build suffix flags.

Profiles: 720×1280 at density 320/100% text (360dp width); 640×1280 at density 320/130% text (320dp width); 720×1280 at density 320/200% text. Each profile runs one instrumentation process, not a new UIAutomator shell process per lookup. Compose semantics navigate and scroll deterministically; screenshot capture uses the existing instrumentation's UIAutomation instance. There are no coordinate taps. Before screenshots, Compose and instrumentation idle are synchronized, then a bounded 250ms compositor-settle interval is applied. Appearance and Typing first-view anchors and the scroll origin are asserted.

## Coverage

Every profile captures Home, Typing, Appearance, Key feel, Symbols & emoji, Personal dictionary, Speed key, Context model, Experimental, About, and Licenses & credits. Ordinary Settings screens are scrolled from the top to their actual end with 20% viewport overlap and no artificial five-page cutoff. Licenses use **section sampling**: one starting viewport for each of the eleven license sections, plus the final scroll boundary (12 images per profile). Each section's entire composed legal text is matched against its asset and its full `TextLayoutResult` is checked for overflow, including off-viewport lines. Intermediate license viewports are not exhaustively imaged or visually reviewed. This bounded sampling avoids hundreds of redundant legal-text screenshots.

All 14 expandable help controls are asserted present, individually expanded, checked for `Expanded` semantics, and then captured together through the full scroll range. Each is then independently collapsed and checked for `Collapsed` semantics. Screens with no help controls still receive a complete sweep. Navigation back to Home is asserted after every section. The Home status reflects a fresh, not-enabled keyboard; this harness does not enable the keyboard or exercise system setup flows. Transient editing/deletion/download dialogs are outside this read-only capture scope.

Each viewport saves a real PNG and unmerged Compose semantics text. All composed text layout results are checked for actual line-width/height overrun greater than one pixel, ellipsis, and max-line/text truncation. Raw `hasVisualOverflow` is logged with exact layout-size, paragraph-size, line-extent and truncation metrics, but is not itself a failure: AndroidX's simple-text semantics can reconstruct a paragraph at the incoming maximum width while retaining the original intrinsic layout size. Horizontal alignment remains separately checked through node bounds, and visible semantic nodes' unclipped horizontal positions/sizes must fit the root. Vertical viewport clipping during scrolling is intentional and is not reported as text truncation. These checks do not prove visual alignment, contrast, absence of overlap, or correct typography: **a human/visual image review remains required**, especially for container padding and text near boundaries.

The script pulls results to `qa-artifacts/standardization/<unique-run-id>/<profile>/`, alongside each profile's instrumentation log. `viewports.tsv` enumerates every image; `license-sections.tsv` records each sampled license and full text character count; `screens.tsv` records each completed screen/state and page count; `layout-issues.txt` records accumulated issues; `text-layout-metrics.tsv` records every raw-overflow observation, including tolerated cases. `coverage-complete.txt` appears only after the entire navigation run completes, even if layout assertions subsequently fail. Success requires **all three** instrumentation logs to contain `OK (1 test)`, all three completion markers, and empty layout-issue files. A unique run directory prevents stale screenshots/markers from satisfying a later run.

Build and runtime are deliberately separate so the existing hosted build can compile before any separately approved emulator/KVM execution. Local validation performed for this harness: `bash -n tools/qa/standardization/capture.sh`. Compilation and real emulator execution require the hosted SDK/emulator and have **not** been claimed as passing locally.
