# Bold Keys Voice Experiment 1

Experimental prerelease for phone testing, based on Bold Keys 0.6.3. Install **Bold Keys Voice Review** (`ai.boldkeys.voice.review`) alongside the production and QAT-review keyboards. This is a debug review APK, signed with the shared review key; it is not the production-signed app or a Play Store release.

In Experimental, enable on-device voice typing, grant microphone access, and explicitly download the optional 32.2 MB multilingual Whisper Tiny Q5 model. Hold space to record and release to stop, or use Voice/Stop/Cancel. A green spacebar light and Listening text show recording. Review the transcript and tap Insert; nothing is sent automatically. Autocorrect and double-space punctuation retain their existing behavior. Voice stays off by default and is disabled in password fields.

Audio remains in memory on the phone, with no online recognition fallback. The optional model download contacts Hugging Face and its delivery providers without audio or typed text. No model weights are bundled. The model and whisper.cpp use MIT licenses, included in offline credits.

## Verification and limits

The application source tested on the phone is `9d1b2e4cef387c2be587ee422e83dabb82f4f075`, including readiness fix `8f0dc63`. The release-source commit adds documentation only; application/native sources are unchanged.

- Debug and release builds/lint passed, with 116 unit tests in each variant and additional readiness-transition checks.
- The AOSP Android 13 emulator passed permission denial/retry/grant, missing-model gating, typing/double-space, password controls, and recorder/green-light/Stop/Cancel/hold-release checks. Recorder checks used an invalid local marker, not real Whisper weights. Software emulation produced startup System UI ANRs; these tests establish behavior, not speed.
- Phone checks verified the actual model download and SHA-256, acoustic synthetic-speech transcription with airplane mode on and Wi-Fi off, preview/Insert, permissions, typing, cancellation and editor protections. The final readiness retest passed for missing-model, newly-ready and password-to-normal transitions. The tester restored the original QAT keyboard/data afterward.
- Recognition accuracy was imperfect. Human speech, latency and simultaneous QAT/Whisper loading remain untested. Actual Whisper inference in the cloud remains unverified because its network policy blocks the model host with HTTP 403; that denial was not bypassed. Phone checks used a matching-source review build, not this separately packaged cloud APK.

These combined checks support an opt-in experimental phone-testing prerelease. They do not establish production readiness or general accuracy/performance.

## Install and verify

Requires Android 13 or newer on an arm64 phone. APK: `bold-keys-voice-experiment-1-arm64.apk`; checksum: `SHA256SUMS-voice-experiment-1.txt`; source/build/signing evidence: `voice-experiment-1-provenance.json`.

Android version is `0.6.3-voice-experimental`, code 11. Select **Bold Keys Voice Review** in Android keyboard settings. It uses a separate package and does not overwrite the production or QAT-review installations.

Shared review certificate SHA-256:
`15d7d298c471711285b81c20ea7069567b00e3b486c8d3e43c643fe6b90cda37`.

The existing production latest release remains unchanged. No main-branch merge, production signing, Play upload or Play Console change is part of this prerelease.
