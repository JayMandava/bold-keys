package ai.boldkeys

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class ContactShortcutsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val launches = mutableListOf<Intent>()
    private lateinit var context: Context

    @Before fun setup() {
        app.getSharedPreferences("bk", 0).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
        context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
    }

    @Test fun oldJsonAndEveryNewAssignmentRoundTripWithoutMigration() {
        Prefs.put(app, "shortcuts", """{"A":{"t":"text","v":"hello","l":"Greeting"},"B":{"t":"link","v":"example.com","l":"Web"},"C":{"t":"app","v":"old.package","l":"Old app"}}""")
        val contact = Shortcut(Shortcut.CONTACT, "content://com.android.contacts/contacts/lookup/stable/42", "Person")
        val call = Shortcut(Shortcut.DIRECT_CALL, "+15555550123", "Person")
        Shortcuts.set(app, 'd', contact); Shortcuts.set(app, 'E', call)
        assertEquals("hello", Shortcuts.get(app, 'a')!!.value)
        assertEquals(Shortcut.LINK, Shortcuts.get(app, 'B')!!.type)
        assertEquals("old.package", Shortcuts.get(app, 'C')!!.value)
        assertEquals(contact.value, Shortcuts.get(app, 'D')!!.value)
        assertEquals(call.value, Shortcuts.get(app, 'e')!!.value)
        Shortcuts.set(app, 'D', null)
        assertNull(Shortcuts.get(app, 'd'))
        assertEquals(4, Shortcuts.all(app).size)
    }

    @Test fun oldTextLinkAndMissingAppBehavior() {
        assertEquals("hello", Shortcuts.execute(context, Shortcut(Shortcut.TEXT, "hello", "")).text)
        assertNull(Shortcuts.execute(context, Shortcut(Shortcut.LINK, "example.com", "")).error)
        assertEquals("https://example.com", launches.single().data.toString())
        assertNotNull(Shortcuts.execute(context, Shortcut(Shortcut.APP, "missing.package", "")).error)
    }

    @Test fun installedAppStillLaunchesWithNewTaskFlag() {
        val pkg = "example.chosen.app"
        val resolved = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = pkg; name = "$pkg.MainActivity"; exported = true; enabled = true
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg; enabled = true }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), resolved)
        assertNull(Shortcuts.execute(context, Shortcut(Shortcut.APP, pkg, "Chosen app")).error)
        assertEquals(pkg, launches.single().component!!.packageName)
        assertTrue(launches.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun legacyTelephoneLinkRemainsAViewActionWithoutCallPermission() {
        assertNull(Shortcuts.execute(context, Shortcut(Shortcut.LINK, "tel:+15555550123", "Phone link")).error)
        assertEquals(Intent.ACTION_VIEW, launches.single().action)
        assertEquals("tel", launches.single().data!!.scheme)
    }

    @Test fun denialAndRevocationDoNothingAndGrantDoesNotReplay() {
        val call = Shortcut(Shortcut.DIRECT_CALL, "+15555550123", "Person")
        assertNotNull(Shortcuts.execute(context, call).error)
        assertTrue(launches.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        assertTrue(launches.isEmpty())
        assertNull(Shortcuts.execute(context, call).error)
        assertEquals(Intent.ACTION_CALL, launches.single().action)
        assertEquals("tel", launches.single().data!!.scheme)
        assertEquals("+15555550123", launches.single().data!!.schemeSpecificPart)
        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
        assertNotNull(Shortcuts.execute(context, call).error)
        assertEquals(1, launches.size)
    }

    @Test fun unsafeNumbersNeverLaunchEvenWithPermission() {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        for (number in listOf("", "911", "112", "+911", "999", "*123#", "**21*15555550123#", "123,45", "123;45", "tel:12345", "12+345", "1-800-CALL", "12", "1234567890123456")) {
            assertNotNull(number, Shortcuts.execute(context, Shortcut(Shortcut.DIRECT_CALL, number, "")).error)
        }
        assertTrue(launches.isEmpty())
        assertEquals("+15555550123", DirectCalls.normalize("+1 (555) 555-0123"))
    }

    @Test fun missingHandlerAndSecurityFailureHaveNoFallback() {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        for (failure in listOf(ActivityNotFoundException(), SecurityException())) {
            var attempts = 0
            val blocked = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) { attempts++; assertEquals(Intent.ACTION_CALL, intent.action); throw failure }
            }
            assertNotNull(Shortcuts.execute(blocked, Shortcut(Shortcut.DIRECT_CALL, "+15555550123", "")).error)
            assertEquals(1, attempts)
        }
    }

    @Test fun contactUsesLookupIdentityWithoutReadingTemporaryGrantAgain() {
        val value = "content://com.android.contacts/contacts/lookup/stable%20key/42"
        assertNull(Shortcuts.execute(context, Shortcut(Shortcut.CONTACT, value, "Person")).error)
        assertEquals(Intent.ACTION_VIEW, launches.single().action)
        assertEquals(value, launches.single().data.toString())
        for (bad in listOf("content://com.android.contacts/contacts/42", "https://example.com", "content://other/contacts/lookup/a")) {
            assertNotNull(Shortcuts.execute(context, Shortcut(Shortcut.CONTACT, bad, "")).error)
        }
        assertEquals(1, launches.size)
    }

    @Test fun pickerReadsOnlySelectedRowAndCopiesNumber() {
        val uri = Uri.parse("content://com.android.contacts/data/7")
        val cursor = MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
        cursor.addRow(arrayOf("+1 (555) 555-0123", "Person"))
        selectedRow(uri, cursor)
        val selected = SelectedContacts.read(app, uri, true)!!
        assertEquals("+15555550123", selected.value)
        assertTrue(cursor.isClosed)
        assertEquals(Shortcut.DIRECT_CALL, selected.type)
        Shortcuts.set(app, 'A', selected)
        val changed = MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
        changed.addRow(arrayOf("+15555550999", "Changed person"))
        selectedRow(uri, changed)
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        assertNull(Shortcuts.execute(context, Shortcuts.get(app, 'A')!!).error)
        assertEquals("+15555550123", launches.single().data!!.schemeSpecificPart)
        assertFalse(changed.isClosed) // Dispatch did not re-query the changed contact.
        assertEquals(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE, SelectedContacts.picker(true).type)
        assertEquals(ContactsContract.Contacts.CONTENT_TYPE, SelectedContacts.picker(false).type)
        assertNull(SelectedContacts.read(app, Uri.parse("content://other/data/7"), true))
    }

    @Test fun staleOrUnreadableSelectionPreservesExistingAssignment() {
        Shortcuts.set(app, 'A', Shortcut(Shortcut.TEXT, "keep", "Keep"))
        assertNull(SelectedContacts.read(app, Uri.parse("content://com.android.contacts/contacts/404"), false))
        assertEquals("keep", Shortcuts.get(app, 'A')!!.value)
    }

    @Test fun serviceConsumesRepeatedCallGestureWithoutLaunchingTwice() {
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        val service = org.robolectric.Robolectric.buildService(BoldKeysService::class.java).get()
        Shortcuts.set(app, 'A', Shortcut(Shortcut.DIRECT_CALL, "+15555550123", "Person"))
        service.onShortcutGestureStart()
        assertTrue(service.onShortcut('A'))
        assertEquals(Intent.ACTION_CALL, shadowOf(app).nextStartedActivity.action)
        assertTrue(service.onShortcut('A'))
        assertNull(shadowOf(app).nextStartedActivity)
        service.onShortcutGestureStart()
        assertTrue(service.onShortcut('A'))
        assertNull(shadowOf(app).nextStartedActivity)
        assertFalse(service.onShortcut('Z'))
    }

    @Test fun repeatedDispatchIsBlockedUntilNewGestureAndCooldown() {
        var now = 0L
        val guard = DirectCallGuard { now }
        guard.beginGesture(); assertTrue(guard.acquire()); assertFalse(guard.acquire())
        now = 10_000; assertFalse(guard.acquire()) // Still holding Speed.
        guard.beginGesture(); assertTrue(guard.acquire())
        guard.beginGesture(); assertFalse(guard.acquire()) // Double tap or accessibility repeat.
        now += 3000; assertTrue(guard.acquire())
    }
}
