package ai.boldkeys

/** Stable preference IDs: existing Black, White and Match phone selections keep their meaning. */
enum class KeyColour(val id: String, val label: String, val dark: Boolean, val inspiration: String) {
    BLACK("dark", "Black", true, "The original black keys and silver trim."),
    WHITE("light", "White", false, "The original white keys and silver trim."),
    RED("red", "Red", true, "Black keys and a crimson frame, inspired by the BlackBerry KEY2 Red Edition."),
    BLACK_GOLD("black_gold", "Black & Gold", true, "Black keys and gold trim, inspired by the limited black-and-gold BlackBerry Passport."),
    WHITE_GOLD("white_gold", "White & Gold", false, "Ivory keys and gold trim, inspired by the white-and-gold BlackBerry Q10 Special Edition."),
    ;

    companion object {
        fun resolve(id: String, systemDark: Boolean = true): KeyColour =
            if (id == "auto") { if (systemDark) BLACK else WHITE }
            else entries.firstOrNull { it.id == id } ?: BLACK
        val choices: List<Pair<String, String>> = listOf(
            BLACK.id to BLACK.label, WHITE.id to WHITE.label, "auto" to "Match phone",
            RED.id to RED.label, BLACK_GOLD.id to BLACK_GOLD.label, WHITE_GOLD.id to WHITE_GOLD.label,
        )
    }
}

/** Every keyboard surface belongs to one palette. Heritage colours are interpretations, not official colour specifications. */
class KeyTheme(val colour: KeyColour) {
    constructor(dark: Boolean) : this(if (dark) KeyColour.BLACK else KeyColour.WHITE)
    val dark = colour.dark
    private val heritage = colour !in setOf(KeyColour.BLACK, KeyColour.WHITE)
    private val red = colour == KeyColour.RED
    private val gold = colour == KeyColour.BLACK_GOLD || colour == KeyColour.WHITE_GOLD
    private val ivory = colour == KeyColour.WHITE_GOLD

    val bg = when (colour) {
        KeyColour.RED -> 0xFF6E101B.toInt()
        KeyColour.BLACK_GOLD -> 0xFF14120F.toInt()
        KeyColour.WHITE_GOLD -> 0xFFE5DFD1.toInt()
        else -> if (dark) 0xFF000000.toInt() else 0xFFe6e8ec.toInt()
    }
    val base = when {
        ivory -> floatArrayOf(250f, 247f, 238f)
        colour == KeyColour.BLACK_GOLD -> floatArrayOf(24f, 24f, 24f)
        heritage -> floatArrayOf(32f, 33f, 36f)
        dark -> floatArrayOf(36f, 44f, 54f)
        else -> floatArrayOf(254f, 254f, 255f)
    }
    val baseSpecial = when {
        ivory -> floatArrayOf(238f, 233f, 220f)
        colour == KeyColour.BLACK_GOLD -> floatArrayOf(22f, 22f, 22f)
        heritage -> floatArrayOf(27f, 28f, 30f)
        dark -> floatArrayOf(31f, 38f, 47f)
        else -> floatArrayOf(238f, 241f, 246f)
    }
    val ambient = if (dark) 0.24f else 0.72f
    val diffuse = if (dark) 1.30f else 0.38f
    val specular = if (dark) 0.5f else 0.12f
    val wallTop = when { ivory -> 0xFFC9C1AF.toInt(); heritage -> 0xFF303033.toInt(); dark -> 0xFF2d343d.toInt(); else -> 0xFFc9ced5.toInt() }
    val wallBottom = when { ivory -> 0xFF89806D.toInt(); heritage -> 0xFF060608.toInt(); dark -> 0xFF040608.toInt(); else -> 0xFF7e848d.toInt() }
    val cast = if (dark) 0x99000000.toInt() else 0x33000000
    val legend = if (colour == KeyColour.BLACK_GOLD) 0xFFF6F0E1.toInt() else if (ivory) 0xFF28251E.toInt() else if (dark) 0xFFF4F6F8.toInt() else 0xFF1b1f24.toInt()
    val legendShadow = if (dark) 0xAA000000.toInt() else 0x66FFFFFF
    val panel = when { ivory -> 0xF8F3EFE5.toInt(); heritage -> 0xF4101012.toInt(); dark -> 0xF4090a0c.toInt(); else -> 0xF8eef0f3.toInt() }
    val padTop = when { ivory -> 0xFFFFFCF4.toInt(); heritage -> 0xFF37373A.toInt(); dark -> 0xFF343c46.toInt(); else -> 0xFFfbfcfd.toInt() }
    val padBottom = when { ivory -> 0xFFE6DFCF.toInt(); heritage -> 0xFF19191C.toInt(); dark -> 0xFF171c22.toInt(); else -> 0xFFe3e6ea.toInt() }
    val pillTop = when { ivory -> 0xFFF8F4EA.toInt(); heritage -> 0xFF303033.toInt(); dark -> 0xFF2b323b.toInt(); else -> 0xFFf6f7f9.toInt() }
    val pillBottom = when { ivory -> 0xFFE4DDCC.toInt(); heritage -> 0xFF141416.toInt(); dark -> 0xFF12161b.toInt(); else -> 0xFFdde0e5.toInt() }
    val barText = legend
    val barText2 = if (ivory) 0xFF5E5748.toInt() else if (dark) 0xFFB4B8BE.toInt() else 0xFF565c64.toInt()
    val divider = if (dark) 0x22FFFFFF else 0x26000000

    // The frame, row separators and trackpad ring carry the edition colour, not the key legends.
    val trimShadow = when { red -> 0xFF6E101B.toInt(); colour == KeyColour.BLACK_GOLD -> 0xFF8B6B2E.toInt(); gold -> 0xFF786020.toInt(); else -> 0xFF8D9197.toInt() }
    val trimHighlight = when { red -> 0xFFDF4553.toInt(); colour == KeyColour.BLACK_GOLD -> 0xFFF0DCA6.toInt(); gold -> 0xFFE9D58D.toInt(); else -> 0xFFF2F4F6.toInt() }
    val trimMid = when { red -> 0xFFB62532.toInt(); colour == KeyColour.BLACK_GOLD -> 0xFFD6B66A.toInt(); gold -> 0xFFBE9A47.toInt(); else -> 0xFFA9ADB3.toInt() }
    val hasColouredFrame = heritage
    val ridgeWidth = if (gold) 2.2f else if (red) 2f else 1.5f
    val ridgeColours = intArrayOf(trimShadow, trimHighlight, trimMid, trimHighlight, trimShadow)
    val ringBottom = if (heritage) trimShadow else 0xFF70747B.toInt()
    val indicator = when { red -> trimHighlight; colour == KeyColour.BLACK_GOLD -> trimMid; ivory -> 0xFF786020.toInt(); else -> 0xFF2F8FE8.toInt() }
    val selection = when { red -> 0xFF9D1C2C.toInt(); gold -> 0xFF786020.toInt(); else -> 0xFF2F8FE8.toInt() }
}
