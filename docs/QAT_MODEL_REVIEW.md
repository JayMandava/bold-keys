# Gemma 3 270M QAT optional catalogue choice — 2026-11-04

This follow-up builds on suggestion candidate `98e9686f6a13aaf10b0a6a20ee7a8a4a43d5359f`. It adds only a third optional context-model choice and its integrity/persistence tests; the suggestion-ranking, bounded learning and deliberate keyboard layout remain intact.

## Identity and evidence

- New ID: `gemma3-270m-qat-q4`; third after the unchanged `gemma3-270m-q4` and `gemma3-270m-q8` entries.
- File: `gemma-3-270m-it-qat-Q4_0.gguf`, 241,410,208 bytes.
- SHA-256: `5f4b2e17722e510122c464573b880587f4983347a40e5472b858d5a3c1ab8095`.
- Publisher: LM Studio community / LM Studio team GGUF conversion of Google's `gemma-3-270m-it-qat-q4_0-unquantized`.
- Pinned publisher revision: `a909196bf1a24eb8cb855e9b7b73742e0b732fc6`.
- Download locator: `https://huggingface.co/lmstudio-community/gemma-3-270m-it-qat-GGUF/resolve/a909196bf1a24eb8cb855e9b7b73742e0b732fc6/gemma-3-270m-it-qat-Q4_0.gguf`.
- Parent independently verified the publisher metadata and supplied these pins. The publisher's previously inspected model card identifies Gemma terms and llama.cpp b6153 conversion. The pinned README URL could not be read by the web tool in this follow-up; no additional pinned-card retrieval is claimed.
- The user supplied the model locally to the parent. The parent reports successful phone import and inference for three targeted smoke cases. Those checks do not establish comparative quality or speed.

**No model was downloaded, transferred to this cloud, bundled or used for inference here.** The earlier artifact request failed at proxy CONNECT with HTTP403. It was not retried. Actual end-to-end network download of the new pinned locator remains unverified in this cloud. Tests use only tiny synthetic byte streams/fake connections and sparse size-only placeholders that are never loaded as models.

## Selection, import and transfer behavior

Existing catalogue IDs, filenames, URLs, first-choice order, context-model opt-in default and saved preferences are unchanged. An explicit saved choice survives a different model's download even when its file is temporarily absent; the original fallback order still applies while it is missing. QAT must be installed and chosen like any optional model. Downloading never turns the context feature on.

A file imported under an exact catalogue filename must match both pinned size and checksum before it replaces the existing file. Valid imports select the matching catalogue entry. Other custom imports retain their existing custom-ID behavior. Pre-existing custom records are retained, including a legacy imported QAT selection. Such an alias and its new catalogue counterpart can refer to the same physical file: removing either removes that file. The UI explicitly explains this shared-file deletion behavior; unrelated model files are untouched.

Transfer tests cover valid bytes, HTTP failure, wrong digest, truncated/oversized bodies, offline errors, metered-network refusal, cancellation and rename failure. Verified transfer installation uses unique temporary files and checked rename. Cancellation state is thread-safe; cleanup finishes before a retry/completion status is exposed. Failure does not replace an existing file, invoke completion or overwrite a previous selection. Connections are disconnected and temporary files removed. Typed text is not part of model download requests.

## Licence and attribution

Primary sources inspected on 2026-11-04:

- [Google Gemma Terms of Use](https://ai.google.dev/gemma/terms), last modified April 1, 2026, explicitly include Gemma3 in their appendix.
- [Gemma Prohibited Use Policy](https://ai.google.dev/gemma/prohibited_use_policy), incorporated by those terms.
- [Publisher model card](https://huggingface.co/lmstudio-community/gemma-3-270m-it-qat-GGUF), previously inspected; identifies the Google QAT source and LM Studio conversion provenance.

The terms distinguish model rights from application-code licensing. Section3.1 conditions redistribution on downstream use restrictions/notice, provision of the agreement, modification notices where applicable and an accompanying notice file for non-hosted distributions. Section3.2 incorporates the prohibited-use policy. A conversion does not remove the base-model conditions.

Bold Keys does not modify or bundle these weights. Existing in-app model terms/use-restriction text and browser link remain; offline notices now attribute the LM Studio QAT conversion and Google source alongside the existing Unsloth entries. No agreement was accepted on anyone's behalf. This verifies the applicable licence evidence and preserves existing notice handling; it does not certify every potential downstream distribution arrangement or approve a public release. Any future redistribution of weights must satisfy the actual model terms separately.

## Review-build identity and verification

The isolated application ID remains `ai.boldkeys.suggestions.review` so the parent may upgrade that review app while retaining its local imports and preferences. Its version name is now `0.6.2-suggestions-qat-review`, code10. Production ID/version, production/debug user data and both original model entries are unaffected. The prior review APK (`0.6.2-suggestions-review`, SHA-256 `0b1d5c874c5eadefe29413e0d178e390e2c25713ba9a3775666ef393c23f71ea`) predates QAT and is stale for this follow-up.

The new APK and its final hash/source commit are recorded in the transfer package's `SOURCE.json` and `SHA256SUMS`. To upgrade the isolated review app, verify its exact package/version and that its debug signer matches the previous review APK, then use `adb install -r` on this isolated package only. Do not uninstall or clear data; do not replace `ai.boldkeys` or `ai.boldkeys.debug`. The model must remain local to the phone/parent; import it there if needed, not into this cloud. Validate that QAT appears third, existing Q4/Q8 selections survive, each model is independently selectable, and the context feature remains opt-in. Disable networking for import/inference checks so no endpoint test is implied.

Cloud verification commands (Temurin21.0.9+10, Gradle8.13, Android SDK36/build-tools35.0.0):

```bash
source /workspace/toolchains/bold-keys-env.sh
export JAVA_HOME=/workspace/toolchains/jdk-21.0.9+10
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug --offline --max-workers=2 -Pkotlin.incremental=false -ProbolectricSdks=33 -PunsignedRelease=true -PsuggestionReviewBuild=true
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest --offline --max-workers=2 -Pkotlin.incremental=false -ProbolectricSdks=36 -PunsignedRelease=true -PsuggestionReviewBuild=true
```

`unsignedRelease=true` prevents production signing-property access; the isolated debug APK receives normal Gradle debug signing. No production signing, version bump, push, tag, merge, release or remote workflow dispatch is part of this follow-up.

Final cloud results: API33 debug94/release94 and API36 debug94/release94, all zero failures/errors/skips (12 synthetic model-management tests plus the existing82). Debug/release lint have zero errors (32/31 warnings), and the isolated debug APK builds and its signature verifies. Independent review found and verified the cleanup-race fix; final review has no unresolved code blocker. The new APK signer matches the old isolated review APK, and no GGUF files are present in the APK.

Additional checks: pinned dictionary/prediction-data tests15/15 and release/bundle/PEPK workflow-contract tests36/36 passed. APK SHA-256: `d5ed9167558260bbd3d997ce6bceec14b17750aef8a5cf440e4cea27f7619b2e` (35,842,707 bytes).
