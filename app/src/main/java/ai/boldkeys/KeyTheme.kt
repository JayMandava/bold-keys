package ai.boldkeys

/** The two looks of the keyboard: the black 9900 and the white one. Every colour the keyboard draws with comes from here. */
class KeyTheme(val dark: Boolean) {
    val bg = if (dark) 0xFF000000.toInt() else 0xFFe6e8ec.toInt()
    val base = if (dark) floatArrayOf(36f, 44f, 54f) else floatArrayOf(254f, 254f, 255f)
    val baseSpecial = if (dark) floatArrayOf(31f, 38f, 47f) else floatArrayOf(238f, 241f, 246f)
    val ambient = if (dark) 0.24f else 0.72f
    val diffuse = if (dark) 1.30f else 0.38f
    val specular = if (dark) 0.5f else 0.12f
    val wallTop = if (dark) 0xFF2d343d.toInt() else 0xFFc9ced5.toInt()
    val wallBottom = if (dark) 0xFF040608.toInt() else 0xFF7e848d.toInt()
    val cast = if (dark) 0x99000000.toInt() else 0x33000000
    val legend = if (dark) 0xFFF4F6F8.toInt() else 0xFF1b1f24.toInt()
    val legendShadow = if (dark) 0xAA000000.toInt() else 0x66FFFFFF
    val panel = if (dark) 0xF4090a0c.toInt() else 0xF8eef0f3.toInt()
    val padTop = if (dark) 0xFF343c46.toInt() else 0xFFfbfcfd.toInt()
    val padBottom = if (dark) 0xFF171c22.toInt() else 0xFFe3e6ea.toInt()
    val pillTop = if (dark) 0xFF2b323b.toInt() else 0xFFf6f7f9.toInt()
    val pillBottom = if (dark) 0xFF12161b.toInt() else 0xFFdde0e5.toInt()
    val barText = if (dark) 0xFFF4F6F8.toInt() else 0xFF1b1f24.toInt()
    val barText2 = if (dark) 0xFFB4B8BE.toInt() else 0xFF565c64.toInt()
    val divider = if (dark) 0x22FFFFFF else 0x26000000
}
