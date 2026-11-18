# Contact and Direct Call Speed keys

Contact and Direct Call extend the existing `t`/`v`/`l` preference JSON with stable `contact` and `direct_call` type values. Existing app, link (including tel links), and text records are preserved unchanged. No new Dial action is added. Clearing or cancelling in settings never calls anyone.

## Selection and identity

Both actions use Android ACTION_PICK. Contact requests Contacts.CONTENT_TYPE; Direct Call requests Phone.CONTENT_TYPE so the user chooses a specific number, including when a contact has several numbers. Only the returned Contacts Provider row is read while the temporary picker grant is valid. No READ_CONTACTS, persistable grant assumption, contact enumeration, refresh job, logging, or upload is introduced. If the picker is absent, the row is unreadable, or the number is rejected, the prior assignment remains intact.

Contact copies the contact's lookup key, last known ID and display label. ACTION_VIEW opens that lookup URI without querying the provider again. Android's contacts viewer resolves changes to aggregate IDs. A deleted contact, unavailable managed profile or provider may produce the viewer's unavailable-card UI; re-selection is the recovery path. The keyboard cannot reliably preflight deletion without renewed read access. It never substitutes another contact or searches by a display name. The saved settings label can become stale.

Direct Call stores a number snapshot and label, not a temporary data URI. Contact edits or deletion do not silently redirect or remove that call target: the settings explicitly instruct users to re-select to update it. Save is separate from both selection and permission grant. Pending editor fields use saved instance state; the permission itself is always rechecked on resume, Save, and invocation.

## Direct calling

CALL_PHONE is requested only through the explicit permission button while configuring Direct Call with a selected number. Denial or revocation launches nothing. The permission callback only updates settings state: it never launches, saves or queues a call. Permanent denial is explained with instructions to enable Phone in Android app permissions. Ordinary typing and other actions remain usable.

The accepted number syntax is an optional leading plus and 3–15 ASCII digits, with spaces, hyphens, periods and parentheses allowed as formatting. Service/USSD/MMI codes, pauses, waits, extensions, URI text and misplaced plus signs are rejected. Common emergency numbers are blocked even without a phone database; Android's local emergency check runs at selection, Save and dispatch and fails closed on platform errors. ACTION_CALL also prohibits emergency calling. Missing handlers and permission/launch exceptions produce a generic accessible Android toast containing no contact data, with no ACTION_DIAL fallback. A valid launch can still require SIM/account selection or other system UI.

The IME permits at most one Direct Call per held Speed gesture, plus a three-second debounce across new gestures and accessibility activations. App, link and text shortcuts keep their existing repeated-use behavior. Keyboard artwork and hit targets are unchanged; a gesture-start listener only scopes the call guard. Test calls are intercepted intents, never real telephone calls.

## Platform references

Reviewed Android official documentation on 2026-11-18:

- [Common contact and phone picker intents and temporary access](https://developer.android.com/guide/components/intents-common#Contacts)
- [Contacts lookup URIs for long-term links](https://developer.android.com/reference/android/provider/ContactsContract.Contacts#CONTENT_LOOKUP_URI)
- [ACTION_CALL permission and emergency restrictions](https://developer.android.com/reference/android/content/Intent#ACTION_CALL)
- [Runtime permission checks and denial](https://developer.android.com/training/permissions/requesting)

## Validation scope

Robolectric tests intercept call intents and use a provider double that asserts reads target only the selected row. Coverage includes legacy and new persistence, picker results/cancellation, explicit Save, permission denial/grant/revocation, number rejection, handler failures, lookup URI dispatch without re-reading a grant, and repeated service/gesture dispatch. Existing keyboard and settings regression tests also run. OEM contacts viewer behavior, carrier emergency databases, managed profiles and dual-SIM UI still require a device review without completing calls before release.

Cloud verification on 2026-11-18:

- `./gradlew --no-daemon -PunsignedRelease=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: passed with JDK 17; 173 tests passed on API 33, zero failures/errors/skips. Lint passed with warnings. The debug APK targets API 36 and uses the existing arm64 native build.
- `git diff --check`: passed.
- API 36 Robolectric execution was attempted with the available Java 21 runtime. It remains **unverified** because downloading `org.robolectric:android-all-instrumented:16-robolectric-13921718-i7` is rejected with HTTP 403 by the cloud network. This is a test-runtime dependency failure, not a passing API 36 runtime check.
- No physical device, live carrier, actual call, release build, merge or publication was used for validation.
