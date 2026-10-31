package ai.boldkeys

/** Labels describe available controls, not what the editor contains. */
internal fun spokenKey(value: String): String = when (value) {
    " " -> "Space"; "." -> "Period"; "," -> "Comma"; "?" -> "Question mark"; "!" -> "Exclamation mark"
    "@" -> "At sign"; "#" -> "Number sign"; "$" -> "Dollar sign"; "€" -> "Euro sign"; "£" -> "Pound sign"
    "'" -> "Apostrophe"; "\"" -> "Quotation mark"; ":" -> "Colon"; ";" -> "Semicolon"
    "/" -> "Slash"; "\\" -> "Backslash"; "(" -> "Left parenthesis"; ")" -> "Right parenthesis"
    "-" -> "Hyphen"; "_" -> "Underscore"; "+" -> "Plus"; "=" -> "Equals"; "*" -> "Asterisk"
    else -> when {
        value.length == 1 && value[0].isUpperCase() -> "Capital $value"
        value.codePointCount(0, value.length) == 1 && !Character.isLetterOrDigit(value.codePointAt(0)) -> Character.getName(value.codePointAt(0))?.lowercase() ?: value
        else -> value
    }
}
