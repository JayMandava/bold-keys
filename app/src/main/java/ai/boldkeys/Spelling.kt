package ai.boldkeys

/**
 * American and British spellings of the same word. The pairs are built from short, careful lists (so words like "program", "tire" or
 * "license", which differ in meaning rather than spelling, are left alone) and then only kept when the American form is a known word.
 */
object Spelling {
    private val OUR = listOf("color", "favor", "honor", "humor", "neighbor", "labor", "flavor", "behavior", "harbor", "rumor", "vapor", "savior", "odor", "armor", "endeavor", "tumor", "glamor", "parlor", "rigor", "vigor", "valor", "splendor", "saber", "fervor")
    private val OUR_SUFFIX = listOf("", "s", "ed", "ing", "ful", "fully", "less", "ite", "ites", "able", "ably", "y", "ist", "ists", "ful", "ings")
    private val ER = listOf("center", "theater", "liter", "fiber", "caliber", "somber", "specter", "meager")
    private val ENSE = listOf("defense", "offense", "pretense")
    private val DOUBLE_L = listOf("travel", "cancel", "model", "label", "level", "signal", "fuel", "counsel", "jewel", "marvel", "quarrel", "rival", "channel", "dial", "enrol")
    private val DOUBLE_L_SUFFIX = listOf("ed", "ing", "er", "ers")
    private val IZE_NOT = setOf("size", "sized", "sizes", "sizing", "prize", "prized", "prizes", "seize", "seized", "seizes", "capsize", "capsized", "resize", "resized", "oversize", "oversized", "downsize", "downsized", "upsize")
    private val ONE_OFFS = mapOf(
        "gray" to "grey", "grays" to "greys", "aluminum" to "aluminium", "mom" to "mum", "moms" to "mums", "plow" to "plough", "plows" to "ploughs",
        "mold" to "mould", "molds" to "moulds", "catalog" to "catalogue", "catalogs" to "catalogues", "analog" to "analogue", "pajamas" to "pyjamas",
        "skeptic" to "sceptic", "skeptics" to "sceptics", "skeptical" to "sceptical", "sulfur" to "sulphur", "airplane" to "aeroplane", "airplanes" to "aeroplanes",
        "ax" to "axe", "cozy" to "cosy", "donut" to "doughnut", "donuts" to "doughnuts", "tire" to "", "program" to "", // the last two are left out on purpose
    )

    /** (American, British) pairs where the American word is in [known]. */
    fun pairs(known: Set<String>): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        fun add(us: String, uk: String) { if (uk.isNotEmpty() && us != uk && us in known) out[us] = uk }
        for (stem in OUR) { val uk = stem.dropLast(2) + "our"; for (s in OUR_SUFFIX) add(stem + s, uk + s) }
        for (stem in ER) { val uk = stem.dropLast(2) + "re"; add(stem, uk); add(stem + "s", uk + "s") }
        for (stem in ENSE) { val uk = stem.dropLast(3) + "nce"; add(stem, uk); add(stem + "s", uk + "s") }
        for (stem in DOUBLE_L) for (s in DOUBLE_L_SUFFIX) add(stem + s, stem + "l" + s)
        for (w in known) {
            if (w.length >= 6 && w !in IZE_NOT) {
                for ((us, uk) in listOf("ize" to "ise", "ized" to "ised", "izes" to "ises", "izing" to "ising", "ization" to "isation", "izations" to "isations", "izer" to "iser"))
                    if (w.endsWith(us)) add(w, w.dropLast(us.length) + uk)
            }
        }
        for ((us, uk) in ONE_OFFS) add(us, uk)
        return out.map { it.key to it.value }
    }
}
