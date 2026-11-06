package ai.boldkeys

import android.view.inputmethod.InputConnection

/** Recheck the same connection and surrounding text before presenting or inserting delayed output. */
internal data class VoiceEditorAnchor(private val connection: InputConnection, private val before: String,
    private val after: String, private val selected: String?) {
    fun matches(current: InputConnection): Boolean = current === connection && capture(current) == this
    companion object {
        fun capture(connection: InputConnection): VoiceEditorAnchor? {
            val before = connection.getTextBeforeCursor(256, 0)?.toString() ?: return null
            val after = connection.getTextAfterCursor(256, 0)?.toString() ?: return null
            return VoiceEditorAnchor(connection, before, after, connection.getSelectedText(0)?.toString())
        }
    }
}
