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
- 115 unit tests pass in both debug and release variants, including space/voice gestures, double-space punctuation, permission/model gating, password restrictions, cancellation/opt-out, literal insertion without Send/autocorrect, stale-editor rejection, and corrupted-model rejection.
- All three arm64 ELF libraries pass 16 KB segment alignment; the APK passes `zipalign -c -P 16 4`.
- Dynamic symbol inspection confirms `libvoice.so` exports only the four `Java_ai_boldkeys_Whisper_*` functions.
- Prediction-data and release/bundle Python checks pass. The wider 152-test Python suite needs mixed Java versions: with configured Java 17, 151 pass and the one Java 21 compiler check fails; rerunning its full 11-test module with Java 21 passes. An earlier overloaded run also hit a transient pre-existing PTY cleanup timeout; the later full Java 17 run passed those tests. No signing-tool security controls or credentials were changed.

Remaining gates:

- This cloud's enforced network policy rejects `huggingface.co` with CONNECT HTTP 403. The actual model cannot be downloaded here. Real offline recognition, accuracy, latency and native model-load/coexistence testing remain unverified; synthetic/empty upstream test weights are not a substitute.
- The API 33 Google APIs x86_64 emulator runs using software emulation because `/dev/kvm` is unavailable. First boot has not yet reached `sys.boot_completed=1`; initial install attempts failed because PackageManagerInternal was not initialized. A smaller AOSP image is being prepared to continue UI verification.
- Emulator permission-denial/grant flows, recording activation/Stop/Cancel, green light, editor lifecycle, text insertion and offline audio inference still need functional verification.
- No GitHub release, tag, production signing, merge or Play Store publication has occurred. Keep the pull request in draft until these gates pass. No phone performance or recognition-quality claim has been made.

Commands:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PtestAbi=x86_64
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease -PunsignedRelease=true
```

The debug-only VoiceTestActivity supplies ordinary and password editor fields for emulator tests and is excluded from the release manifest.
