#!/usr/bin/env python3
"""Local aggregate comparison. Never exports old table rows or old prediction strings."""
import argparse
import collections
import json
from pathlib import Path
import re
import subprocess
import data_pipeline as data


def table(text):
    return dict((key, values.split(',')) for key, values in (line.split('\t') for line in text.splitlines()))


def evaluate(source_dir, baseline):
    if not re.fullmatch('[0-9a-f]{40}', baseline):
        raise ValueError('Baseline must be a full commit SHA')
    scenarios = data.read_json(data.DATA / 'prediction_scenarios.json')
    result = dict(baseline_commit=baseline, limitations=[
        'No old rows or prediction strings are exported; only aggregate metrics and synthetic-case hit flags.',
        'Unigram-weighted context coverage is a proxy, not measured typing frequency or suggestion quality.',
        'Common Voice prompts differ from real messaging and may overlap old source material; this is not an independent real-user benchmark.',
        'Chat diagnostics intentionally overlap seed design and are not a generalization score.',
        'This exploratory split was used to compare source/count/backoff policies, so it is not an untouched model-selection test.',
        'The model uses one previous word; full synthetic prefixes are explanatory context only.'
    ], languages={})
    for lang in data.LANGS:
        vocab = dict((word, int(count)) for word, count in (line.split('\t') for line in (data.ASSETS / data.word_name(lang)).read_text().splitlines()))
        previous_bytes = subprocess.check_output(['git', 'show', baseline + ':app/src/main/assets/' + data.pair_name(lang)], cwd=data.ROOT)
        old = table(previous_bytes.decode())
        current_bytes = (data.ASSETS / data.pair_name(lang)).read_bytes()
        new = table(current_bytes.decode())
        lines = data.sentences(data.source_bytes(source_dir, 'predictions_' + lang).decode())
        train = [s for s in lines if int(data.sha(s.encode())[:8], 16) % 5 != 0]
        heldout = [s for s in lines if int(data.sha(s.encode())[:8], 16) % 5 == 0]
        trained = dict(data.prediction_rows(lang, train, vocab))
        actual = data.pair_counts(lang, heldout, vocab)
        total = sum(sum(counts.values()) for counts in actual.values())
        heldout_results = {}
        for label, model in [('old', old), ('replacement_train_only', trained)]:
            hits = sum(n for context, counts in actual.items() for following, n in counts.items() if following in model.get(context, [])[:2])
            covered = sum(sum(counts.values()) for context, counts in actual.items() if model.get(context))
            heldout_results[label] = dict(top2_hits=hits, eligible_transitions=total, top2_hit_rate=hits / total,
                                          context_coverage_rate=covered / total)
        diagnostics = []
        categories = collections.defaultdict(lambda: dict(cases=0, old_top2_hits=0, replacement_top2_hits=0))
        for category, prefix, context, expected in scenarios[lang]:
            old_hit = bool(set(old.get(context, [])[:2]) & set(expected))
            new_hit = bool(set(new.get(context, [])[:2]) & set(expected))
            categories[category]['cases'] += 1
            categories[category]['old_top2_hits'] += int(old_hit)
            categories[category]['replacement_top2_hits'] += int(new_hit)
            diagnostics.append(dict(category=category, synthetic_prefix=prefix, context=context, expected=expected,
                                    replacement_top2=new.get(context, [])[:2], old_top2_hit=old_hit, replacement_top2_hit=new_hit))
        result['languages'][lang] = dict(
            old=dict(contexts=len(old), follower_slots=sum(map(len, old.values())), bytes=len(previous_bytes)),
            replacement=dict(contexts=len(new), follower_slots=sum(map(len, new.values())), bytes=len(current_bytes), single_follower_contexts=sum(len(v) == 1 for v in new.values())),
            retained_old_contexts=len(set(old) & set(new)),
            old_context_unigram_weight_coverage=sum(vocab.get(w, 0) for w in set(old) & set(new)) / sum(vocab.get(w, 0) for w in old),
            training_prompts=len(train), heldout_prompts=len(heldout), heldout=heldout_results,
            synthetic_categories=dict(categories), synthetic_cases=diagnostics)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', required=True, type=Path)
    parser.add_argument('--baseline-commit', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.write_text(json.dumps(evaluate(args.source_dir, args.baseline_commit), indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
