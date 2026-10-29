# Single-use teaser capture

This tooling records the real Android IME in a disposable accelerated emulator.
It does not change the product sources: `verify_source.py` requires the exact
product objects from `e75b623f2cdac7b496e3f04695dd783d6f9fa0ca`.
Extra instrumentation and pure gesture tests are added only through
`capture.gradle`, outside `app/`.

## Execution boundary

The locally prepared supplemental workflow would run only on the first owner-created push of
`qa/bold-url-fix-capture-20261029-1`, attempt 1, in the public upstream repository.
It has no manual, scheduled, PR, or downstream trigger. Do not delete/recreate
the branch, rerun the job, or reuse historical approval. A fresh owner approval
is required for another privileged execution.

Builds and checks finish before the device grant. The guard requires the exact
ordinary root:kvm mode-0660, non-extended-ACL device baseline and a non-root
runner. It checks public workflow history contains only the current run, writes
durable intent, and grants only that runner UID read/write access. Runtime traps,
the outer trap, and an always step stop only the owned emulator and restore and
verify the original ACL, owner, group, mode and device identity. There are no
persistent group, udev, security-policy, or credential changes.

The whole job is capped at 45 minutes; privileged runtime is capped at 32 minutes,
with ten-minute boot and twenty-minute instrumentation bounds. The ordinary guest
shell must remain UID 2000. The official API35 Google image must advertise ARM64
translation. The emulator uses KVM and SwiftShader at 1080×1920/420 dpi.

FFmpeg and FFprobe are provisioned from the runner's official Ubuntu package
repositories before the decoder preflight; they are not assumed to be installed.
This uses ordinary disposable-host package provisioning. The emulator and
controller still run as the non-root runner, and the separate KVM guard remains
the only device-permission modification.

## Capture and review

`feature-plan.json` maps proposed clips to source-backed claims and limitations.
Core features are public in v0.6.1; Red, Black & Gold and White & Gold must be
labelled development previews ending “New colours coming soon”. Optional
English-only contextual AI is off by default and is not demonstrated. There is
no voice typing. Physical haptics, audible sound, phone performance, installation
continuity and production release readiness are not claimed by these captures.

The neutral dark editor is the actual debug `KeyboardQaActivity` EditText with
a Notes heading. Preferences and an example snippet are staged before takes;
recorded actions use real touchscreen injection against the IME. No text is
committed by the controller during recordings. The paced trackpad plan, passive
trace and eight scheduling regressions are copied from successful QA source
`32d507c55b0def60568a73ccbe0fdebb26739210`; no product listener is consumed.

Raw screenshots and screenrecord MP4s are preserved. Each shot with a live IME
exports actual keyboard, suggestion-strip and text-editor bounds, plus an
auxiliary coverage mask from the runtime body/frame Paths. Masks are geometry,
not screenshot pixels or generated product UI. Screenshots are flattened over
black: use the mask and undo the black matte at partial-alpha exterior edges.
Never remove all black pixels or alter the key interiors.

Recorder startup/finalization errors fail tests. The supplemental class runs independent feature methods with per-test preference/dictionary restoration; it does not re-record valid original takes. Guest handler resolution is recorded without launching anything; unavailable handlers fail only their own shot. Clip JSON records action
assertions separately. `stage_evidence.py` verifies container type, rejects ZIP
packages even if renamed, rejects symlinks, decodes media, hashes originals,
exports unmodified video review frames and produces a feature manifest. Only
explicit PNG/MP4/JSON capture/provenance/cleanup files are uploaded, for one day.
APKs, signing material, archives, environment dumps and broad build/system logs
are not artifact inputs. Public-repository artifact visibility applies.

Decoder and assertion results are not final visual approval. Review each raw
clip and extracted frames for visible action coverage, framing, popups and edges
before editing. Local compilation and mock guards are not Android runtime proof.

## Local supplemental preparation (not executed)

Run 40824867852 preserved 15 assertion-passed clips. Its two settings recordings
failed before actions at the old `>4096 bytes AND live process within 10s` gate;
appearance was never reached. The exported evidence lacks the poll-by-poll
process/byte observations needed to distinguish process exit from static-screen
muxer buffering. Decodable finalized files do not settle that distinction.

The local recorder repair removes the encoded-byte threshold. It requires the
same non-zombie `screenrecord` process (including `/proc/PID/stat` start identity)
and a freshly created output file throughout one second of preroll. It rejects
PID reuse, clears only its own previous clip path before launch, checks liveness
after actions/hold, and still requires SIGINT finalization and host decoding.
Bounded non-secret process state/start identity/output-byte observations and the
startup failure are exported in each recording JSON. No artificial screen
movement is introduced. This process gate does not certify encoded frames.

`supplemental-plan.json` audits remaining settings and behavioural shots, exact
assertions, dependencies, conditional App/Link/AI readiness and physical-device
limitations. The supplemental instrumentation and allowlist are implemented and compiled locally; Android runtime coverage remains unverified. Separate test methods must prevent one missing recording from hiding
the next. All valid existing media stays unchanged. The published trigger is
spent; neither this local repair nor the plan authorizes another run.


The completed branch-3 run retained 17 successful supplemental shots; branch4
added four. The local branch5 configuration selects only the remaining Text and
Link methods and keeps all36 valid clips unchanged. It keeps the same
45-minute job, 32-minute access phase and 20-minute test bound, pinned actions,
UID-only grant, exact cleanup and one-day artifact retention. There is no dispatch
trigger. Publication and execution require fresh owner approval. Do not recreate
branch 1, 2, 3 or 4, or rerun a previous workflow. No source or APK release is involved.

The optional AI model stays off and is not downloaded. Link capture uses only
https://www.android.com and asserts a resolved browser plus delivered intent, not
a loaded page or network speed. Browser onboarding/chooser can make that shot
unusable; it is never accepted as successful link proof. URL fields may offer
suggestions in current source while suppressing rewriting; email/password fields
suppress suggestions. Haptic and ambient-sensor controls do not prove physical feel
or ambient response.

`feature-plan.json.runner_selection` and the shell runner argument must agree;
regressions enforce exactly the two remaining independent methods. The four
other branch4 repairs passed runtime capture. The new zero-overlap repair is
compiled but awaits Android runtime validation. No further capture is currently
authorized.


Current pending configuration is branch5, selecting only
`g_configureAndRunTextShortcut` and `i_configureAndRunLinkShortcut`. The four
passed branch4 shots bring retained validated clips to36. None is re-recorded.
The zero-overlap fix retains the same focused-window observer, allows legitimate
zero client-local IME overlap, and independently validates the actual bound IME
window geometry. Active editor, input connection, animation completion, stable
frames, exact saved shortcuts/text and browser intent/focus assertions remain
required. Raw/observed insets and ownership diagnostics remain exported.
Branches1–4 are spent. No new execution is currently approved.


The owner-approved URL-fix run selects only i_configureAndRunLinkShortcut.
Product source is pinned to e75b623f2cdac7b496e3f04695dd783d6f9fa0ca, adding
URI EditorInfo to Link and protecting lowercase i in URI fields. Ordinary
autocorrection stays enabled, verified by unit tests and a real teh→the control.
No Autocorrect-Off workaround is used. Capture verifies exact touch entry, save,
reopen, browser focus and URL intent; page loading is not certified and no
onboarding agreement is accepted. All37 existing passed clips keep their old
provenance and bytes. Exactly one new creation push/run attempt is approved.
