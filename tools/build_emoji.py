"""Builds assets/emoji.tsv from Unicode's emoji-test.txt: the keyboard's emoji, grouped as Unicode groups them. A line is an emoji; for emoji that
take a skin tone the line is the plain one followed by its five tones (light to dark), separated by tabs. Header lines start with '@' (the keycap emoji starts with '#').
Usage: python3 -I build_emoji.py <emoji-test.txt> <out emoji.tsv>"""
import re, sys

TONES = ["light skin tone", "medium-light skin tone", "medium skin tone", "medium-dark skin tone", "dark skin tone"]
SHORT = {"Smileys & Emotion": "smileys", "People & Body": "people", "Animals & Nature": "animals", "Food & Drink": "food",
         "Travel & Places": "travel", "Activities": "activities", "Objects": "objects", "Symbols": "symbols", "Flags": "flags"}
LINE = re.compile(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*(\S+)\s+E[\d.]+\s+(.*)$")

src, out = sys.argv[1:3]
groups = {}; order = []; group = None
plain = {}   # name -> (group, emoji)
toned = {}   # name -> {tone index: emoji}
for raw in open(src, encoding="utf8"):
    if raw.startswith("# group:"):
        group = raw.split(":", 1)[1].strip()
        if group in SHORT: order.append(group); groups[group] = []
        continue
    m = LINE.match(raw.rstrip("\n"))
    if not m or group not in SHORT:
        continue
    emoji, name = m.group(2), m.group(3)
    single = [i for i, t in enumerate(TONES) if name.endswith(": " + t) and ", " not in name.rsplit(": ", 1)[1]]
    if single:
        toned.setdefault(name.rsplit(": ", 1)[0], {})[single[0]] = emoji
    elif ": " in name and "skin tone" in name:
        continue  # two different tones in one emoji (a couple): not offered
    else:
        groups[group].append((name, emoji))

lines = []
n = 0
for g in order:
    lines.append("@" + SHORT[g])
    for name, emoji in groups[g]:
        t = toned.get(name)
        if t and len(t) == 5:
            lines.append("\t".join([emoji] + [t[i] for i in range(5)]))
        else:
            lines.append(emoji)
        n += 1
open(out, "w", encoding="utf8").write("\n".join(lines) + "\n")
print(n, "emoji,", sum(1 for l in lines if "\t" in l), "take a skin tone")
