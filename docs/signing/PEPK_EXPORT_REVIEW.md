# One-time encrypted signing-identity transfer: implemented, production disabled

The production review candidate remains `225268bd029319dc7c62fc47feef38b11e5c0e6c`.
This branch contains public workflow/helpers/tests, a public Console encryption key and
internal-use notice evidence. No application source, production credential, existing
signing identity or Play state is changed. Review-branch push is authorized; merge,
workflow dispatch, protected-job approval and real-key execution have not occurred.
The prior Library401 and restricted gstatic request have not been retried.

## Public candidate: next executable validation

`.github/workflows/pepk-public-audit.yml` is manual, exact-current-main only, with no
protected environment or secret references. After parent review and a separately
authorized merge to main, launch on `main` with `commit_sha` equal to that complete
40-character main SHA. A branch push alone does not register a new manual workflow
on GitHub's default branch. This task does not dispatch it.

The compiler is exact Temurin `21.0.13+9`, installed by the SHA-pinned setup-java
action with forced download and required vendor signature verification. The helper
also compares all installed JDK regular files/link targets against the independently
checksum-pinned publisher archive. Python `3.12.14` is a checksum-pinned
`actions/python-versions` release, extracted into an isolated prefix. Its setup.sh
is deliberately not run because it performs an unpinned pip upgrade. Cryptography,
cffi and pycparser wheels are hash-pinned, downloaded as public inputs and installed
offline with no dependency resolution. Exact URLs, release/asset IDs and hashes are
in `tools/release/pepk-candidate-runtime.json`; requirements have matching hashes.
Publisher metadata/TLS/hash provenance is not described as a PSF signature.

The candidate workflow runs adversarial PTY/policy unit tests on that runtime,
verifies the unchanged official Google source archive before extraction, checks the
complete `source/` manifest and archive-relative dependency-group manifests, and
builds all3613 Java sources twice (5990 classes). The supplied base recipe remains
byte-pinned; the explicit candidate delta is the selected canonical Java path.
It records both build hashes, compiled `PepkIdentity.class` hash, runtime/compiler
bytes, complete standard-library/package manifests, libpython/zlib identities and
resolved native-library hashes. It executes real PEPK console/crypto checks and
actual export-driver/final-verifier checks using newly generated disposable keys.
No source/tool archive, JAR, fixture keys or ciphertext are uploaded as artifacts.
Only nonsensitive candidate evidence is printed to the public job log.

Independent review must assess the resulting evidence, explicitly update the
production recipe/runtime/tool/helper pins and provision that reviewed runtime in
the protected workflow before any production execution. There is no automatic
promotion, no assumption that Ubuntu equals the prior Debian audit, and no
permanent requirement to reproduce that particular installed Debian image.
The strict reference builder currently still requires those old pins, so the
protected workflow is intentionally unexecutable on an unmatched runner.

## Approved private delivery implementation

The public source repository must remain public. The owner approved the fixed
private destination `JayMandava/bold-keys-signing-delivery`; parent has verified
it exists and is private. Parent handles repository/token setup in supported UI.
`BOLD_PEPK_DELIVERY_TOKEN` must be a fine-grained token limited to that repository's
Contents read/write, expiring within24hours, stored only in `production-release`.
No token exists as of the parent's last checkpoint; GitHub Mobile sudo confirmation
is pending. No production signing secret is copied to the delivery repository.

The export workflow retains the existing production-release sole-owner approval,
main-only/no-admin-bypass controls, exact source/workflow/current-main SHA checks,
and disabled readiness record. All tool preparation precedes signing injection.
Public and protected jobs rebuild locally rather than transfer third-party tools
through public Actions artifacts. After owner approval and before the four signing
secrets enter their separate export step, `pepk_delivery.py` checks the fixed
private repository's owner/ID/visibility/main branch and write permission, plus
bounded token expiry. It saves only a run-bound, nonsensitive local proof.

The dispatch input `delivery_token_expires_at` is a nonsensitive explicit UTC upper
bound within one day. Where GitHub returns its token-expiry header, the helper
checks it against that bound. Without that header, this remains an owner/UI expiry
assertion, not independent API proof. Fine-grained target restriction and actual
expiry must be verified in secure token setup; a token prefix cannot prove scopes.
The helper does not create credentials or claim it can inspect all token grants.

After local final verification, a separate step with only the delivery token
rechecks destination identity/privacy/expiry immediately before each write. It
sends exactly `encrypted-export.zip` and `public-certificate.pem` under
`transfers/<reviewed-sha>/<run-id>-<attempt>/`. Both paths must be absent. PUT is
attempted once, never automatically retried. Readback checks blob SHA, size and
SHA256; uncertain PUT outcomes can be resolved by readback alone. Failed or partial
transfers require private destination inspection before a new attempt. Authenticated
redirects and out-of-allowlist paths are rejected. API bodies, tokens and ciphertext
are never logged; no Actions artifact upload is used. Local metadata/receipt is
cleaned rather than published. The token must be revoked and its stored environment
secret removed after confirmed delivery. Committed ciphertext remains in private
Git history; deletion does not promise erasure. Repository administrators and
collaborators with access remain part of the private trust boundary.

