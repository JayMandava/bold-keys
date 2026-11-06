# Voice Experiment 1 — experimental phone-testing prerelease

Optional, on-device voice typing is available under Experimental, off by default. Enable it, grant microphone permission and download the separate 32.2 MB Whisper Tiny model. Audio stays on device; there is no online recognition fallback.

Hold Space to speak. Release to transcribe and insert directly into the same eligible editor. A green spacebar glow shows recording; slow flashes show transcription. There is no voice panel, preview or Insert/Cancel button. Slide off Space to discard. Typing, editor changes, cursor movement and keyboard dismissal discard pending output. Password fields are excluded; dictation never presses Send. Autocorrect and double-space punctuation retain their existing behavior.

Install **bold-keys-voice-experiment-1-arm64.apk** on an Android 13+ ARM64 phone. This is the isolated **Bold Keys Voice Review** package (`ai.boldkeys.voice.review`), version `0.6.3-voice-experimental` / code 11, a debuggable experimental build signed with the shared review key, not the production key. Enable/select it as a keyboard and open Experimental settings. It can coexist with the production keyboard; model weights are not bundled. SHA256SUMS and provenance accompany the APK.

The application source tested on phone and built for this APK is `56f064b5c86fe2acfff3f342bd4951352b6076c6`; the release commit adds verification/release documentation only. Debug/release builds, lint and 124 unit tests per variant passed. Cloud API 33 emulator checks passed for gestures, cancellation, light/UI and typing regressions. The connected OnePlus phone passed offline acoustic synthetic-speech automatic insertion, processing flashes, cancellation/editor safety, password protection and typing regressions. Earlier phone tests verified model download/hash.

**Known limits:** synthetic-speech recognition was inaccurate. Human speech, multilingual quality, formal performance and simultaneous QAT/Whisper loading are unverified. Cloud model access returned HTTP 403, so real inference was verified on the phone, not the emulator. An external-app editor recording check in cloud was inconclusive. GitHub has no automatic branch/PR CI; the build/lint/test results are from this cloud environment. This prerelease is for further phone testing. Stable v0.6.3 remains latest; there is no main merge or Play publication.

See [verification details](../VOICE_TYPING_REVIEW.md) for model pin, licenses, safety and coverage.
