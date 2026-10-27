# Cloud colour UI gates

The feature-only `Android colour UI QA` workflow validates the real Android IME,
not just the Appearance preview. It runs only on `feat/iconic-colours` in the
public upstream repository, with read-only repository permission, no production
environment or signing secrets, a 45-minute job limit, and one-day artifacts.
It does not publish or trigger the protected release workflow.

## Prerequisites and fail-closed behaviour

The hosted runner must already have effective read/write access to `/dev/kvm`.
The workflow records the user and device permissions and stops if that is not
true. It never changes ACLs, groups, udev rules or security policy, and never
runs the emulator as root. SDK installation uses official packages and already
accepted licenses; stdin is closed rather than accepting new agreements.

The API 35 Google APIs x86_64 image must report ARM64 translation so that the
normal ARM64 APK can run unchanged. Boot has a ten-minute bound. A failed boot
or absent capability is a blocked runtime gate, not an application test pass.

## Coverage and evidence

The build stage runs both JVM variants, both lint variants, release tooling
tests, an unsigned release build, and debug/test APK builds. The instrumentation
uses a debug-only synthetic editor plus the real setup screen. It verifies:

- All five explicit palettes through labelled choices, accessible selected
  state, reopening settings and applying the choice to both real IME surfaces.
- Real key touches, Shift, delete, cursor movement and Shift-trackpad selection;
  repeated symbol/emoji opening and dismissal.
- Portrait and landscape, explicit colours under light/dark system appearance,
  Match phone, larger text, and independent backlight preferences.
- Text, search, email, URL, password and numeric fields, including the existing
  private-field suggestion rules and URL/email/password no-replacement behaviour.

`palette-*-portrait-real-app.png` shows the real setup screen and connected IME.
`qa-*` images use the clearly labelled synthetic test host. Neither contains
user data. PNGs, view hierarchies, per-test result JSON, failure evidence, device
properties, source SHA and APK hashes are retained together. Optional raw MP4
recordings must be decoded and visually reviewed before use; a passing UI test
does not certify successful video encoding.

The debug test activity is excluded from the release APK. Successful debug
instrumentation establishes runtime behaviour for the shared production code,
but does not replace production certificate, fresh-download or protected
release-workflow verification.

## Software-emulator fallback

For a cloud image without ARM translation, the explicit
`-PqaX86Debug=true` option builds **only the debug variant** for x86_64, including
the same native sources (no feature stubs). Debug defaults and all release
builds remain ARM64. ARM compiler flags stay scoped to ARM builds; the test-only
x86 build does not optimize for the cloud host's CPU. Invalid option values
fail closed. Signing-mode functional checks verify both configurations and
byte-identical release output with the option enabled or disabled.

For example, build `assembleDebug assembleDebugAndroidTest` with
`-PunsignedRelease=true -PqaX86Debug=true`, then run the same instrumentation on
the dedicated emulator. Label this evidence with its actual API and x86_64
debug ABI. It verifies real keyboard UI/input behaviour, not ARM64 execution,
production signing, physical-device timing or AI-model performance. The primary
GitHub workflow still uses the normal ARM64 APK and requires translation.

### Independent hosted software runner

`Android software colour UI QA` runs only on the separate
`qa/iconic-colours-software` branch. It uses the standard, fully rendered AOSP
API 33 image (not ATD), the explicit x86 debug variant, and `-accel off`. It does
not access a hardware-virtualization device, change permissions or run as root.
The guest adb identity must be the ordinary shell UID 2000. The failed primary
KVM run is preserved rather than retried with weakened access controls.

This ordinary public runner has read-only repository permission, no secrets or
production environment, a 45-minute job cap and one-day artifacts. SDK install
stdin is closed. Zero-only image extents may be made sparse to meet disk-space
requirements, but full length and SHA-256 must remain identical before boot.
The same real IME tests and labelled captures run after debug unit/lint/build
checks. This supplements—not replaces—the separately validated ARM64 release,
release unit/lint/signing gates and final visual review. It makes no ARM64,
physical-device timing or AI-performance claim.

Cold software startup must reach a stable, visible launcher, not merely set the
boot-completed property. Within the existing twenty-minute startup budget, a
maximum ten-minute readiness check preserves system errors and actual screen
evidence. It may press **Wait once**, only when both window focus and the exact
hierarchy identify the System UI startup ANR dialog. A second dialog fails
closed. The launcher must remain focused for thirty seconds and no new system
ANR may occur for sixty seconds before product tests start. No service,
watchdog, permission or security setting is disabled. A recovered startup is
recorded explicitly; it is not a product test pass.

The explicit `softwareEmulator=true` instrumentation argument permits only the
first real Settings launch up to 120 seconds for cold Compose initialization.
It still requires the focused, package-scoped, accessible Home UI; subsequent
launches and all functional assertions retain their normal bounds. Evidence
copies use separate commands because Android's UiAutomation does not interpret
shell operators. Optional recording uses a fixed-path script invoked through
the normal shell, and its output still requires independent decoding/review.

### One explicitly approved accelerated job (2026-10-27)

The separate `One approved accelerated colour QA` workflow is restricted to the
initial creation push of `qa/iconic-colours-kvm-20261027`, the public upstream
repository, its owner actor, and run attempt 1. It has no manual or periodic
trigger; later pushes and reruns cannot consume the one-job permission. A
fail-closed public workflow-history check also requires exactly this one run
before granting access. Never delete/recreate this branch or remove its run
history to reuse the approval.

The owner approved temporary read/write access to `/dev/kvm` for only that
job's existing non-root runner UID. The guard requires the observed ordinary
root:kvm, mode 0660, non-extended ACL baseline. Only after build/unit/lint gates
pass does it add the named runner entry; the existing group/other effective
permissions remain unchanged. Android and its controller never run as root.
The runtime uses the normal ARM64 debug APK on the official API 35 Google APIs
image with verified ARM64 translation and guest shell UID 2000.

An EXIT/signal cleanup and an independent always-run cleanup stop the owned
emulator, remove the temporary entry and verify the exact original ACL, owner,
group, mode and device identity. Grant intent is recorded before mutation so
failed/uncertain grants are still cleaned. Any unexpected external ACL change
is not overwritten: only this job's named grant is removed, and verification
fails. The always-run artifact records cleanup evidence even when tests fail.
The hosted runner is disposable; there are no persistent group, udev, network,
credential or production-environment changes. No world-writable device mode is
used. A terminal job failure does not authorize another security grant: any
new accelerated job needs a fresh action-time approval. Production signing
still requires the owner's separate protected-release approval.
