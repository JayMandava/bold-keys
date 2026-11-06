package ai.boldkeys

import android.view.inputmethod.InputConnection

/** Recheck the same connection and surrounding text before presenting or inserting delayed output. */
internal data class VoiceEditorAnchor(private val connection: InputConnection, private val before: String,
    private val after: String, private val selected: String?) {
    fun matches(current: InputConnection): Boolean = current === connection && capture(current) == this
    /** Space-delimited dictation joins surrounding words without changing any recognized word. */
    fun insertion(text: String): String {
        if (!selected.isNullOrEmpty() || text.isEmpty()) return text
        fun word(cp: Int) = Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.OTHER_SYMBOL.toInt()
        val left = before.takeIf { it.isNotEmpty() }?.let { it.codePointBefore(it.length) }
        val right = after.takeIf { it.isNotEmpty() }?.codePointAt(0)
        val prefix = left != null && (word(left) || left.toChar() in ",.!?:;)]}") && word(text.codePointAt(0))
        val suffix = right != null && word(right) && !text.last().isWhitespace()
        return (if (prefix) " " else "") + text + (if (suffix) " " else "")
    }
    companion object {
        fun capture(connection: InputConnection): VoiceEditorAnchor? {
            val before = connection.getTextBeforeCursor(256, 0)?.toString() ?: return null
            val after = connection.getTextAfterCursor(256, 0)?.toString() ?: return null
            return VoiceEditorAnchor(connection, before, after, connection.getSelectedText(0)?.toString())
        }
    }
}