## Key, encryption and execution safeguards

Expected existing signing certificate DER SHA256:
`B2:37:E1:AE:C0:BF:DA:E5:79:CA:EB:E1:A0:D9:75:17:AF:1B:7C:06:6A:DC:4B:3D:19:7B:64:1A:DF:A4:6C:34`.
The current Console recipient is a distinct public RSA3072 key supplied by the owner;
PEM SHA256 `ac50890bea088f457fd6ebdb56fd48ed1c58f42b4e7c380b2608ec9b2085e623`,
SPKI SHA256 `4a269c18760f5159742cb14ea8235359d3b4fd9bf45c2492d5dedde4036c90b4`.
Its provenance is the owner's current Console download, not independent Google
cryptographic attestation. Neither is the Console's different51:A2 app-signing cert.

Readiness is checked before any production-secret/token lookup. The review record
still has `export_implementation_reviewed:false`, missing independent production
and private-delivery review IDs, and a disabled status. Existing signing inputs
are only referenced in the export step: BOLD_RELEASE_KEYSTORE_BASE64,
BOLD_RELEASE_STORE_PASSWORD, BOLD_RELEASE_KEY_ALIAS, BOLD_RELEASE_KEY_PASSWORD.
No app build, unrelated application code, GH token or download runs with these.

The source-verified tool has no env: password syntax and no stdin fallback; parse
errors may print argv. Password flags are omitted. A private PTY controller matches
ordered exact prompts and waits for ECHO and ECHONL to clear before each bounded
nonblocking write; input/output/timeouts/reaping are bounded and raw transcripts
are discarded. A hash-pinned helper checks the public certificate and proves
private/public-key correspondence through an in-memory challenge. Only an encrypted
keystore container is temporarily written; no raw private-key serialization.
Temporary directories/permissions/core-dump limits, signal cleanup and workflow
always cleanup are applied. Terminal flag checking/writing is not atomic against
malicious code, making trusted child/runtime pins essential. Full VM/string/host
forensic erasure is not promised.

The audited source uses fresh AES256, RSA-OAEP SHA1/MGF1 and RFC5649 wrapping;
certificate-inclusive ZIP has exactly encryptedPrivateKey and certificate.pem.
Local checks prove key/cert pairing, allowlist/CRC/bounds/framing and independent
round trips with disposable recipients. They cannot decrypt Google's production
ciphertext or prove Play acceptance. This workflow performs no Play upload/save.

## Evidence and licensing scope

Independent audit task `01a18fd0-933b-76c1-a435-89f248eee029` verified the complete
transferred implementation, found the complete-manifest-prefix defect, rebuilt
Google source twice to `7923237dd6ea1c0198b0ee91a1bfc0dafbd858aa88936f2ce847652e93d1e18a`,
and reported43 corrected unit tests,11 actual hardened-PTY checks and22 actual
export-driver/final-verifier checks with fresh disposable identities. Those actual
PEPK executions occurred in that separate environment, not this restricted worker.
New local delivery API tests mock GitHub; they do not prove a real token/repository
write, reviewer approval, GitHub provisioning or Google acceptance.

`pepk-third-party-notices.txt` retains internal copyright/permission/disclaimer and
attribution evidence. Scoped internal compilation/one-time execution is supported
by inspected permissive notices. Public redistribution clearance for the combined
archive is incomplete: application Apache2.0, Bouncy Castle permissive, Checker MIT,
concurrency annotations CC Attribution and incomplete annotation notice details
must not be collapsed into a wholly-Apache claim. No third-party source or compiled
PEPK binary is committed or published. The separate Google binary is not a necessary
gate; the official source build is a viable reviewed tool choice. Formal support
and actual Console acceptance of this exact build remain unproven.

## Remaining protected steps

1. Review/merge public code, then owner launches the nonsecret exact-main candidate
   workflow with its full main SHA. Independently review hashes/real dummy tests.
2. Explicitly promote reviewed runtime/tool/helper/recipe pins and provision that
   runtime in the protected workflow. Review this new delivery implementation.
   Keep readiness disabled until those reviews are complete.
3. Parent completes secure dedicated token setup after the owner's Mobile sudo
   confirmation; no token/password should be pasted in chat. Confirm Contents-only,
   single-private-repo scope and expiry≤24h, then record UTC upper bound.
