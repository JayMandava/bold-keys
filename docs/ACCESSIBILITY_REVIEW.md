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
