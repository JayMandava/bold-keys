#!/usr/bin/env python3
"""Corpus-only next-word generation; no FrequencyWords or hand-authored seed inputs."""
import collections
import data_pipeline as data

MAX_CONTEXTS = 24000
MAX_FOLLOWERS = 4
ONE = {'en': {'a', 'i'}, 'fr': {'a', 'à', 'y', 'ô'}, 'de': set()}


def lexical(lang, word):
    return 2 <= len(word) <= 35 or word in ONE[lang]


def corpus_statistics(lang, lines):
    unigram = collections.Counter()
    following = collections.defaultdict(collections.Counter)
    contexts = collections.Counter()
    for line in lines:
        words = data.tokens(lang, line)
        unigram.update({w for w in words if lexical(lang, w)})
        pairs = {(a, b) for a, b in zip(words, words[1:]) if a != b and lexical(lang, a) and lexical(lang, b)}
        for a, b in pairs:
            following[a][b] += 1
        contexts.update({a for a, _ in pairs})
    return unigram, following, contexts


def model(lang, lines, minimum_follower_prompts=2, alpha=0.0, maximum_contexts=MAX_CONTEXTS):
    unigram, following, contexts = corpus_statistics(lang, lines)
    rows = {}
    for left in sorted(following, key=lambda w: (-contexts[w], -unigram[w], w)):
        eligible = [(right, n) for right, n in following[left].items() if unigram[right] >= minimum_follower_prompts]
        # Attested rare contexts may have one hint; candidates must recur independently elsewhere.
        ranked = sorted(eligible, key=lambda item: (-(item[1] / unigram[item[0]] ** alpha), -item[1], -unigram[item[0]], item[0]))
        if ranked:
            rows[left] = [right for right, _ in ranked[:MAX_FOLLOWERS]]
        if len(rows) == maximum_contexts:
            break
    return rows


def load_training(source_dir, lang):
    return data.sentences(data.source_bytes(source_dir, 'predictions_' + lang).decode())
