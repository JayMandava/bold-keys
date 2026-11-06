# Experimental on-device voice typing

## Current interaction: hold, speak, release, insert

The user superseded the earlier preview flow. There is **no transcript preview, Insert/Cancel button, status row, toast, or other added keyboard voice UI**. Hold space for 450 ms to record. The spacebar backlight glows green while recording; release stops the recorder, slowly pulses the light during local transcription, and commits the result directly into the same eligible editor. Only needed word-boundary spaces are added around recognized text; selection replacements remain literal. Transcription never invokes Send or autocorrect.

Slide off space before lifting to discard. Touch cancellation, ordinary typing, selection movement, editor changes/restarts, keyboard dismissal and opting out discard pending work. A generation token and input-connection/surrounding-text snapshot guard delayed output; stale or duplicate results cannot write. Empty/error results insert nothing. Recordings are bounded to 30 seconds and audio remains in memory only.

The existing keyboard geometry and its two original views (suggestion bar and keyboard) remain. Quick space taps and double-space punctuation retain their service paths. With voice off/unavailable, space keeps its original finger-down behavior. Other typing and autocorrect are unchanged.

Screen readers can start dictation using Space's long-click action and stop by activating Space. Recording/transcribing state is exposed on that same key without announcing recognized text. A slow 1.6-second pulse avoids rapid flashing; with system animations disabled, a dim steady light and the nonvisual state provide feedback. Android's microphone privacy indicators and permission UI remain intact.

## Opt-in and privacy

Experimental voice is off by default. Enabling requests microphone permission; downloading the optional 32.2 MB model is a separate explicit action. Password/non-text fields cannot activate voice. English, French or German recognition follows keyboard language. No audio/editor data enters networking code and there is no online recognition fallback. The optional HTTPS model download reveals ordinary request metadata to Hugging Face and its delivery providers.

## Dependencies

- whisper.cpp v1.8.2, pinned submodule `4979e04f5dcaccb36057e059bbaed8a2f5288315`, MIT.
- OpenAI Whisper Tiny multilingual Q5_1, 32,152,673 bytes, MIT; no weights bundled.
- [Pinned model](https://huggingface.co/ggerganov/whisper.cpp/blob/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-tiny-q5_1.bin), SHA-256 `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7`.
- Download verifies SHA-256 before atomic installation in private no-backup storage; inference verifies it again. Cancelled/failed partial files are removed. Settings can delete the model. Offline license notices are included.
- Whisper statically links its own ggml in a separate CMake build and exports only four JNI functions; the existing llama implementation stays separate. Native libraries retain 16 KB alignment and production arm64 ABI; `testAbi=x86_64` supports emulator builds.

## Verification status

The revised interaction has passed debug x86_64 and unsigned release arm64 builds/lint, with 124 unit tests in each variant. The rendered spacebar pulse/reduced-motion regression was additionally rechecked in both variants. Tests cover direct literal insertion without Send/autocorrect, duplicate/stale result rejection, permission/password policy, editor/cursor/typing/dismissal cancellation, touch/hold/release/slide-off, no extra controls, slow pulse/reduced motion, and nonvisual Space actions. AOSP API 33 emulator confirmation passed for no added keyboard UI, steady hold light, release, slide-off discard, OS touch cancellation, hardware-typing interruption and quick/double-space punctuation. These recorder checks used a deliberately invalid marker, not a valid model; it was removed and AudioService showed no active recorder afterward. The connected OnePlus phone retest at `56f064b5` passed actual offline acoustic synthetic-speech automatic insertion, hold/release with no extra voice panel, visible processing flashes that stop, slide-off cancellation, typing/editor-change discard, password protection, and unchanged autocorrect/double-space. Original QAT keyboard/data were restored afterward. Earlier phone tests also verified the optional model download and SHA-256. Synthetic-speech accuracy was imperfect; human speech, multilingual quality, formal performance and simultaneous QAT/Whisper loading remain unverified. The external-app editor recording check in cloud was inconclusive and is not counted as passed.

Cloud model access is still denied with CONNECT HTTP 403. There is no cloud inference claim and no network bypass. The user selected the standard protected production APK release path for version 0.6.4/code 12. The empty experimental draft must remain unpublished. PR review/merge authorization and the owner-only protected signing approval remain required; no merge, production signing or Play publication has occurred. The old signed preview candidate was removed; only the revised direct-insert candidate may be published. GitHub has no automatic branch/PR CI configured: the exact tested head has zero check runs/status contexts; validation was run in this managed cloud environment.

## Isolated phone review

Use `:app:assembleDebug -PvoiceReviewBuild=true -PunsignedRelease=true` with default arm64 ABI. Output: `app/build/outputs/apk/debug/app-debug.apk`; package `ai.boldkeys.voice.review`; label **Bold Keys Voice Review**; version `0.6.4-voice-experimental` / code 12. It is separate from production and QAT review. Do not combine the two review flags.

The shared review key is alias `boldkeys-debug`, certificate SHA-256 `15d7d298c471711285b81c20ea7069567b00e3b486c8d3e43c643fe6b90cda37`. The cloud's default Gradle debug key differs (`7ebbc11c8179258db079ea51c8640984f21e83e9778d125051b28fce9686d3c4`); preserve/verify the shared identity before a phone update. No production key is used.

## Network authorization

The exact model download begins at `huggingface.co/ggerganov/whisper.cpp/resolve/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-tiny-q5_1.bin`. The first CONNECT is denied, so its actual delivery redirect is unobservable here. The environment supports custom allowed hosts but this task has read-only configuration tools. The smallest supported step is user-reviewed authorization of `huggingface.co`, then HEAD-only inspection without following redirects, followed by authorization of only the selected delivery host if needed. Do not edit the policy file, bypass the proxy or use a relay.

Hugging Face's [current network documentation](https://huggingface.co/docs/hub/models-downloading#downloading-behind-a-proxy-or-firewall) lists `cas-server.xethub.hf.co`, `cas-server.xethub-eu.hf.co`, `transfer.xethub.hf.co`, `transfer.xethub-eu.hf.co`, `us.aws.cdn.hf.co`, `us.gcp.cdn.hf.co`, `cdn-lfs-us-1.hf.co` and `cdn-lfs-eu-1.hf.co`. Its [domain catalogue](https://huggingface.co/.well-known/meta.json) includes regional alternatives. These are possibilities, not a verified model-specific chain, and do not warrant a blanket grant.

## Standard 0.6.4 production candidate

The user requires the existing Protected Android release workflow unchanged. Version 0.6.4/code 12 was prepared on the feature PR. Secret-free local production checks passed: 17 release-workflow tests, 15 pinned-data tests, 124 release unit tests, release lint and unsigned ARM64 assembly. Local build-tools 35 checks passed package `ai.boldkeys`, version/code, non-debuggable and unsigned status, ZIP/ELF 16 KB alignment and pinned-data integrity. The unchanged hosted workflow uses build-tools 36 and repeats its own exact-artifact verification. This local APK is not signed or published. The empty experimental draft is superseded, remains unpublished and must not be used.

Next: review and explicitly authorize merge of PR #18 to main, then dispatch the existing workflow with `operation=publish`, `version=0.6.4` and the exact current-main SHA. After secret-free validation, JayMandava must approve the `production-release` environment deployment; agents cannot approve it. No main merge or workflow dispatch has occurred during preparation.
