# Model-picker row and fallback fix

This fix follows feature candidate `8e35c1985fb500889713de1c949f9ac8e790bcb8`. Phone QA found that a sole installed QAT fallback appeared checked while an unavailable custom ID remained saved, and that only the small radio control persisted a choice; tapping its label did nothing.

## Result

The checked model continues to mean the effective model currently in use. When this differs from the saved preference, the row visibly explains that the saved model is unavailable and this model is being used temporarily (or that it is the default when there is no saved preference). The same explanation is exposed as its accessibility state description. Resolving a fallback does not overwrite a preference that may become available again.

The installed model's entire selection area, including its name, details and radio indicator, is now one selectable radio target with a minimum48dp height. The list exposes a selectable group. The radio indicator has no separate click handler, so there is one selection action. Tapping the selection area explicitly saves that model even when it was already effective as a fallback; its state then reads “Saved choice”.

Download, Cancel and Delete are sibling targets outside the selectable area. They have model-specific accessible names and do not invoke row selection. Missing or busy models cannot be selected. Selection rechecks file availability and active transfer state at action time, and refreshes a stale row even if selection is rejected. Download/import integrity checks, catalogue/default order, context opt-in and existing saved/custom model IDs are unchanged. Keyboard layout, ranking and local learning code are unchanged.

## Automated evidence

`ModelOptionRowTest` uses the production Compose row, real pointer/semantics actions and real app-private model preferences with synthetic sparse file placeholders. It performs no model request or inference. Eight cases cover:

1. A stale saved custom ID and sole effective QAT fallback: selected status and explanatory visible/accessibility state, with no automatic preference change.
2. Actual pointer tap on the visible model label: persists QAT and changes the state to “Saved choice”.
3. One radio accessibility action with a48dp-or-larger selection target; activation persists an already-effective default.
4. Pointer taps across the selection area and independent choices for all three installed catalogue models.
5. Missing/busy rows disabled, Download/Cancel action isolation and named controls outside radio semantics.
6. An installed model with a transfer in progress: selection rejected and Cancel isolated.
7. Delete action isolation.
8. A file removed after rendering: a stale row action cannot persist it and refreshes disabled state.

All previous model-integrity, selection/persistence, suggestion, privacy and accessibility suites are included in the full checks. The test-only Compose UI dependency is not packaged in the production application. Independent review found no unresolved blocker after the busy-state announcement and guard regression were added.

Commands use the existing SDK `/workspace/android-sdk` (platform36/build-tools35.0.0), Gradle8.13 and the configured proxy/CA environment. API33/API36 checks use Temurin21.0.9+10; a separate API33 unit run uses the existing Temurin17.0.17+10 to match the release workflow's default JDK.

```bash
source /workspace/toolchains/bold-keys-env.sh
export JAVA_HOME=/workspace/toolchains/jdk-21.0.9+10
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug --offline --max-workers=2 -Pkotlin.incremental=false -ProbolectricSdks=33 -PunsignedRelease=true -PsuggestionReviewBuild=true
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest --offline --max-workers=2 -Pkotlin.incremental=false -ProbolectricSdks=36 -PunsignedRelease=true -PsuggestionReviewBuild=true
# In a fresh shell, source bold-keys-env.sh without overriding JAVA_HOME for the JDK17/API33 check.
```

Final logs and XML evidence are in the transfer package. Debug/release lint have zero errors (32/31 warnings); the isolated debug APK builds and verifies with the same debug certificate as the previous QAT review APK.

## Review artifact and phone check

The updated isolated package is `ai.boldkeys.suggestions.review`, version `0.6.2-suggestions-qat-picker-review` / code10. APK SHA-256: `7f11b366c3d33a560ce11cbea00ad7f91d3828a6a0cff667a43e8a45239eade8` (35,859,103 bytes). It supersedes the QAT-only review APK with hash `d5ed9167558260bbd3d997ce6bceec14b17750aef8a5cf440e4cea27f7619b2e`. Final source commit and package hash are in `SOURCE.json` and `SHA256SUMS` in the new transfer package.

After verifying its hash/package and matching debug signer, upgrade only the isolated review app with `adb install -r`; retain its downloaded/imported models and preferences. Do not clear data or replace production `ai.boldkeys` or the existing `ai.boldkeys.debug` app.

On the phone, open Context model and check the current saved choice/fallback explanation. If the original unavailable-custom-ID scenario is present, tap the visible QAT name or elsewhere in its selection area; confirm it becomes “Saved choice” and persists after reopening. A valid legacy custom alias sharing the QAT file should remain selected until an explicit choice. Check all locally installed model choices, TalkBack/focus activation, and separate file-action buttons. Automated tests cover Download/Cancel isolation without requesting model bytes. No new phone-validation result for this picker APK is claimed yet.

The pre-existing `docs/releases/0.6.3-draft.md` edit is intentionally preserved separately and left uncommitted. This fix changes only the isolated review version suffix, not production0.6.2/code10. No production signing, push, merge, tag, release or workflow dispatch occurred. The APK receives only the normal Gradle debug signature.

Final unit results: debug102/release102 on JDK21/API33, JDK21/API36 and JDK17/API33; every configuration has zero failures/errors/skips. All eight picker UI cases pass in every configuration.
