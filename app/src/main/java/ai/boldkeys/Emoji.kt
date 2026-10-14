package ai.boldkeys

import android.content.Context
import android.graphics.Paint

/** The emoji the keyboard offers: Unicode's set in Unicode's groups, limited to what this phone's emoji font can draw (so there are no empty boxes). */
object Emoji {
    /** One emoji: the plain one, and (for people and hands) the same emoji in the five skin tones, light to dark. */
    class Item(val plain: String, val tones: List<String>)
    class Group(val name: String, val items: List<Item>)

    @Volatile private var cache: List<Group>? = null

    fun groups(c: Context): List<Group> = cache ?: synchronized(this) { cache ?: load(c).also { cache = it } }

    private fun load(c: Context): List<Group> {
        val paint = Paint()
        val out = ArrayList<Group>()
        var name: String? = null
        var cur = ArrayList<Item>()
        fun flush() { name?.let { if (cur.isNotEmpty()) out.add(Group(it, cur)) } }
        c.assets.open("emoji.tsv").bufferedReader().useLines { lines ->
            for (l in lines) {
                if (l.startsWith("@")) { flush(); name = l.substring(1); cur = ArrayList(); continue }
                val p = l.split('\t')
                if (p[0].isEmpty() || !paint.hasGlyph(p[0])) continue
                val tones = if (p.size == 6 && p.drop(1).all { paint.hasGlyph(it) }) p.drop(1) else emptyList()
                cur.add(Item(p[0], tones))
            }
        }
        flush()
        return out
    }

    // ---- skin tones: 0 is the plain (yellow) emoji, 1 to 5 are light to dark. One default for all, and an emoji the user picked a tone for keeps it.

    private fun overrides(c: Context): Map<String, Int> =
        Prefs.string(c, "emojiTones", "").split('\n').mapNotNull { l -> l.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }.toMap()

    fun defaultTone(c: Context) = Prefs.string(c, "skinTone", "0").toIntOrNull()?.coerceIn(0, 5) ?: 0

    fun tone(item: Item, overrides: Map<String, Int>, default: Int) = if (item.tones.isEmpty()) 0 else overrides[item.plain] ?: default

    fun shown(item: Item, tone: Int) = if (item.tones.isEmpty() || tone == 0) item.plain else item.tones[tone - 1]

    fun toneState(c: Context): Pair<Map<String, Int>, Int> = overrides(c) to defaultTone(c)

    fun remember(c: Context, item: Item, tone: Int) {
        val map = LinkedHashMap(overrides(c)); map[item.plain] = tone
        Prefs.put(c, "emojiTones", map.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
}
