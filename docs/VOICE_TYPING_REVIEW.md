# Experimental on-device voice typing

This branch adds an optional Whisper CPU voice-typing experiment. It is off by default in Experimental. Enabling requests Android microphone permission. Downloading the model is a separate explicit action. No audio or editor text is passed to networking code; there is no online recognition service or fallback.

## User flow

Enable Experimental → On-device voice typing, grant Microphone, and download the model. In an ordinary text field, hold space for 450 ms to record, then release to stop. A green light around the spacebar and a textual Listening indicator show when the recorder is active. Voice and Stop buttons provide an alternative to holding a key. Cancel discards capture or pending output. Review the transcript, then tap Insert to commit literal text; the pipeline never invokes Send or autocorrect. Keyboard language selects English, French or German recognition. Recordings are limited to 30 seconds and held in memory only.

Quick space taps still type one space. When another finger types before the space is released, the waiting space is committed first. With voice disabled or unavailable, space retains its original finger-down behavior. Double-space punctuation and autocorrect retain their existing service paths.

Voice is disabled in password and other sensitive fields. Editor restarts, keyboard dismissal, selection changes, typing, suggestion selection and trackpad movement discard pending voice work. A connection and surrounding-text snapshot is checked before showing and inserting delayed output. Revoked microphone access fails closed. Android app settings are linked after denial for users whose permission prompt no longer appears.

## Dependencies and model provenance

- whisper.cpp v1.8.2, pinned submodule commit `4979e04f5dcaccb36057e059bbaed8a2f5288315`, MIT.
- Whisper Tiny multilingual Q5_1, 32,152,673 bytes, derived from OpenAI Whisper (MIT).
- Model source: https://huggingface.co/ggerganov/whisper.cpp/blob/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-tiny-q5_1.bin
- SHA-256: `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7`.
- The downloader checks the entire SHA-256 before atomically installing. Inference verifies it again before opening the native model. Failed or canceled partial downloads are removed. The model lives in private no-backup storage; deleting it is available in Experimental.
- No weights are bundled in the APK. The model download sends ordinary request metadata to Hugging Face and its HTTPS delivery providers.
- Full upstream license notices are included in offline Licenses & credits.

Whisper and the existing llama.cpp each statically link their own ggml in separate CMake builds. `libvoice.so` exports only its four JNI functions; all bundled Whisper/ggml/C++ symbols are local. The production ABI remains arm64-v8a; `-PtestAbi=x86_64` supports cloud-emulator verification. ARM optimization flags on the existing model are retained. Both native libraries use the existing 16 KB alignment configuration.

## Verification in this cloud

Build and static/unit checks completed on 2026-11-06:

- Debug x86_64 APK builds; debug lint passes.
- Unsigned arm64 release APK builds; release lint passes.
- 116 unit tests pass in both debug and release variants, including space/voice gestures, double-space punctuation, permission/model gating, password restrictions, cancellation/opt-out, literal insertion without Send/autocorrect, stale-editor rejection, and corrupted-model rejection.
- All three arm64 ELF libraries pass 16 KB segment alignment; the APK passes `zipalign -c -P 16 4`.
- Dynamic symbol inspection confirms `libvoice.so` exports only the four `Java_ai_boldkeys_Whisper_*` functions.
- Prediction-data and release/bundle Python checks pass. The wider 152-test Python suite needs mixed Java versions: with configured Java 17, 151 pass and the one Java 21 compiler check fails; rerunning its full 11-test module with Java 21 passes. An earlier overloaded run also hit a transient pre-existing PTY cleanup timeout; the later full Java 17 run passed those tests. No signing-tool security controls or credentials were changed.

Emulator functional verification (AOSP API 33 x86_64, `emulator-5556`):

- Boot completed; APK installation and Settings navigation work. Initial Google-image boot/installation problems were resolved by using the smaller AOSP image. Software emulation has no `/dev/kvm` and produced System UI ANRs during startup; these checks establish behavior, not performance.
- Experimental is off by default. Enabling shows Android's microphone prompt. Denial keeps `voiceTyping=false`; retry/grant sets it true. These states were checked against Android's runtime-permission state and the app's preferences.
- Without a model, Voice is disabled and normal typing works. Real touchscreen taps within the 700 ms window produce double-space punctuation. `tools/voice/TouchDriver.java` injects taps in one shell VM so separate `adb input` startup time does not exceed that window.
- Password fields hide the voice controls. Editor changes during recorder startup discard the pending capture.
- A deliberately invalid, private marker file was temporarily used to exercise recorder controls independently of model availability. This is **not a Whisper model** and does not pass SHA verification. Android AudioRecord start/stop metadata, Listening text, the green spacebar light, Cancel, explicit Stop, hold-space activation/release, and silence handling were observed. No audio was uploaded, no real model was loaded, and the marker was deleted after the checks.
- The readiness hint issue reported by the phone tester was reproduced and fixed in `8f0dc63`. The corrected APK was checked for newly-ready state, model-missing state, and password-to-normal return. The unit regression exercises permission/model changes and the password transition in both debug and release variants.

