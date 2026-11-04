# Suggestion improvements review — 2026-11-04

Base/main verified against origin: `e8ca3757286e7abf277432bbe7467349c624d305` (0.6.2/code10).
Local implementation branch: `feat/contextual-local-suggestions`.

## Implementation

- `SpellEngine.kt`: rank 24 completions, 12 corrections and bounded follower/supplement seeds using the preceding word and local preference evidence. Keep two visible suggestions. Candidate work stays bounded: existing 4,000 dictionary scans/correction indices, 512 supplement words, 1,024 learned words and 2,048 learned pairs. A context bonus and contextual completion frequency floor help rare relevant candidates compete with common unrelated typo candidates.
- `suggestion_phrases_en.tsv`: 45 authored conversational/technical contexts, suggestion-only. Shipped dictionaries and corpus tables are unchanged. Supplemental words never expand the automatic-correction dictionary.
- `LearningData.kt`, `LearningFiles.kt`: validate and cap counts at 255, word lengths at 24; persist words/preferences and adjacent pairs in the existing per-language private file. Keep the valid UTF-8 maximum below the 512KiB read guard. Settings edits use a revision to invalidate pending memory even when timestamps match.
- `BoldKeysService.kt`: supply preceding context, learn accepted suggestions and known-word frequency, debounce writes for one second, atomically replace the file and flush at editor lifecycle boundaries. Publish dictionaries on the UI thread after reading current Settings state. A generation guard rejects superseded loads. Public engine operations serialize mutable maps/scratch arrays.
- `SetupActivity.kt`: retain per-language dictionary editing and removal; “Clear all learning” clears words, preferences and pairs, including pending memory. Removal of a word removes its pairs. The existing backlight callback was expanded into separate lines to resolve a lint indentation diagnostic; no geometry changed.
- `app/build.gradle.kts`, manifest: opt-in isolated debug package/label for safe device review. Production ID, version, label, model choices and defaults are unchanged.

Password/email suggestion suppression, no-learning in incognito/URL editors, offline operation and safety filtering apply to all output sources and selection. Unsafe deliberate typing remains trusted recognition/confidence evidence, so filtering cannot promote a weaker automatic replacement. No text logging, telemetry, upload or personal-message fixtures were added.

## Final checks

| Check | Result |
| --- | --- |
| Android unit/service/accessibility tests, Robolectric API33 | 82 passed, zero failures/errors |
| Same suite, API36 | 82 passed, zero failures/errors |
| Debug lint | 0 errors, 32 warnings, 3 hints |
| Release lint | 0 errors, 31 warnings, 3 hints |
| arm64 isolated debug APK | Built; APK signature verifies with Android debug certificate |
| Production-ID unsigned release AAB | Built; no signing entries; version remains 0.6.2/code10, so not upload-ready |
| Python tests (Temurin21.0.9+10) | 152 passed |
| Automatic correction comparison against base | 18,000/18,000 decisions identical across English, French and German, before personalization |
| Synthetic context ranking | Expected top-two result in 9/9 cases vs 1/9 on base |
| Independent reviewer | Nine additional editor/privacy/clear/normalization regressions; final diff reviewed, no unresolved blocker |
| Whitespace diff check | Passed |

Initial checks exposed and fixed callback ordering, clear/load races and normalization issues. Running builds concurrently caused Kotlin cache conflicts; all final Gradle checks were serialized. System Java21 was JRE-only and initially failed Gradle/compiler and native-contract fixtures; the full checksum-verified Temurin21 JDK resolves those failures. No signing-tool production pins were changed.

The benchmark uses public dictionaries and nine authored development cases. It is not a held-out conversational accuracy estimate. Linux JVM latency below is not phone latency; saturation updates 3,500 synthetic word/pair actions before measuring.

| Language | Baseline p50/p95 ms | Current p50/p95 ms | Saturated current p50/p95 ms |
| --- | --- | --- | --- |
| English | 0.051 / 0.099 | 0.079 / 0.142 | 1.075 / 4.242 |
| French | 0.061 / 0.163 | 0.103 / 0.311 | 0.767 / 2.566 |
| German | 0.032 / 0.113 | 0.043 / 0.162 | 0.859 / 1.450 |

## Reproduction

Use the configured Android SDK, Gradle cache and a full JDK21. In this saved environment:

```bash
source /workspace/toolchains/bold-keys-env.sh
export JAVA_HOME=/workspace/toolchains/jdk-21.0.9+10
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:bundleRelease --offline --max-workers=2 -Pkotlin.incremental=false -ProbolectricSdks=36 -PunsignedRelease=true -PsuggestionReviewBuild=true
./gradlew :app:testDebugUnitTest --offline --max-workers=2 -Pkotlin.incremental=false -ProbolectricSdks=33 -PunsignedRelease=true -PsuggestionReviewBuild=true
python3 -m unittest discover -s tests -v
python3 tools/benchmark_suggestions.py
```

API36 XML evidence and final logs are included in the review package. API33 XML was captured after the final confirmation run.

## Device and release limits

Physical-device testing has **not** been performed by this cloud task. No emulator/system image is installed here. The separate local test task has reported access to a CPH2467 on Android17; cloud Robolectric coverage ends at API36. Follow `SUGGESTION_DEVICE_TEST.md` to test the isolated app without replacing production/debug installs or clearing their data.

The candidate [270M QAT GGUF](https://huggingface.co/lmstudio-community/gemma-3-270m-it-qat-GGUF) identifies Google’s QAT source, Gemma terms and llama.cpp b6153 conversion. Its published file metadata lists SHA-256 `5f4b2e17722e510122c464573b880587f4983347a40e5472b858d5a3c1ab8095`. This environment's proxy denied the Hugging Face download with HTTP403 at CONNECT, so actual bytes, access requirements, compatibility with the vendored runtime and quality could not be verified. No third catalogue entry was added; both existing entries and defaults are intact.

Bounded eviction can forget older preferences. A process kill can lose debounce-pending counts. Learning uses immediately adjacent words only; it does not model full sentences. English supplemental coverage is authored and limited; French/German use their existing corpus plus local learning. Local files retain the app's existing private-storage protection without adding encryption.

Proposed release: `release/0.6.3`, tag `v0.6.3`, version0.6.3/code11; draft notes are in `releases/0.6.3-draft.md`. No version bump, push, tag, PR, publication, Play Console change, production signing or production-data access occurred. Public release still requires actual phone results, final version/signing checks and definitive publication authorization.
