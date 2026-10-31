# Reconciled production candidate — 0.6.2

This is preparation for review, not a signed or published release. The original
`review/play-api36-0.6.2` remains intact at
`225268bd029319dc7c62fc47feef38b11e5c0e6c`.

The integration branch replays its eight existing commits onto public main
`3e92cb1595f46eb533b71bc8dd1e851c12d90622`, then incorporates the image-only main
merge `fb371fe7ef6f0d19e91c2e74bd7c485ebe42995a` (PR13). The entire app Git tree
remains `cc750de96449c42fcfcf188fd6d3b5756d95347b`, identical to the original
candidate. The llama.cpp gitlink remains
`748d4225b9016b17ce4bcfa69fdc2c39f473a965`. All36 PEPK files present on the base
are retained byte-for-byte. No private debug/colour branch or new application
feature was used.

## App and protected bundle path

Package `ai.boldkeys`, versionName0.6.2/code10, min33 and compile/target36 are
unchanged from the candidate. The nine public tags v0.1.0 through v0.6.1 have codes
1 through9; no newer tag was present during the2026-10-31 check.

Existing changes retain the local spelling engine and improved CC0 next-word tables,
fail-closed release signing, exact privacy-policy URL, offline credits, editor
no-personalized-learning protections and private-aware virtual accessibility nodes.
Password/email correction and suggestions are suppressed; URL fields can suggest
without automatic rewrite. No new accessibility announcement contains changing
candidate/editor text. Original API36 ATD evidence in ACCESSIBILITY_REVIEW.md still
applies to the byte-identical app sources; no new device, TalkBack, physical-arm64
or model-execution result is claimed by this integration.

`play-bundle.yml` is nonpublishing and manual/main-only. It reuses the existing
production-release owner approval and exact-main controls, validates the unsigned
artifact/provenance before credentials, signs only with the existing four production
secret names through jarsigner, and verifies every signed payload against the
B237 certificate. No Gradle/build/repository code runs in its signing step. It
neither enrolls signing nor uploads to Play. The owner reports that Play now has
this same existing signing certificate; the one-time PEPK delivery token has been
revoked and is not used by this AAB path. No further PEPK export is needed.

## Data and distribution boundary

The three spelling assets remain the original FrequencyWords-derived lists, with
an affirmative CC BY-SA4.0 data grant, complete offline notices and retained source
hashes. The outstanding enquiry concerns underlying OpenSubtitles permissions for
commercial/offline Play redistribution and database rights. This is unresolved due
diligence, not a finding of infringement or a blanket Play prohibition on CC BY-SA.
No permission response or legal attestation is invented here. Existing experimental
spelling replacements were not selected or packaged.

The Common Voice CC0 next-word generator and sealed evaluation are unchanged;
source/hash reproduction does not establish universal typing accuracy. Previously
reported coverage, false-fix, vocabulary, memory and uncommon-phrase limitations
remain applicable. No old unresolved next-word corpus or seed fallback is restored.

Review, unsigned build validation, public listing assets and factual policy drafts
can proceed while the dictionary enquiry is pending. Distribution requires the
owner's disposition of that question plus final policy/Data Safety/app-content
review. No signing or publication was performed in this integration. A signed AAB
still requires an exact reviewed main commit, owner manual launch and protected
approval; the agent's workflow-dispatch restriction is unchanged.

## Listing and policy continuity

PR13 publishes only eight previously approved PNGs in
`fastlane/metadata/android/en-US/images`. They are byte-identical to the existing
preparation pack and show the same candidate UI. Internal QA records, the submission
ZIP, unsigned bundle and any signing material are not published with those images.
Website wording proposals remain version-qualified review text; no site deployment
or Console declaration is performed here. Physical-device/TalkBack validation
limits remain explicit, without asserting that they are Google prerequisites.

## Local validation of the reconciled tree

The unchanged production app passes98 API33/36 Robolectric tests with zero
failures/errors/skips, release lint with zero errors (30 warnings,3 hints), and
unsigned release APK/AAB builds using the installed pinned SDK/NDK inputs. All13
signing-mode functional checks pass, including rejection of missing/malformed/debug
credentials and an observed unsigned build that never opens signing properties.
Those checks use only disposable invalid property fixtures, never a production key.
The pinned data audit and offline regeneration from verified cached sources pass.
Final unsigned artifact hashes/provenance and repository test results are reported
with the reviewed commit; local outputs are not upload-ready signed bundles.
