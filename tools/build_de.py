"""Builds the German word list and next-word table from FrequencyWords (de_50k) and Tatoeba German sentences.
Usage: python3 -I build_de.py <de_50k.txt> <deu_sentences.tsv> <out words_de.tsv> <out pairs_de.tsv>"""
import re, sys, collections

LET = "a-zäöüß"
WORD = re.compile(f"[{LET}]+")

freq, sents, out_w, out_p = sys.argv[1:5]
words = []
for line in open(freq, encoding="utf8"):
    w, c = line.split()
    if WORD.fullmatch(w) and len(w) >= 2:
        words.append((w, int(c)))
# subtitles contain text typed without umlauts ("fur", "uber", "grosse"): drop an unmarked spelling when its umlaut twin is far commoner
FOLD = {ord("ä"): "a", ord("ö"): "o", ord("ü"): "u", ord("ß"): "ss"}
best_twin = {}
for w, c in words:
    f = w.translate(FOLD)
    if f != w and c > best_twin.get(f, 0):
        best_twin[f] = c
words = [(w, c) for w, c in words if not (w == w.translate(FOLD) and best_twin.get(w, 0) >= 4 * c)]
words = words[:46000]
vocab = {w for w, _ in words}
open(out_w, "w", encoding="utf8").write("".join(f"{w}\t{c}\n" for w, c in words))

pair = collections.defaultdict(collections.Counter)
for line in open(sents, encoding="utf8"):
    parts = line.rstrip("\n").split("\t")
    if len(parts) < 3:
        continue
    toks = WORD.findall(parts[2].lower())
    for a, b in zip(toks, toks[1:]):
        if a in vocab and b in vocab and a != b:
            pair[a][b] += 1
rows = []
for a, cnt in pair.items():
    top = [b for b, n in cnt.most_common(4) if n >= 3]
    if len(top) >= 2:
        rows.append((a, top))
count = dict(words)
rows.sort(key=lambda r: -count[r[0]])
rows = rows[:12000]
open(out_p, "w", encoding="utf8").write("".join(f"{a}\t{','.join(t)}\n" for a, t in rows))
print(len(words), "words", len(rows), "follower rows")
