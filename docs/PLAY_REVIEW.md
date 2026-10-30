# Google Play preparation review — 0.6.2

This is a local review candidate, not an approved release. No upload, publication, signing-key migration or Play enrollment is part of this change.

## Source and scope

Base: public `main` commit `cde20679729fce28cbc1ca90ec42045c841945fa`, fetched on 2026-10-30. Public v0.6.1 is `d80866e4b7bcd5c2a298c62a7ae081a5caa32aae`. The two intervening public commits add protected release tooling and fix its SDK-manager path; they do not change production Kotlin/native application sources. This keeps their fail-closed signing behavior rather than reintroducing v0.6.1's debug-key fallback. The private debug branch and its colour features were not used. Existing public black/white themes and four backlight colours are retained.

Candidate: package `ai.boldkeys`, versionName `0.6.2`, versionCode `10`, min SDK 33, compile/target SDK 36, arm64-v8a only. The version increment distinguishes this candidate from production code 9. Changes add the policy button in About, clarify the network description, update the APK verifier's target expectation, and exercise unsigned bundle generation in the signing regression checks.

## Signing and reproduction

The existing build requires production signing properties from `BOLD_KEYSTORE_PROPERTIES` or `~/.bold-keys-keystore/keystore.properties`. Missing, malformed, incomplete and debug-alias inputs fail release artifact tasks. Debug builds, compilation, tests and lint remain usable without production credentials. `-PunsignedRelease=true` explicitly produces unsigned output and skips signing-property reads. Do not use a debug key or create a replacement production key.

```
./gradlew --no-daemon --max-workers=4 -PunsignedRelease=true testReleaseUnitTest lintRelease bundleRelease assembleRelease
python3 -m unittest discover -s tests -p 'test_release_workflow.py' -v
python3 tests/test_release_signing_modes.py --logs /absolute/path/to/signing-checks
# With JAVA_HOME/PATH selecting JDK 21, also run the API 33 + 36 matrix:
./gradlew --no-daemon --max-workers=4 -PunsignedRelease=true -ProbolectricSdks=33,36 testReleaseUnitTest
```

The three existing keyboard test classes cover SDKs 33 and 36. Gradle defaults to SDK 33 so existing JDK 17 CI remains supported; explicitly select both SDKs with JDK 21 for Android 16 Robolectric validation. The test JVM opens `java.base/jdk.internal.access` for Robolectric's API 36 FileDescriptor bridge, following https://robolectric.org/getting-started/ . This is test-only JVM configuration. Robolectric results do not replace physical-device tests.

The AAB output is `app/build/outputs/bundle/release/app-release.aab`. Until signed and independently verified with the existing production identity, it is **unsigned and not upload-ready**. The protected GitHub release workflow currently signs/publishes APKs only; it does not yet provide an approved AAB-signing pipeline. This review does not dispatch or expand that workflow.

The publicly downloaded v0.6.1 APK independently verified with Android build-tools 36.0.0 apksigner: one signer, v2 signature valid, certificate SHA-256 `b237e1aec0bfdae579caebe1a0d97517af1b7c066adc4b3d197b641adfa46c34`, RSA 4096. APK SHA-256 `f4cda8c04f73807561f9e3c139c9c261a0dc9768f5ec1cccaa8d61b8a4af8322` matches the public asset digest. Only public certificate metadata was inspected.

## Validation observed on 2026-10-30

- Release keyboard tests: 26 tests on API 33 with JDK 17; API 33 + 36 matrix: 52 tests with Temurin JDK 21.0.13+9, all passed after configuring the documented test JVM module opening.
- 17 release-workflow Python tests passed. 13 signing/build functional checks passed, including missing-signing APK/AAB failures, malformed/incomplete/debug-alias rejection, original signing-config selection, explicit unsigned APK/AAB ZIP/signature checks, and inotify proof that unsigned mode never opens the supplied signing properties.
- Release lint: 0 errors, 30 warnings and 3 hints. Remaining warnings include inherited accessibility `performClick` gaps, backup/data-extraction rules, arm64-only ChromeOS compatibility, drawing allocations and context/storage issues; they were not suppressed. No device UI result is claimed.
- Bundletool 1.18.3 validates the unsigned AAB; bundle configuration specifies PAGE_ALIGNMENT_16K. APK metadata/signature checks and 16 KB ZIP alignment pass; both shipped ELF libraries have 16 KB LOAD alignment.
- Earlier API 36 attempts stopped at test-runtime setup (JDK 17 unsupported, system Java 21 installation lacking a compiler, then missing JDK module access). These were resolved with a complete digest-verified Temurin JDK 21 and the test-only module opening; final 52-test run passed.

