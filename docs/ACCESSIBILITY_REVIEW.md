# Accessibility/privacy production review

Base: `09040344cedb067291c9f7e323e71345d3bf95a3` on `review/play-api36-0.6.2`. Package/version remain ai.boldkeys, 0.6.2/code10, min33/target36. Local engine, spelling dictionaries and improved CC0 next-word tables are unchanged. No production signing or external operation is included.

## Implementation

The painted keyboard and suggestion bar use AndroidX ExploreByTouchHelper virtual buttons with clipped bounds, independent focus and explicit activation. Shift/Alt expose checked/state descriptions, hold equivalents are named actions, and cursor movement/selection are trackpad actions. Existing accents, symbol/emoji pickers, skin tones, currency-volume and Speed shortcuts have accessible equivalents; shortcut labels do not expose saved snippets/URLs. Standard Enter action labels use Android metadata, never arbitrary editor text. Replaced layouts/pickers/candidates reject obsolete action IDs. Touch-mode focus does not add a visual ring to normal touch typing.

Candidate labels are available to an enabled accessibility tool navigating visible suggestions. API34+ marks these nodes/events data-sensitive. Candidate changes emit only generic subtree invalidation, with no added live-region, text-change or announcement payload containing candidates/editor contents. Android/editor/tool feedback is outside this guarantee. Password/email candidates are absent; incognito stops new learning rather than hiding every visible suggestion. New/finished editors immediately clear prior candidates and transient accessibility shortcut/accent modes.

Privacy wording in Settings/README now distinguishes password/email suppression from URL suggestions without automatic rewrite. Exact proposed website wording is in PRIVACY_POLICY_ACCESSIBILITY_PROPOSAL.md; no website change was made.

## Verification boundary

Final amended source: 98 unit tests on API33/36, zero failures; release lint zero errors (30 warnings,3 hints); 40 Python data/workflow/signing-contract tests; actionlint; pinned data audit; unsigned release APK/AAB builds and bundle verification pass. The bundle verifier checks package/version/SDK, release-only components, arm64 native set/16KiB alignment, exact packaged data and notices, and absence of signatures. These checks do not prove spoken screen-reader usability or physical arm64 performance.

Device evidence and final executed/not-run results are recorded separately after the targeted API36 ATD probe. Full TalkBack is absent from the authorized image; no TalkBack claim or proprietary service installation is made.

## Distribution gates retained

Existing FrequencyWords underlying-source redistribution confirmation remains a separate owner/maintainer question; it is neither proven infringement nor a blanket Google Play prohibition. This change does not replace or relicense those dictionaries. New CustomView1.2.0 Apache2 attribution/source is included in offline notices. Optional model terms remain unchanged; no model was downloaded or executed. Production signing, physical-device/arm64 and TalkBack validation, live-policy publication and final Data Safety/export attestations remain outside these local unsigned checks.

## Unsigned artifacts

Build output (same application sources as implementation commit `378f748fb883109fc710ac158186b4f91c602152`):

| File | Bytes | SHA-256 |
|---|---:|---|
| unsigned.aab | 13964686 | `801258658fb56c113019133dd2e143833295e22e9556a5d693a034ff9f1ba2e9` |
| unsigned.apk | 29876446 | `f6951e06fa28fc0232a18c982cadcaad0374b33f54aa40889ef88b2f371fa47b` |

Local directory: `/workspace/bold-a11y-review/artifacts`. These are unsigned review artifacts, not install/upload-ready production releases. Public production certificate pin remains `B2:37:E1:AE:C0:BF:DA:E5:79:CA:EB:E1:A0:D9:75:17:AF:1B:7C:06:6A:DC:4B:3D:19:7B:64:1A:DF:A4:6C:34`; no signing operation or secret access occurred. AAB grew 25589 bytes from the preceding candidate, APK 66353 bytes; no new on-phone memory/performance claim is made.

## Executed API36 framework results

On cached official AOSP ATD API36/ext17rev1 x86_64, emulator37.2.13 software TCG/SwiftShader (no KVM), an isolated test-only-signed build exposes35 individual Button keys. Actual keyboard/accessibility focus, no typing on focus, exactly-once activation, candidate use/data-sensitive flag, modifier state, EN/FR/DE correction, accents/symbols and obsolete-layout rejection pass. After positively separating the explicit candidate-focus event,20 changing candidates produce text-free subtree events and stale actions are rejected. Password/email nodes/rewrite are suppressed; URLs suggest without rewrite; normal learning persists and incognito learning does not. At150% font and landscape, actual focus/activation/bounds and cursor movement/selection pass. Switching editor clears pending accessibility shortcut mode.

No TalkBack package/service is installed; these are framework actions, not spoken/gesture user-flow certification. Device volume/shortcut execution and emoji-tone navigation were not exercised; emoji/personalization and transient-mode regression coverage is in the API33/36 unit suite. No model was downloaded/executed. App data/font assets and engine are unchanged; no new phone performance or incremental memory measurement was made. The first focus failure was fixed in production; later event-observation and lazy-list Settings predicates were fixture issues, preserved in evidence.

Device lab: `/workspace/bold-keys-a11y-device`, isolated `experiment/accessibility-device-api36`; production main-source parity is exact except one documented test service hook. Detailed metadata, synthetic results, verifier and genuine test screenshots are in its `docs/accessibility-device`. Screenshots show an explicitly labeled x86 device lab, not a production store listing. Process-local HOME plus Android configuration-home overrides redirect only SDK child processes to disposable writable storage; no security change or production key access is needed.

The combined real-device verifier passes39 checks, retaining historical fixture failures in its output. Settings Typing privacy wording, About privacy/credits controls, offline notices, actual toolbar Back activation and return to the Bold Keys home title pass. Genuine Activity captures were visually inspected; global screencap returned black for the software-rendered Compose screen and is preserved as capture-failure evidence, not a production UI result. The fixture clears UiAutomation caches and requires successful Back action plus the actual home title, avoiding a stale-tree false positive.
