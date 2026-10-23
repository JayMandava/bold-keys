package ai.boldkeys

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator

/** Recognises the complete character before the first space, not just its final UTF-16 unit. */
internal object DoubleSpacePeriod {
    // Enough context for emoji sequences (including family, skin-tone and subdivision flags),
    // without asking the editor for the whole message on every space.
    const val LOOK_BEHIND = 64
    const val INTERVAL_MS = 700L

    fun shouldReplace(before: String, now: Long, lastSpaceAt: Long): Boolean {
        if (lastSpaceAt <= 0L || now - lastSpaceAt !in 0 until INTERVAL_MS || !before.endsWith(' ')) return false
        val end = before.length - 1
        if (end == 0) return false
        val characters = BreakIterator.getCharacterInstance()
        characters.setText(before)
        val start = characters.preceding(end)
        if (start == BreakIterator.DONE) return false
        val base = before.codePointAt(start)
        if (Character.isLetterOrDigit(base) || base == ')'.code || base == '"'.code || base == '\''.code) return true
        // # and * have the Emoji property only because they can start keycaps. A plain
        // symbol must keep its two spaces; the enclosing keycap makes it an emoji.
        if (base == '#'.code || base == '*'.code) return before.substring(start, end).contains('\u20E3')
        return UCharacter.hasBinaryProperty(base, UProperty.EMOJI)
    }
}
