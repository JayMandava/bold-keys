# Proposed private cloud Debug app (local scaffold)

This is opt-in: `-PboldDebug=true` adds `privateDebug`, package `ai.boldkeys.debug`,
label **Bold Keys Debug**. It has separate Android app data and can coexist with
production `ai.boldkeys`. It is debuggable; it is intended for private QA only.
Default `debug`, `release`, production signing and existing QA workflows are unchanged.
The private variant has no Gradle signing configuration and never uses a generated debug key.
It uses main application sources, without the existing `src/debug` measurement activities.

## Non-secret checks (Linux cloud, no Mac or signer)

Use JDK 17, SDK platform 36, NDK 27/CMake and the exact pinned llama.cpp submodule.
Build without any credential injection:

```sh
./gradlew --no-daemon -PunsignedRelease=true -PboldDebug=true \
  :app:testPrivateDebugUnitTest :app:lintPrivateDebug :app:assemblePrivateDebug
```

Check variant isolation both with and without opt-in using the Gradle model assertions:

```sh
./gradlew --no-daemon -PunsignedRelease=true \
  --init-script tests/private_debug_isolation.gradle :app:assertPrivateDebugIsolation
./gradlew --no-daemon -PunsignedRelease=true -PboldDebug=true \
  --init-script tests/private_debug_isolation.gradle :app:assertPrivateDebugIsolation
python3 -m unittest discover -s tests -p 'test_*.py'
```

The output `app/build/outputs/apk/privateDebug/app-privateDebug-unsigned.apk` is
unsigned. Keep it private. No install, public GitHub upload, release, tag or Drive
upload is part of this scaffold. Existing production release approval remains mandatory.

## Owner-managed PersonalVault setup (not performed)

Current tools cannot write PersonalVault. In the user-owned PersonalVault UI,
after explicit owner approval and supported secure signer handoff, create
**Environment variable** entries scoped only to the saved **bold-keys**
environment, with these exact variable names:

- `BOLD_DEBUG_KEYSTORE_BASE64`: strict single-line base64 (no whitespace) of the dedicated debug keystore, alias
  `boldkeys-debug`. This is secret key material, even when base64 encoded.
- `BOLD_DEBUG_STORE_PASSWORD`: its password, also used for the private key entry.

There is no default password or key fallback. Do not use a production key.
The cloud build helper never generates or imports keys. The separate
`tools/setup_private_debug_macos.py` is an optional owner-run one-time setup
helper: it generates only after the owner runs `create` and types its explicit
confirmation in a terminal. It has not been run with real tools here.
Generation/provisioning remain pending; prior build approval is not permission
to generate a signer autonomously. Do not paste either secret
into chat, a command argument, repo, Drive, Library, logs or an artifact.
Do not substitute GitHub secrets or another credential route for unavailable vault tools.
Vault injection is opt-in for the future signing command only; keep ordinary
build/test jobs free of these entries.

Separately obtain and independently confirm the **public SHA-256 certificate
fingerprint** for the dedicated signer. It is a required explicit public input,
`--expected-cert-sha256`; no pin is inferred from an untrusted keystore. No pin
has been provisioned by this patch. Consistent updates require the same signer
and increasing versionCode; signer replacement is not an automatic repair path.

## Future private signing command (blocked until secure setup)

After owner approval/setup, run in a trusted ephemeral Linux environment with
vault injection, shell tracing disabled, no process/environment dumps or terminal
recording, and a pre-existing private output directory. Use SDK build-tools 35.0.0
for the standalone signer (alongside the project's normal build tools):

```sh
python3 tools/build_private_debug.py \
  --expected-cert-sha256 '<owner-confirmed-public-SHA256>' \
  --output /private-output/Bold-Keys-Debug.apk
```

The helper requires both injected variables and the pin. It removes secrets
from Gradle's environment, disables its persistent daemon/configuration cache, explicitly
skips production signing properties, and builds only `privateDebug`. It checks
the app identity, decodes the injected keystore only into a temporary mode-0700
directory under `/tmp`, explicitly outside the checkout (file mode 0600), verifies alias/certificate before signing, and passes
passwords to Java tools by environment reference, never by argument value.
It suppresses tool output, verifies the finished APK's signature/pin and writes
only the APK to a mode-0600 destination, published atomically without overwrite. Temporary keystore and
candidate files are removed on success and ordinary failure. Forced termination
or host failure requires the ephemeral environment's normal disposal; do not
use a persistent/shared runner. No build log or intermediate archive is exported.

Private Drive delivery is a separate step through the already connected approved
connector, after artifact verification and authorization. It is not configured
here, and no new Drive OAuth, public artifact upload, or publication is added.
A signed APK, real signing execution, runtime coexistence and phone installation
remain untested until specifically authorized secure setup is available.
