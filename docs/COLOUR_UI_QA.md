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
The runtime is configured to test the normal ARM64 debug APK on the official
API 35 Google APIs image. Its ABI/native-bridge properties must advertise ARM64
translation, and its guest shell must remain UID 2000; these setup checks do
not prove successful ARM64 app execution.

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

#### First accelerated attempt and readiness correction

Run [40438950301](https://github.com/JayMandava/bold-keys/actions/runs/40438950301)
reached a healthy API 35 Google launcher advertising ARM64 translation, with no ANRs,
but stopped **before installing/testing Bold Keys**: the readiness helper had
incorrectly assumed the AOSP launcher package. The job's artifacts verify
that its temporary runner grant was removed and the exact original device ACL
and metadata were restored. This consumes that one-job approval; it is not a
runtime product pass and must not be rerun under the same approval.

The corrected helper asks Android to resolve its current default HOME activity,
normalizes short/full component names, and requires that exact component to be
focused with its package present in the real hierarchy. It fails closed on an
unresolved/ambiguous HOME, another focused activity, unexpected dialogs, or
instability. Regression fixtures include the actual Google launcher focus from
that run and AOSP, plus rejection cases. Settings launch checks likewise use
actual WindowManager focus rather than the last accessibility-event package.
Neither correction relaxes the product UI assertions or startup health bounds.

Optional raw recording now waits at most ten seconds for a live recorder and
file output, then holds a one-second genuine preroll before the action. A
bounded SIGINT/finalization wait follows the action. Startup/finalization
warnings remain separate from functional assertions; byte output is **not** a
successful video claim. Decode each resulting file and inspect that its real
action is actually present before editing or publishing it.

#### Separately approved second accelerated job

After the first attempt stopped and exact cleanup was verified, the owner gave
fresh action-time approval on 2026-10-27 at 03:17 UTC for **one additional**
disposable accelerated test with the same temporary runner-only access and
cleanup. This is isolated as the initial creation of
`qa/iconic-colours-kvm-20261027-2` and workflow
`colour-approved-kvm-qa-2.yml`. It includes the validated HOME/focus/capture
corrections. The first branch/run is retained; it is not rerun or recreated.

Only the exact new branch, first creation event, attempt 1 and sole public
workflow-history entry may reach the grant. The ordinary device baseline,
build-before-grant, runner UID-only ACL, normal guest shell, bounded runtime,
exact cleanup and always-run evidence checks are unchanged. A terminal failure
of this second job likewise consumes this additional approval; no third grant
is authorized. Production approval is still a separate owner-only gate.

#### Second attempt: provisioning/HOME lifecycle, not a product failure

Run [40485245229](https://github.com/JayMandava/bold-keys/actions/runs/40485245229)
passed both 31-test JVM suites, both lint gates and ARM64 builds. Hardware
acceleration passed; ABI/native-bridge properties advertised ARM64 translation.
No ANRs were recorded; actual ARM64 app execution remains untested. It nevertheless
stopped before installing Bold Keys: the first HOME resolution still referred
to GoogleSdkSetup. Actual Android logs then show NexusLauncher starting at
10:08:34 UTC, displaying at 10:08:36, and receiving the HOME role at 10:08:40.
The helper had incorrectly frozen the initial component. The final screen was
the healthy launcher. Artifacts verify exact original ACL/device metadata
restoration, absent runner grant and owned emulator shutdown. This second
approval is consumed; there is no authorization for a third grant.

The follow-up readiness correction is a bounded lifecycle gate, not a setup
bypass. Every poll reads device/user provisioning completion, current HOME,
actual user-0 window focus, system process identity, boot state and error logs.
It never writes provisioning settings or chooses a launcher. Incomplete setup
or a temporarily unresolved HOME remains pending within the original deadline;
ambiguous/malformed observations fail closed. A changed HOME or provisioning
state restarts both stability timers. Completed provisioning regressing,
system-server restart, boot regression, fatal exception or watchdog failure is
an error, not a recovery success. Both ordinary and system-process Java fatal
forms and warning-level watchdog termination messages are recognized.

Only a provisioned current HOME with the existing thirty-second focus and
sixty-second healthy interval can proceed. Resolution, provisioning, focus and
health are checked again after hierarchy collection and again after screenshot
capture; a late transition cannot use stale evidence. Initial/current HOME and
all observed transitions are preserved. The original timeout, one narrowly
verified System UI Wait maximum, product assertions and ACL/production gates
remain unchanged. Regression fixtures cover both real failed-run component
shapes plus synthetic provisioning/focus/health/capture-boundary transitions;
these mocks are not real Android product-test results.

Changing to a standard image alone is not a lifecycle fix: AOSP's own
[Provision activity](https://android.googlesource.com/platform/packages/apps/Provision/+/8af0d687f40aef1974aac86516114fe440262741/src/com/android/provision/DefaultActivity.java)
completes provisioning and disables its initial activity too. The separately
inspected standard API 33 image lacks ARM64 translation and would require the
labelled x86 debug variant. The API 35 Google image advertises the
ARM64 translation required to attempt the normal APK. No new image trial, emulator run,
security grant or production release is implied by this local correction.

#### Separately approved third accelerated job

After the lifecycle correction passed 92 checks and independent review, the
owner explicitly approved one further disposable accelerated test. This third
approval is isolated to the initial creation of
`qa/iconic-colours-kvm-20261027-3` and workflow
`colour-approved-kvm-qa-3.yml`, with the validated correction's ancestry.
Both consumed branches and runs remain preserved; neither is rerun or recreated.

The exact public repository, owner actor, creation event, attempt 1 and sole
matching public run-history guards remain mandatory. Only this job's existing
non-root runner UID receives temporary read/write access to the ordinary
virtualization device after build gates. Baseline checks, bounded runtime,
normal guest shell, exact ACL/metadata restoration and always-run cleanup are
unchanged. No app code, production bytes, timeout, network, secret, persistent
group or device-wide permission change is included. A terminal third job
consumes this specific approval; no fourth grant is authorized. Protected
production release approval remains separate and owner-only.

#### Third run outcome and test-input synchronization correction

Run [40493503355](https://github.com/JayMandava/bold-keys/actions/runs/40493503355)
at `bfc07d1b72446254642be13f28f7cb35d30af86d` passed the provisioning/HOME
readiness gate and installed the normal ARM64 debug APK on API 35 with native
translation. Four instrumentation tests ran: the large-text/backlight/real-app
Red typing test passed, and the other three failed their initial text checks.
This is **not a full keyboard runtime pass**. The complete colour/input matrix,
trackpad, private-field and landscape gates remain unpassed.

The recorded event timeline identifies an input-driver race. In the first two
tests, Q was injected about 200 ms before IME `onShown`; the existing
outside-touch handler then hid the keyboard. In the third, the first T was
injected before IME `onShown`, and the editor contained `eh`, not `teh`. The
later real TryCard successfully received `Make it yours.` after the keyboard
was settled. No product regression is established by these failures, but that
does not certify the unreached behaviour. Original logs, screenshots and all
four results are preserved without changing their failed status.

The correction observes the real client window's
[IME animation lifecycle](https://developer.android.com/reference/android/view/WindowInsetsAnimation.Callback)
before injecting input. A zero-area, non-focusable, inaccessible test observer
is attached before each Activity starts; it does not replace Compose callbacks,
consume insets or change keyboard behaviour. Prepared/running IME animations
must finish, client IME insets must be visible and positive, and the real input
connection must belong to the focused served editor. Matching keyboard and
suggestion-strip geometry must then be stable across two distinct Choreographer
frames. Activity recreation, lost focus, changed editor/geometry and hidden IME
invalidate readiness. Every key, picker and trackpad action uses this gate.
There is no probe character, lost-key retry, disabled outside-touch handling or
weakened expected-text assertion.

The pure state machine is debug-only, with JVM regressions for pre-animation
end layout, overlapping/cancelled animations, duplicate frame timestamps,
geometry/editor changes, focus loss, detach and repeated awaits. The observer
itself exists only in instrumentation. Production source, release settings,
signer policy and ACL workflow are unchanged. Host tests/builds are not a
substitute for rerunning the corrected instrumentation on Android.

The third job's cleanup evidence confirms exact original ACL, owner, group,
mode and device identity restored; the runner-specific grant is absent and the
owned emulator is stopped. The third approval is consumed. No fourth job or
grant follows from this fix. Public release and promotion remain on hold;
debug APK storage and a clearly labelled private Coming Soon preview are
separate from release readiness.


#### Separately approved fourth accelerated job

After the test-input correction passed host tests, lint, APK compilation and
independent review, the owner approved one further disposable accelerated test
and its test-only public source push. This fourth approval is isolated to the
initial creation of `qa/iconic-colours-kvm-20261027-4` and workflow
`colour-approved-kvm-qa-4.yml`, including correction ancestor
`2f2421c5055808a3923eef37335b0d29413f417c`.

The three consumed branches/jobs remain preserved, never rerun or recreated.
Exact actor/repository/ref/creation/attempt/public-history guards, build-before-
grant, one non-root runner UID's temporary device ACL, runtime budgets and
exact restoration with always-cleanup are unchanged. No fifth job or grant is
authorized. Production source and release bytes remain unchanged; the debug
state helper and instrumentation observation are test-only. Public APK release
and Instagram publication remain on hold. Phone connection and certificate
compatibility checks follow completed cloud QA, not an assumed successful run.

Before the fourth branch was pushed, its artifact upload was narrowed to honour
the public-download hold. Only allowlisted text reports/results/hash evidence,
real PNG screenshots and screenrecord MP4s are staged into a fresh public
evidence directory. Package archives and other binaries are never uploaded:
this excludes APKs, AABs, Git bundles, ZIP/JARs, native libraries and binary test
result stores. Renamed package content, symlinks and unexpected capture formats
are rejected too. The manifest records copied hashes and excluded filenames;
an unsuccessful staging step prevents upload rather than falling back to raw
build directories. Build/runtime assertions, local private APKs and exact ACL
cleanup are unchanged. Earlier authorized artifacts are handled separately;
this upload policy neither deletes them nor changes source/run history.

#### Fourth run: three tests pass; final White gesture overshoots

Run [40511992713](https://github.com/JayMandava/bold-keys/actions/runs/40511992713)
at `a52eecf939ab1fd86a7747291d6ce63168cb68ff` passed the new IME-settle gate.
Tests b, c and d passed, covering portrait/landscape, explicit and automatic
light/dark palettes, text/search/email/URL/password/number fields, picker reset,
larger text, backlight independence and genuine Red TryCard typing.

Test a completed its entire sequence for Red, Black & Gold, White & Gold and
Black, including cursor movement, Shift-selection, delete, picker interactions
and real TryCard captures. On the fifth, classic White iteration, the first
trackpad swipe moved the caret to index 0 in `qWr`, rather than expected index
1. The keyboard remained visible and settled. It was not an unresponsive
trackpad, nor a failure of the first/new palette. The overall suite remains
**failed: three passed, one failed**.

Both independent reviews identify a test-driver explanation consistent with
the evidence, but not proven by a delivered-event trace: the harness injected
an effectively instantaneous single 28dp MOVE. Android 15's
[input resampling](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android15-release/libs/input/InputConsumer.cpp)
can extrapolate a sufficiently close sample pair by up to 50%, turning that
jump into 42dp and crossing three 13dp cursor steps. The captured caret position
matches this mechanism; the run did not log delivered MotionEvents, so this is
an inference, not a confirmed product defect. No colour-dependent input path
was found.

The next local correction is test-only: a small, physically paced multi-sample
swipe along the same 28dp path, with actual monotonic event gaps and an endpoint
hold, plus passive delivered-touch/selection diagnostics. Exact two-character
movement and selection assertions remain unchanged; there is no retry,
production sensitivity change, resampling-property change or fabricated pass.
Runtime validation of that correction is still required.

Fourth-run evidence verifies exact ACL/owner/group/mode/device restoration,
absent runner grant and stopped emulator. Its 162-file evidence manifest was
independently hash-checked, with no application binary uploaded. This fourth
approval is consumed; no fifth job/grant is authorized. Genuine palette frames
can support a private Coming Soon preview, but not a full-QA or release claim.
