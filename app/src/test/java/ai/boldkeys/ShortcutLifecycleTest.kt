package ai.boldkeys

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual SetupActivity save/restore and ActivityResultRegistry callbacks, with selected-row doubles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<SetupActivity>
    private val activity get() = controller.get()

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("bk", 0).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
        Shortcuts.set(app, 'A', Shortcut(Shortcut.TEXT, "existing assignment", "Existing"))
        controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Speed key"))
        compose.onNodeWithText("Speed key").performScrollTo().performClick()
        compose.onNodeWithText("A").performScrollTo().performClick()
    }

    @After fun cleanup() { controller.pause().stop().destroy() }

    private fun click(label: String) {
        val node = compose.onNodeWithText(label)
        if (label !in listOf("Save", "Cancel")) node.performScrollTo()
        node.performClick()
    }

    private fun recreate() {
        compose.runOnUiThread { controller.recreate() }
        compose.waitForIdle()
        compose.onNodeWithText("Speed key + A").assertIsDisplayed()
    }

    private fun choose(call: Boolean): org.robolectric.shadows.ShadowActivity.IntentForResult {
        click(if (call) "Direct Call" else "Contact")
        click(if (call) "Choose phone number" else "Choose contact")
        return shadowOf(activity).nextStartedActivityForResult
    }

    private fun result(requestCode: Int, call: Boolean) {
        val uri = Uri.parse(if (call) "content://com.android.contacts/data/8" else "content://com.android.contacts/contacts/42")
        val row = if (call) MatrixCursor(arrayOf("data1", "display_name")).apply {
            addRow(arrayOf("+15555550124", "Synthetic Person"))
        } else MatrixCursor(arrayOf("_id", "lookup", "display_name")).apply {
            addRow(arrayOf(42, "stable-key", "Synthetic Person"))
        }
        selectedRow(uri, row)
        compose.runOnUiThread { shadowOf(activity).callOnActivityResult(requestCode, Activity.RESULT_OK, Intent().setData(uri)) }
        compose.waitForIdle()
        assertTrue(row.isClosed)
        compose.onNodeWithText("Synthetic Person").assertExists()
    }

    private fun unchangedAndNoCall() {
        assertEquals("existing assignment", Shortcuts.get(activity, 'A')!!.value)
        generateSequence { shadowOf(activity).nextStartedActivity }.forEach {
            assertNotEquals(Intent.ACTION_CALL, it.action)
            assertNotEquals(Intent.ACTION_DIAL, it.action)
        }
    }

    @Test fun contactResultReturnedAfterRecreationKeepsPickerTypeAndRequiresSave() {
        val request = choose(false)
        recreate()
        result(request.requestCode, false)
        unchangedAndNoCall()
        click("Save")
        assertEquals(Shortcut.CONTACT, Shortcuts.get(activity, 'A')!!.type)
        assertTrue(SelectedContacts.isLookupUri(Uri.parse(Shortcuts.get(activity, 'A')!!.value)))
    }

    @Test fun phoneResultReturnedAfterRecreationKeepsSpecificNumberAndRequiresSave() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.CALL_PHONE)
        val request = choose(true)
        recreate()
        result(request.requestCode, true)
        unchangedAndNoCall()
        click("Save")
        assertEquals("+15555550124", Shortcuts.get(activity, 'A')!!.value)
    }

    @Test fun selectedContactBeforeRecreationSurvivesWithoutRequeryingGrant() {
        val request = choose(false)
        result(request.requestCode, false)
        recreate()
        compose.onNodeWithText("Synthetic Person").assertExists()
        unchangedAndNoCall()
        click("Save")
        assertEquals(Shortcut.CONTACT, Shortcuts.get(activity, 'A')!!.type)
    }

    @Test fun selectedPhoneBeforeRecreationSurvivesWithoutRequeryingGrant() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.CALL_PHONE)
        val request = choose(true)
        result(request.requestCode, true)
        recreate()
        compose.onNodeWithText("+15555550124").assertExists()
        unchangedAndNoCall()
        click("Save")
        assertEquals("+15555550124", Shortcuts.get(activity, 'A')!!.value)
    }

    @Test fun cancelledPickerAfterRecreationPreservesAssignment() {
        val request = choose(true)
        recreate()
        compose.runOnUiThread { shadowOf(activity).callOnActivityResult(request.requestCode, Activity.RESULT_CANCELED, null) }
        compose.waitForIdle()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        unchangedAndNoCall()
        click("Cancel")
        assertEquals("existing assignment", Shortcuts.get(activity, 'A')!!.value)
    }

    private fun permissionAfterRecreation(granted: Boolean) {
        val request = choose(true)
        result(request.requestCode, true)
        click("Enable Direct Call permission")
        val permission = shadowOf(activity).lastRequestedPermission
        recreate()
        if (granted) shadowOf(activity.application).grantPermissions(Manifest.permission.CALL_PHONE)
        compose.runOnUiThread {
            activity.onRequestPermissionsResult(permission.requestCode, permission.requestedPermissions,
                intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
        }
        compose.waitForIdle()
        if (granted) compose.onNodeWithText("Save").assertIsEnabled()
        else compose.onNodeWithText("Save").assertIsNotEnabled()
        unchangedAndNoCall()
        if (granted) {
            click("Save")
            assertEquals("+15555550124", Shortcuts.get(activity, 'A')!!.value)
            generateSequence { shadowOf(activity).nextStartedActivity }.forEach { assertNotEquals(Intent.ACTION_CALL, it.action) }
        }
    }

    @Test fun permissionGrantAfterRecreationNeverSavesOrCallsUntilExplicitSave() = permissionAfterRecreation(true)
    @Test fun permissionDenialAfterRecreationLeavesAssignmentUntouched() = permissionAfterRecreation(false)
}