Separate device evidence supplied by the phone-testing task: at `84df665`, model download/SHA verification and acoustic synthetic-speech transcription worked with airplane mode on and Wi-Fi off. That task also reports hold-space/glow, Stop/Cancel, preview/Insert, permissions, password/editor protection, spaces, double-space and autocorrect passing. Accuracy was imperfect; human speech, latency and simultaneous QAT/Whisper loading were not tested. The readiness fix at `8f0dc63` needs that task's final phone recheck. This report does not treat those phone results as cloud inference results.

Remaining cloud/release gates:

- The cloud's enforced network policy rejects `huggingface.co` with CONNECT HTTP 403. Actual cloud model loading, offline audio inference and preview/Insert from real model output remain unverified. Empty upstream weights or the corrupt recorder marker are not substitutes.
- No GitHub release, tag, production signing, merge or Play Store publication has occurred. Keep the PR in draft and coordinate the final phone/cloud evidence before release. No phone latency or general recognition-quality claim has been made.

Commands:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PtestAbi=x86_64
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease -PunsignedRelease=true
```

The debug-only VoiceTestActivity supplies ordinary and password editor fields for emulator tests and is excluded from the release manifest.

## Isolated phone-review package

Build `:app:assembleDebug -PvoiceReviewBuild=true -PunsignedRelease=true` with the default arm64 ABI. The APK is `app/build/outputs/apk/debug/app-debug.apk`, package `ai.boldkeys.voice.review`, label **Bold Keys Voice Review**, version `0.6.3-voice-experimental` (code 11). This is separate from production `ai.boldkeys` and the QAT/suggestion review package `ai.boldkeys.suggestions.review`. Do not combine the two review flags.

This variant uses Gradle's normal debug signing configuration; inspect the actual APK's public certificate fingerprint before installation and preserve that debug key for subsequent review updates. It does not use or fall back to the production release key. `unsignedRelease=true` avoids reading production signing properties while building the debug candidate and does not disable debug signing. The cloud's default-package debug APK must not be used to replace a production-signed installation.


## Smallest network authorization step

The exact model URL is `https://huggingface.co/ggerganov/whisper.cpp/resolve/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-tiny-q5_1.bin`. The first CONNECT to **huggingface.co** is denied, so this runtime cannot observe which delivery hostname this file currently redirects to. Do not guess that a particular CDN is the actual selected hop.

The managed environment supports a custom `allowed_hosts` list, but this task exposes only read-only environment/status/diagnostic tools. Changes must use the supported environment configuration workflow and its user review. The smallest next step is to authorize **huggingface.co** in that configuration, confirm the applied policy is enforced, and inspect the pinned URL's HEAD response **without following redirects**. Then authorize only the actual delivery hostname returned and repeat if another hop is required. Editing `/etc/codex/network-policy.json`, bypassing the proxy, fetching through another relay, or disabling TLS verification is not a solution.

Hugging Face's current [download/network documentation](https://huggingface.co/docs/hub/models-downloading#downloading-behind-a-proxy-or-firewall) lists HTTPS delivery infrastructure including `cas-server.xethub.hf.co`, `cas-server.xethub-eu.hf.co`, `transfer.xethub.hf.co`, `transfer.xethub-eu.hf.co`, `us.aws.cdn.hf.co`, `us.gcp.cdn.hf.co`, `cdn-lfs-us-1.hf.co`, and `cdn-lfs-eu-1.hf.co`. Its [machine-readable domain catalogue](https://huggingface.co/.well-known/meta.json) includes additional regional CDN names. These are provider-published possibilities, **not a verified per-model redirect chain**, and do not justify granting all of them by default. The app uses a direct HTTPS file download, not the hf_xet client APIs.

The configured shared review identity is alias `boldkeys-debug`, certificate SHA-256 `15d7d298c471711285b81c20ea7069567b00e3b486c8d3e43c643fe6b90cda37`. The cloud's default Gradle debug certificate is different (`7ebbc11c8179258db079ea51c8640984f21e83e9778d125051b28fce9686d3c4`). The phone-testing workflow must preserve/verify its existing QAT review identity for update continuity; do not install an APK signed with an unrelated key over an existing review install. Only public certificate identities were inspected; no signing material was published or changed.
