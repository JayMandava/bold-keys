package ai.boldkeys

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real emulator window captures only. Never launches a picker, requests permission or calls. */
@RunWith(AndroidJUnit4::class)
class SpeedKeyEditorCapture {
    @get:Rule val compose = createAndroidComposeRule<SetupActivity>()

    @Test fun captureContactAndDirectCallEditors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnUiThread {
            context.getSharedPreferences("bk", Context.MODE_PRIVATE).edit().clear().commit()
            Shortcuts.set(context, 'A', Shortcut(Shortcut.CONTACT,
                "content://com.android.contacts/contacts/lookup/synthetic/42", "Synthetic Person"))
            Shortcuts.set(context, 'B', Shortcut(Shortcut.DIRECT_CALL, "+15555550124", "Synthetic Person"))
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Speed key"))
        compose.onNodeWithText("Speed key").performScrollTo().performClick()
        for ((letter, type) in listOf("A" to "contact", "B" to "direct-call")) {
            compose.onNodeWithText(letter).performScrollTo().performClick()
            capture(context, "$type-top")
            if (letter == "B") {
                compose.onNodeWithText("+15555550124").performScrollTo().assertIsDisplayed()
                capture(context, "$type-number")
            }
            compose.onNodeWithText(if (letter == "A") "Choose contact" else "Enable Direct Call permission")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Save").assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            capture(context, "$type-controls")
            compose.onNodeWithText("Cancel").performClick()
        }
    }

    private fun capture(context: Context, name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val config = context.resources.configuration
        val directory = File(context.filesDir, "speed-key-captures").apply { mkdirs() }
        val prefix = "${config.screenWidthDp}dp-${(config.fontScale * 100).toInt()}pct-$name"
        File(directory, "$prefix.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(directory, "$prefix-semantics.txt").writeText(
            compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE))
    }
}