4. On separately authorized reviewed main, dispatch export with commit_sha and
   delivery_token_expires_at, then owner approves production-release. No action
   here does that. Verify two private files and revoke/remove the transfer token.
5. Parent/owner performs the already authorized authenticated Play import/save and
   verifies the final B237 certificate identity. No replacement key is generated.

## Local validation for this revision

- Full repository Python suite:96 passed. PEPK subset:56 passed.
- The same56 PEPK tests passed using the actual hash-verified GitHub Python3.12.14
  prefix and Temurin21.0.13+9 installed locally. This includes real disposable
  identity/helper tests and adversarial mock children; the one-source build fixture
  is explicitly not an official PEPK build.
- Public runtime bootstrap completed:454 JDK files/links compared,7802 standard
  library files/links inventoried,1031 package files/links inventoried. No setup.sh
  or network package resolution was used. Local archive comparison is distinct
  from the workflow's future setup-java signature verification.
- actionlint passed both PEPK workflows; git diff --check passed.
- Application `app/src/main` diff against225268bd is empty. Existing unsigned AAB
  SHA256 remains801258658fb56c113019133dd2e143833295e22e9556a5d693a034ff9f1ba2e9;
  submission ZIP remainscc142a25e8654fc9ff18ff8e524bdb4c386821e16f9e3e549f85c876fd1bd456.
- The new full-source candidate build/actual driver audit was NOT executed here:
  the official source is unavailable under this task's old network policy, and
  its denied URL was not retried. Independent reference audit results above remain
  separate evidence. The new candidate execution is the next public workflow step.

## Loaded-runtime provenance correction (pins/readiness unchanged)

The successful public run41175240814 proved repeat builds and disposable checks, but
its old diagnostic `ldd` call dropped LD_LIBRARY_PATH. That field described a different
search environment, and its system-libpython hash is not an approved runtime identity.
The corrected candidate records file-backed executable ELF mappings from its own
/proc/self/maps, checks path/device/inode and file hashes, and separately records loaded
host libz. It verifies mapped libpython equals the interpreter/library bytes derived
from the existing checksum-pinned publisher archive. It also probes a child with the
same environment used to run the build recipe. Deleted/replaced mappings, conflicting
or missing shared libpython, and unresolved ldd entries (including exit0) fail closed.
The diagnostic child receives only PATH/LANG and the verified prefix's library path;
Python build children also receive that prefix's Python selectors. No arbitrary loader
path, LD_PRELOAD, LD_AUDIT, token or signing input is inherited. This records mapped
file identities; it does not attest that in-memory pages were never modified.

Production enforcement now requires reviewed python_sha256, python_library_sha256,
python_standard_library_sha256, python_packages_sha256 and native_library_pins.
The latter uses python:<prefix-relative path> and host:<canonical absolute path> keys.
Every observed native mapping must match a reviewed entry; loaded shared host libz is
mandatory. These checks run during strict toolchain preparation, for the build probe
child, and at payload verification before signing-secret reads. The readiness policy
rejects missing runtime identities even if other synthetic approval fields are filled.
Candidate-derived identities remain labelled NOT_APPROVED; no configuration pins,
review IDs or readiness status have been promoted by this correction.

Strict builder compatibility selects the already-reviewed canonical Java path instead
of the runner default, and preserves only the verified Python prefix in child startup.
A compiler substitution in the immutable base recipe requires an explicit
`derived_build_recipe_sha256` matching the derived script. The unchanged original recipe
is permitted only when /usr/bin/java resolves to the reviewed compiler. The protected
workflow has NOT yet been changed to install/use the candidate runtime; that provisioning
and explicit runtime/recipe pin promotion remain later reviewed steps. It cannot become
ready merely by changing a status string.

Local standalone test hosts without a shared libpython mapping are explicitly reported
as unverified executable-only identities. That fallback supports local fixture tests;
production enforcement rejects it. The official extracted shared-library runtime must
always have the exact archive-derived live mapping. No system-libpython hash is adopted
to make conflicting provenance agree. No PEPK crypto, Java helper or expected JAR hash
changes are part of this correction.

Correction validation:86 repository tests and69 PEPK tests on the actual pinned runtime
pass. Live parent/build-child probes both mapped archive-matching libpython
1fa3c52ba5aa8f6b2852836a4bf6cbb23161f7cf379f6f19248d87124b28b38a and separately
mapped host libz.so.1.3.1 (local hash85590dd58edf5445e18bc7193e5ebc01ac5841f1ae187e97705a662e90c6421e).
A live public-only strict-toolchain probe selected the nondefault reviewed compiler,
required an explicit derived-recipe fixture pin, enforced the build child, and rejected
changed libpython/stdlib/package/host-libz identities. These in-memory fixture pins were
never written to the production record. New full-source PEPK/driver execution remains
for independent review; no original Google source request was retried in this worker.