## Android 16 review

Google's current target policy requires API 36 for new phone apps and updates from 2026-08-31: https://developer.android.com/google/play/requirements/target-sdk . Review both https://developer.android.com/about/versions/16/behavior-changes-16 and https://developer.android.com/about/versions/16/behavior-changes-all .

- SetupActivity already calls `enableEdgeToEdge`; its Scaffold padding is consumed by a scrolling list with IME padding. BoldKeysService applies system/navigation/tappable-element insets to the keyboard. There is no edge-to-edge opt-out.
- Settings navigation uses AndroidX Compose `BackHandler`, with no app override of legacy `onBackPressed` or `KEYCODE_BACK` interception. Device predictive-back and keyboard-dismiss behavior still need testing.
- No fixed orientation, aspect ratio or resizability restriction is declared. Large screen, rotation, split screen, gesture/three-button navigation and large font behavior remain device checks.
- Downloads use a worker Thread, not JobScheduler/WorkManager/DownloadManager; no fixed-rate scheduling or priority-dependent ordered broadcast is present in app sources. This does not guarantee long background downloads survive process termination.
- Native build uses NDK 27 flexible-page-size support, static C++ runtime, AGP 8.13.2 and arm64. Validate actual ELF load alignment and generated APK ZIP alignment rather than relying on build flags. Hardware smoke tests must cover Android 13 and 16, including a 16 KB page device.
- The native build uses `-march=armv8.2-a+dotprod`. This is an inherited hardware restriction beyond the advertised arm64 ABI; older arm64 CPUs may lack required instructions. Do not claim support for every arm64 device without testing or a separately reviewed CPU compatibility change.

## Data flow and Data Safety evidence

This is a factual technical inventory, not a submitted Data Safety declaration or legal export classification. Evidence: `app/src/main/AndroidManifest.xml`, the release merged manifest, `BoldKeysService.kt`, `SpellEngine.kt`, `Prefs.kt`, `ContextModels.kt`, `Shortcuts.kt`, `ContextLm.kt`, `app/src/main/cpp/{CMakeLists.txt,contextlm.cpp}`, and the resolved `releaseRuntimeClasspath` report.

| Path | Observed behavior | Limits / owner review |
|---|---|---|
| Typed text | InputConnection text/cursor context is processed locally by dictionary and optional JNI inference. No app text-upload code or remote inference endpoint found. Model inference is off by default. | Source inspection is not network capture or proof about the user's host apps/OS. Sensitive-field behavior depends on editor metadata. |
| Learned words | Per-language word/count TSV files in app-private filesDir; UI supports deletion/clear. Preferences, emoji recents, shortcut text/links and model metadata use private SharedPreferences. | Plain local storage, not app-level encrypted files. `allowBackup=false`; device/platform transfer behavior should still be checked. No explicit `IME_FLAG_NO_PERSONALIZED_LEARNING` handling found; owner should review incognito-editor expectations separately. |
| Model download | User-tapped HTTPS GET to fixed Hugging Face catalogue URLs, possible HTTP redirects/CDN delivery, default metered-network guard; pinned SHA-256 and size checks before keeping the file. No request body, typing, identifier, auth token or analytics payload added by app code. | Servers can observe IP address, request time, model path and normal HTTP headers. Third-party retention/CDN redirects and policy classification need owner confirmation; no live capture performed. |
| Model import | System OpenDocument picker, copy selected GGUF into private model directory, local inference. No model bundled. | The chosen document provider may be cloud-backed. Imported models have user-selected licenses and trust requirements. |
| External actions | Speed key launches installed apps and user-configured URL/tel/mailto/geo intents; snippets commit locally into the host editor. Privacy button opens the exact public HTTPS policy URL. | Receiving app/browser may transmit URL contents and metadata under its own behavior. User-configured shortcut values can themselves contain sensitive text. |
| SDKs | Runtime dependency graph contains AndroidX Compose/Material/Activity/Core/Lifecycle/Startup/ProfileInstaller/Emoji2 and Kotlin/coroutines. No analytics, ads, billing, Firebase, Sentry or crash-upload SDK found. ProfileInstaller is local ART optimization; Startup is component initialization. | EmojiCompat 1.4.0 auto initializer resolves a system font provider and requests `emojicompat-emoji-font` in a background loader. Provider-side caching/download/telemetry depends on device. Do not assert all transitive behavior is network-free. |
| Permissions | INTERNET, ACCESS_NETWORK_STATE, VIBRATE and AndroidX signature-level internal receiver permission. Launcher queries; no contacts, microphone, location, broad file access or advertising-ID permission. | Exported input-method service is protected by BIND_INPUT_METHOD. SetupActivity is exported launcher. Startup provider is not exported. Profile installer receiver is exported under DUMP permission. Debug evaluation activities are absent from release manifest. |

