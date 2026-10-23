package ai.boldkeys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubleSpacePeriodTest {
    private fun replaces(text: String, now: Long = 1200, lastSpaceAt: Long = 1000) =
        DoubleSpacePeriod.shouldReplace(text.takeLast(DoubleSpacePeriod.LOOK_BEHIND), now, lastSpaceAt)

    @Test fun replacesAfterCompleteEmojiGraphemes() {
        val emoji = listOf(
            "🙂", // supplementary code point
            "❤️", "☀️", // variation selectors
            "👍🏽", // skin tone
            "👩🏾‍💻", "👨‍👩‍👧‍👦", "🏳️‍🌈", // ZWJ sequences
            "🇬🇧", "🇮🇳", // pairs of regional indicators
            "#️⃣", "*️⃣", "1️⃣", // keycaps
            "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F", // England tag flag
        )
        for (item in emoji) {
            assertTrue("Emoji alone: $item", replaces("$item "))
            assertTrue("Emoji after text: $item", replaces("That's great $item "))
            assertTrue("Emoji at bounded context edge: $item", replaces("x".repeat(100) + "$item "))
        }
    }

    @Test fun preservesWordsDigitsAndClosingPunctuation() {
        for (text in listOf("Hello", "42", "café", "cafe\u0301", "हिन्दी", "𐐀", "word)", "word\"", "word'")) {
            assertTrue(text, replaces("$text "))
        }
    }

    @Test fun doesNotReplaceAfterWhitespaceOrNonEndingSymbols() {
        for (text in listOf("", " ", "  ", "\n ", "\t ", "word. ", "word! ", "word? ", "🙂. ", "🙂  ", "# ", "* ", "@ ", "$ ", "- ", "( ", "\uFE0F ", "\u200D ", "\u0301 ", "\uDC42 ")) {
            assertFalse(text, replaces(text))
        }
    }

    @Test fun requiresAFirstSpaceAndTheExistingTimingWindow() {
        assertFalse(replaces("🙂"))
        assertFalse(replaces("🙂 ", lastSpaceAt = 0))
        assertFalse(replaces("🙂 ", now = 999))
        assertTrue(replaces("🙂 ", now = 1000))
        assertTrue(replaces("🙂 ", now = 1699))
        assertFalse(replaces("🙂 ", now = 1700))
        assertFalse(replaces("🙂 ", now = 2200))
    }

    @Test fun reproducesTheOldUtf16Bug() {
        fun oldRule(before: String): Boolean {
            val tail = before.takeLast(2)
            return tail.length == 2 && tail[1] == ' ' && (tail[0].isLetterOrDigit() || tail[0] in ")\"'")
        }
        for (emoji in listOf("🙂", "❤️", "👍🏽", "👩🏾‍💻", "🇬🇧", "#️⃣")) {
            assertFalse("Old rule unexpectedly accepts $emoji", oldRule("$emoji "))
            assertTrue("New rule rejects $emoji", replaces("$emoji "))
        }
    }
}
