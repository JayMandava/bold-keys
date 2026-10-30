#!/usr/bin/env python3
"""Pinned, offline-reproducible word/prediction assets. Network access occurs only in explicit fetch mode."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'tools/data'
ASSETS = ROOT / 'app/src/main/assets'
LANGS = ('en', 'fr', 'de')
MIN_PAIR_COUNT = 2
CONTEXT_BACKOFF_MIN_PROMPTS = 3
MAX_FOLLOWERS = 4
MAX_CONTEXTS = 12000
PATTERNS = {'en': r"[a-z]+(?:'[a-z]+)?", 'fr': r'[a-zàâäéèêëîïôöùûüÿçœæ]+', 'de': r'[a-zäöüß]+'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key: ' + key)
        result[key] = value
    return result


def read_json(path):
    return json.loads(Path(path).read_text(), object_pairs_hook=no_duplicates)


def registry():
    return read_json(DATA / 'sources.json')


def source_bytes(directory, key):
    item = registry()['sources'][key]
    data = (Path(directory) / item['filename']).read_bytes()
    if sha(data) != item['sha256']:
        raise ValueError('Source digest mismatch: ' + key)
    return data


def word_rows(lang, text):
    rows = [(w, int(c)) for w, c in (line.rsplit(' ', 1) for line in text.splitlines())]
    if lang == 'en':
        adjustments = read_json(DATA / 'english_word_adjustments.json')
        short = set(adjustments['allowed_two_letter_words']) | {'a', 'i'}
        exclude = set(adjustments['excluded_incomplete_contraction_stems'])
        words = [(w, c) for w, c in rows if re.fullmatch('[a-z]+', w) and (len(w) >= 3 or w in short) and w not in exclude]
        words = sorted(words + [tuple(x) for x in adjustments['contractions']], key=lambda x: -x[1])
        return words + [tuple(x) for x in adjustments['supplemental_words']]
    letters = 'a-zàâäéèêëîïôöùûüÿçœæ' if lang == 'fr' else 'a-zäöüß'
    stems = {'qu', 'puisqu', 'quelqu', 'jusqu', 'lorsqu', 'quoiqu', 'parcequ', 'aujourd'} if lang == 'fr' else set()
    one = {'a', 'à', 'y', 'ô'} if lang == 'fr' else set()
    words = [(w, c) for w, c in rows if re.fullmatch('[' + letters + ']+', w) and (len(w) >= 2 or w in one) and w not in stems]
    fold = str.maketrans('àâäæéèêëîïôöœùûüÿç', 'aaaaeeeeiiooouuuyc') if lang == 'fr' else {ord('ä'): 'a', ord('ö'): 'o', ord('ü'): 'u', ord('ß'): 'ss'}
    twins = {}
    for word, count in words:
        plain = word.translate(fold)
        if plain != word:
            twins[plain] = max(twins.get(plain, 0), count)
    return [(w, c) for w, c in words if not (w == w.translate(fold) and twins.get(w, 0) >= 4 * c)][:46000]


def sentences(text):
    # Deduplicate normalized case-insensitive prompts: repetitions do not inflate confidence.
    return sorted({unicodedata.normalize('NFC', s.strip()).replace('’', "'").lower() for s in text.splitlines() if s.strip()})


def tokens(lang, text):
    return re.findall(PATTERNS[lang], text)


def pair_counts(lang, lines, vocab):
    result = collections.defaultdict(collections.Counter)
    for line in lines:
        words = tokens(lang, line)
        for left, right in zip(words, words[1:]):
            # Never bridge an unknown token by deleting it from a sentence first.
            if left in vocab and right in vocab and left != right:
                result[left][right] += 1
    return result


def training_statistics(lang, lines, vocab):
    counts = collections.defaultdict(collections.Counter)
    support = collections.Counter()
    for line in lines:
        words = tokens(lang, line)
        pairs = {(left, right) for left, right in zip(words, words[1:]) if left in vocab and right in vocab and left != right}
        for left, right in pairs:
            counts[left][right] += 1
        for left in {left for left, _ in pairs}:
            support[left] += 1
    return counts, support


def prediction_rows(lang, lines, vocab, seeds=None, preferred=None):
    counts, support = training_statistics(lang, lines, vocab)
    rows = {left: [right for right, n in sorted(follow.items(), key=lambda x: (-x[1], -vocab[x[0]], x[0])) if n >= MIN_PAIR_COUNT or support[left] >= CONTEXT_BACKOFF_MIN_PROMPTS][:MAX_FOLLOWERS]
            for left, follow in counts.items()}
    seed_data = read_json(DATA / 'prediction_seeds.json')
    seeds = seeds if seeds is not None else seed_data[lang]
    preferred = set(preferred if preferred is not None else seed_data['preferred_chat_contexts'][lang])
    if not preferred.issubset(seeds):
        raise ValueError('Preferred chat contexts must have explicit seeds')
    for left, followers in seeds.items():
        if left not in vocab or not followers or len(followers) > MAX_FOLLOWERS or len(set(followers)) != len(followers):
            raise ValueError('Invalid curated context: ' + lang + '/' + left)
        if any(right not in vocab or right == left for right in followers):
            raise ValueError('Invalid curated follower: ' + lang + '/' + left)
        # Explicit conversational priors, not fabricated observation counts.
        observed = rows.get(left, [])
        first, second = (followers, observed) if left in preferred else (observed, followers)
        rows[left] = (first + [w for w in second if w not in first])[:MAX_FOLLOWERS]
    return [(left, rows[left]) for left in sorted(rows, key=lambda w: (-vocab[w], w)) if rows[left]][:MAX_CONTEXTS]


def word_name(lang):
    return 'words.tsv' if lang == 'en' else 'words_' + lang + '.tsv'


def pair_name(lang):
    return 'pairs.tsv' if lang == 'en' else 'pairs_' + lang + '.tsv'


def word_tsv(rows):
    return ''.join(f'{w}\t{c}\n' for w, c in rows).encode()


def pair_tsv(rows):
    return ''.join(f"{w}\t{','.join(follow)}\n" for w, follow in rows).encode()


def build(directory, output, check=False):
    # Verify all grants/guidance as well as all six source files before producing anything.
    for key in registry()['sources']:
        source_bytes(directory, key)
    generated = {}
    stats = {}
    for lang in LANGS:
        words = word_rows(lang, source_bytes(directory, 'words_' + lang).decode())
        lines = sentences(source_bytes(directory, 'predictions_' + lang).decode())
        # No dictionary, historical seed, FrequencyWords weight, or held-out input enters this model.
        import prediction_coverage
        pairs = list(prediction_coverage.model(lang, lines).items())
        generated[word_name(lang)] = word_tsv(words)
        generated[pair_name(lang)] = pair_tsv(pairs)
        stats[lang] = dict(words=len(words), unique_prompts=len(lines), contexts=len(pairs),
                           follower_slots=sum(len(v) for _, v in pairs), single_follower_contexts=sum(len(v) == 1 for _, v in pairs),
                           curated_contexts=0, preferred_chat_contexts=0)
    manifest = dict(schema=2, pipeline_sha256=sha(Path(__file__).read_bytes()),
                    source_registry_sha256=sha((DATA / 'sources.json').read_bytes()),
                    prediction_generator_sha256=sha((ROOT / 'tools/prediction_coverage.py').read_bytes()),
                    english_adjustments_sha256=sha((DATA / 'english_word_adjustments.json').read_bytes()),
                    source_snapshots=registry(),
                    policy=dict(prediction_inputs='Only pinned CC0 Sentence Collector text; no spelling dictionaries, FrequencyWords weights, historical seeds or held-out files',
                                minimum_follower_distinct_prompts=2, training_observations='Each pair/context/unigram counts at most once per normalized prompt',
                                ranking='Descending pair count, then corpus unigram prompt count, then lexical order',
                                maximum_followers=4, maximum_contexts=24000,
                                rare_contexts='One-prompt contexts allowed if follower occurs in at least two distinct prompts; no invented counts'),
                    statistics=stats,
                    assets={name: dict(sha256=sha(data), bytes=len(data), license='CC0-1.0 (Common Voice source)' if name.startswith('pairs') else 'CC-BY-SA-4.0 (FrequencyWords-derived dictionary)') for name, data in generated.items()})
    generated['data-provenance.json'] = (json.dumps(manifest, indent=2, ensure_ascii=False) + '\n').encode()
    for name, data in generated.items():
        destination = Path(output) / name
        if check:
            if destination.read_bytes() != data:
                raise ValueError('Generated asset differs: ' + name)
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
    return manifest


def audit_assets(directory=ASSETS):
    directory = Path(directory)
    manifest = read_json(directory / 'data-provenance.json')
    checks = [('pipeline_sha256', Path(__file__)), ('source_registry_sha256', DATA / 'sources.json'),
              ('prediction_generator_sha256', ROOT / 'tools/prediction_coverage.py'), ('english_adjustments_sha256', DATA / 'english_word_adjustments.json')]
    for key, path in checks:
        if manifest[key] != sha(path.read_bytes()):
            raise ValueError('Data recipe changed without rebuilding: ' + key)
    if manifest['source_snapshots'] != registry():
        raise ValueError('Source registry differs from packaged provenance')
    for name, item in manifest['assets'].items():
        data = (directory / name).read_bytes()
        if sha(data) != item['sha256'] or len(data) != item['bytes']:
            raise ValueError('Data asset digest mismatch: ' + name)
    return manifest


def verify_archive(archive, prefix):
    """Bind shipped data and offline grants to this reviewed source recipe, not a caller's manifest."""
    manifest = audit_assets()
    if archive.read(prefix + 'data-provenance.json') != (ASSETS / 'data-provenance.json').read_bytes():
        raise ValueError('Packaged data provenance differs from reviewed source')
    for name, item in manifest['assets'].items():
        data = archive.read(prefix + name)
        if sha(data) != item['sha256'] or len(data) != item['bytes']:
            raise ValueError('Packaged prediction/dictionary digest mismatch: ' + name)
    for path in (ASSETS / 'licenses').iterdir():
        if archive.read(prefix + 'licenses/' + path.name) != path.read_bytes():
            raise ValueError('Packaged license notice differs: ' + path.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['fetch', 'build', 'check', 'audit'])
    parser.add_argument('--source-dir', type=Path)
    parser.add_argument('--output-dir', type=Path, default=ASSETS)
    args = parser.parse_args()
    if args.command == 'audit':
        audit_assets(args.output_dir)
        print('Pinned generated data assets verified')
        return
    if args.source_dir is None:
        parser.error('--source-dir is required')
    if args.command == 'fetch':
        args.source_dir.mkdir(parents=True, exist_ok=True)
        for key, item in registry()['sources'].items():
            data = urllib.request.urlopen(item['url'], timeout=60).read()
            if sha(data) != item['sha256']:
                raise ValueError('Downloaded source digest mismatch: ' + key)
            (args.source_dir / item['filename']).write_bytes(data)
    else:
        result = build(args.source_dir, args.output_dir, check=args.command == 'check')
        print(json.dumps(result['statistics'], ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print('Data build failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