Emoji provider finding was confirmed against Google's published `androidx.emoji2:emoji2:1.4.0` source JAR (`EmojiCompatInitializer` and `DefaultEmojiCompatConfig`), not just its package name. The provider is selected from installed system providers. This review does not claim the provider necessarily makes a network request.

## Native and encryption inventory

Pinned llama.cpp submodule: `748d4225b9016b17ce4bcfa69fdc2c39f473a965`. The APK contains exactly arm64 `libcontextlm.so` (JNI inference with static llama/ggml CPU libraries) and `libandroidx.graphics.path.so` (transitive AndroidX graphics path support); `LLAMA_CURL`, common, examples, tools and server are disabled, as are OpenMP and llamafile. The final link command links llama, ggml, ggml-cpu, ggml-base and Android/system log/math/dl/atomic support. An `LLAMA_OPENSSL=ON` cache entry alone is not evidence that OpenSSL is linked: inspect the final ELF/import evidence. The separate vendor hash target includes SHA-1/SHA-256/xxHash sources, but it is absent from the final app link command. Both shipped ELF files have 0x4000 (16 KB) LOAD alignment. Their dynamic NEEDED entries are Android/system libraries (contextlm: log, android, m, dl, c; graphics path: m, dl, c). No OpenSSL/libcrypto/libcurl dependency or crypto/socket/connect symbol was found in the exported/imported symbol inventory. This is a bounded binary/source inspection, not a formal audit of every instruction.

App Java uses the platform HTTPS/TLS implementation for downloads, and `MessageDigest("SHA-256")` for integrity. No custom encryption, VPN, encrypted messaging or cryptographic key-management feature was found in app code. App private data is not explicitly encrypted by this app. Normal Android device encryption is platform behavior. The imported native inference library is computational/model parsing code, not itself evidence of an encryption product. Owner/legal review must determine export declarations, distribution territories and any applicable filings; no legal compliance conclusion is made here.

## Privacy alignment and licenses

Policy URL: https://jay-mandava-dev.web.app/privacy.html . The delegating conversation verified a Bold Keys section naming Jay Mandava and describing local typing/learned words/preferences and optional Hugging Face requests. This runtime's browser fetch was unavailable, so page content was not independently re-read here. Owner should confirm the latest page also accurately covers shortcut/browser handoffs, cloud-backed document providers and conditional AndroidX emoji-font-provider behavior. About now describes the latter and links to the policy. README's broad “only network use” sentence predates this dependency finding and should be reviewed with the policy.

Existing Apache-2.0 project LICENSE and NOTICE list third-party assets: FrequencyWords English/French/German (CC BY-SA 4.0), Tatoeba French/German pair data (CC BY 2.0 FR), Norvig n-grams, Unicode emoji data (Unicode License v3), Nunito (SIL OFL 1.1), llama.cpp (MIT). Before store distribution, confirm complete attribution, modification/source and share-alike obligations for derived datasets, Norvig data provenance/terms, and bundled font/Unicode license-text availability. Existing About credits are abbreviated; an Apache project license does not replace data/font licenses. Optional Gemma downloads remain subject to Gemma terms, and imported models keep their own terms. This is an obligations/provenance review list, not a finding that all licensing requirements have been met.

## Genuine screenshots and remaining decisions

No device or emulator is available in this runtime, so no genuine production screenshots or interactive Android 16 results are claimed. Capture from an existing production-signed v0.6.1 install for current feature marketing, or the reviewed 0.6.2 production-signed candidate after owner-controlled signing. Use synthetic text, show only the existing public themes, and label version-specific differences. Suggested captures: public keyboard with suggestions/trackpad, emoji picker, Settings home and new About policy button. Debug evaluation screens and private colour themes must not appear.

Owner still needs: existing-key AAB signing and certificate verification; confirmation of Play App Signing/upload-key state without enrollment changes; device QA including browser button/TalkBack and API36 behavior; privacy/Data Safety answers including third-party download metadata and emoji-provider uncertainty; licensing review; store listing/screenshots/content rating/export decisions. No merge, push, PR, publish or Play upload is authorized by this review.
