package ai.boldkeys

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ComponentActivity
    private val saved = mutableListOf<Shortcut>()
    private var dismissed = false

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.getSharedPreferences("bk", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(activity.application).denyPermissions(Manifest.permission.CALL_PHONE)
    }

    private fun show(current: Shortcut? = null) {
        activity.setContent { MaterialTheme {
            ShortcutDialog(activity, 'A', current, onDismiss = { dismissed = true }) { saved += it }
        } }
        compose.waitForIdle()
    }

    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (text !in listOf("Save", "Cancel")) node.performScrollTo()
        node.performClick()
    }

    @Test fun existingTextCanBeEditedAndCancelDoesNotSave() {
        show(Shortcut(Shortcut.TEXT, "old text", "Old"))
        compose.onNodeWithText("old text").performTextReplacement("new text")
        click("Cancel")
        assertTrue(dismissed)
        assertTrue(saved.isEmpty())
    }

    @Test fun cancelledPhonePickerDoesNotReplaceExistingNumber() {
        show(Shortcut(Shortcut.DIRECT_CALL, "+15555550123", "Old person"))
        click("Choose phone number")
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_PICK, started.intent.action)
        assertEquals(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE, started.intent.type)
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        compose.waitForIdle()
        compose.onNodeWithText("+15555550123").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertTrue(saved.isEmpty())
    }

    @Test fun selectedNumberMustBeReviewedAndSavedExplicitly() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.CALL_PHONE)
        show()
        click("Direct Call")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        click("Choose phone number")
        val started = shadowOf(activity).nextStartedActivityForResult
        val uri = Uri.parse("content://com.android.contacts/data/8")
        val row = MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
        row.addRow(arrayOf("+1 (555) 555-0124", "Chosen person"))
        selectedRow(uri, row)
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(uri))
        compose.waitForIdle()
        assertTrue(saved.isEmpty())
        click("Save")
        assertEquals(Shortcut.DIRECT_CALL, saved.single().type)
        assertEquals("+15555550124", saved.single().value)
        generateSequence { shadowOf(activity).nextStartedActivity }.forEach { assertNotEquals(Intent.ACTION_CALL, it.action) }
    }

    @Test fun permissionDenialNeverSavesOrPlacesACall() {
        show(Shortcut(Shortcut.DIRECT_CALL, "+15555550123", "Person"))
        click("Enable Direct Call permission")
        val denied = shadowOf(activity).lastRequestedPermission
        activity.onRequestPermissionsResult(denied.requestCode, denied.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        compose.waitForIdle()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertTrue(saved.isEmpty())
        generateSequence { shadowOf(activity).nextStartedActivity }.forEach { assertNotEquals(Intent.ACTION_CALL, it.action) }
    }

    @Test fun permissionGrantNeverSavesOrPlacesACall() {
        show(Shortcut(Shortcut.DIRECT_CALL, "+15555550123", "Person"))
        click("Enable Direct Call permission")
        val allowed = shadowOf(activity).lastRequestedPermission
        shadowOf(activity.application).grantPermissions(Manifest.permission.CALL_PHONE)
        compose.runOnUiThread {
            activity.onRequestPermissionsResult(allowed.requestCode, allowed.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Save").assertIsEnabled()
        assertTrue(saved.isEmpty())
        generateSequence { shadowOf(activity).nextStartedActivity }.forEach { assertNotEquals(Intent.ACTION_CALL, it.action) }
        click("Save")
        assertEquals(1, saved.size)
        generateSequence { shadowOf(activity).nextStartedActivity }.forEach { assertNotEquals(Intent.ACTION_CALL, it.action) }
    }

    @Test fun directCallEditorRemainsScrollableAtTwoHundredPercentText() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try {
            show(Shortcut(Shortcut.DIRECT_CALL, "+15555550123", "A selected contact with a long display name"))
            compose.onNodeWithText("Choose phone number").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Enable Direct Call permission").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Save").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
        } finally { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun contactSelectionHasExplicitSaveAndNoPhonePermissionControl() {
        show()
        click("Contact")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Enable Direct Call permission").assertDoesNotExist()
        click("Choose contact")
        val started = shadowOf(activity).nextStartedActivityForResult
        val uri = Uri.parse("content://com.android.contacts/contacts/42")
        val row = MatrixCursor(arrayOf("_id", "lookup", "display_name"))
        row.addRow(arrayOf(42, "stable-key", "Person"))
        selectedRow(uri, row)
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(uri))
        compose.waitForIdle()
        assertTrue(saved.isEmpty())
        click("Save")
        assertTrue(SelectedContacts.isLookupUri(Uri.parse(saved.single().value)))
        assertEquals(Shortcut.CONTACT, saved.single().type)
    }
}
