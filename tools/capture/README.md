# Single-use teaser capture

This tooling records the real Android IME in a disposable accelerated emulator.
It does not change the product sources: `verify_source.py` requires the exact
product objects from `ce23cf74526b4864201d38503d2c9ad233d1787d`.
Extra instrumentation and pure gesture tests are added only through
`capture.gradle`, outside `app/`.

## Execution boundary

The workflow runs only on the first owner-created push of
`qa/bold-teaser-capture-20261029-1`, attempt 1, in the public upstream repository.
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

Recorder startup/finalization errors fail tests. Clip JSON records action
assertions separately. `stage_evidence.py` verifies container type, rejects ZIP
packages even if renamed, rejects symlinks, decodes media, hashes originals,
exports unmodified video review frames and produces a feature manifest. Only
explicit PNG/MP4/JSON capture/provenance/cleanup files are uploaded, for one day.
APKs, signing material, archives, environment dumps and broad build/system logs
are not artifact inputs. Public-repository artifact visibility applies.

Decoder and assertion results are not final visual approval. Review each raw
clip and extracted frames for visible action coverage, framing, popups and edges
before editing. Local compilation and mock guards are not Android runtime proof.
