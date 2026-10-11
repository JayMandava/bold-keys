package ai.boldkeys

/** The two looks of the keyboard: the black 9900 and the white one. Every colour the keyboard draws with comes from here. */
class KeyTheme(val dark: Boolean) {
    val bg = if (dark) 0xFF000000.toInt() else 0xFFc7cad0.toInt()
    val base = if (dark) floatArrayOf(36f, 44f, 54f) else floatArrayOf(228f, 231f, 237f)
    val baseSpecial = if (dark) floatArrayOf(31f, 38f, 47f) else floatArrayOf(206f, 210f, 217f)
    val ambient = if (dark) 0.24f else 0.50f
    val diffuse = if (dark) 1.30f else 0.64f
    val specular = if (dark) 0.5f else 0.22f
    val wallTop = if (dark) 0xFF2d343d.toInt() else 0xFFaab0b8.toInt()
    val wallBottom = if (dark) 0xFF040608.toInt() else 0xFF50555d.toInt()
    val cast = if (dark) 0x99000000.toInt() else 0x44000000
    val legend = if (dark) 0xFFF4F6F8.toInt() else 0xFF1b1f24.toInt()
    val legendShadow = if (dark) 0xAA000000.toInt() else 0x66FFFFFF
    val panel = if (dark) 0xF4090a0c.toInt() else 0xF2dcdfe3.toInt()
    val padTop = if (dark) 0xFF343c46.toInt() else 0xFFf6f7f9.toInt()
    val padBottom = if (dark) 0xFF171c22.toInt() else 0xFFcdd1d7.toInt()
    val pillTop = if (dark) 0xFF2b323b.toInt() else 0xFFe9ebef.toInt()
    val pillBottom = if (dark) 0xFF12161b.toInt() else 0xFFbfc3ca.toInt()
    val barText = if (dark) 0xFFF4F6F8.toInt() else 0xFF1b1f24.toInt()
    val barText2 = if (dark) 0xFFB4B8BE.toInt() else 0xFF565c64.toInt()
    val divider = if (dark) 0x22FFFFFF else 0x33000000
}
