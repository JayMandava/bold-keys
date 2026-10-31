"""Offline schema/provenance and behavior checks; no old corpus contents are copied into tests."""
import io
import zipfile
import collections
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('data_pipeline', ROOT / 'tools/data_pipeline.py')
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)
sys.path.insert(0, str(ROOT / 'tools'))
import prediction_coverage as coverage
import evaluate_coverage as evaluation
from unittest.mock import patch


class PredictionDataChecks(unittest.TestCase):
    def test_packaged_data_and_recipe_hashes_match(self):
        manifest = pipeline.audit_assets()
        self.assertEqual(len(manifest['assets']), 6)
        for lang in pipeline.LANGS:
            source = manifest['source_snapshots']['sources']['predictions_' + lang]
            self.assertEqual(source['license'], 'CC0-1.0')
            self.assertIn('/70dde553e48d7d0da452ba4ba070c001f65db88c/server/data/' + lang + '/sentence-collector.txt', source['url'])
        self.assertEqual(pipeline.sha((ROOT / 'app/src/main/assets/licenses/CommonVoice-CC0.txt').read_bytes()), pipeline.registry()['sources']['cv_license']['sha256'])
        self.assertEqual(pipeline.sha((ROOT / 'app/src/main/assets/licenses/CC-BY-SA-4.0.txt').read_bytes()), pipeline.registry()['sources']['cc_by_sa_legalcode']['sha256'])

    def test_all_tables_have_valid_unique_multilingual_corpus_followers(self):
        for lang in pipeline.LANGS:
            vocab = dict(line.split('\t') for line in (pipeline.ASSETS / pipeline.word_name(lang)).read_text().splitlines())
            seen = set()
            for line in (pipeline.ASSETS / pipeline.pair_name(lang)).read_text().splitlines():
                context, following = line.split('\t')
                words = following.split(',')
                self.assertNotIn(context, seen)
                seen.add(context)
                self.assertTrue(coverage.lexical(lang, context))
                self.assertEqual(pipeline.tokens(lang, context), [context])
                self.assertTrue(1 <= len(words) <= 4)
                self.assertEqual(len(words), len(set(words)))
                self.assertNotIn(context, words)
                self.assertTrue(all(coverage.lexical(lang, word) and pipeline.tokens(lang, word) == [word] for word in words))
            self.assertEqual(len(seen), pipeline.audit_assets()['statistics'][lang]['contexts'])

    def test_observed_threshold_deduplication_and_unknown_token_boundaries(self):
        vocab = dict(hello=100, there=90, one=80, two=70)
        duplicates = pipeline.sentences('Hello there one\nhello there one\n')
        self.assertEqual(len(duplicates), 1)
        self.assertEqual(pipeline.prediction_rows('en', duplicates, vocab, seeds={}, preferred=[]), [])
        self.assertEqual(dict(pipeline.prediction_rows('en', ['hello there one', 'hello there two'], vocab, seeds={}, preferred=[]))['hello'], ['there'])
        self.assertEqual(dict(pipeline.pair_counts('en', ['hello unknown there'], vocab)), {})

    def test_weak_follower_backoff_requires_three_distinct_prompts(self):
        vocab = dict(hello=100, one=90, two=80, three=70, end=60)
        short = ['hello one end', 'hello two end']
        self.assertNotIn('hello', dict(pipeline.prediction_rows('en', short, vocab, seeds={}, preferred=[])))
        broad = dict(pipeline.prediction_rows('en', short + ['hello three end'], vocab, seeds={}, preferred=[]))
        self.assertEqual(broad['hello'], ['one', 'two', 'three'])
        repeated = ['hello one hello two hello three']
        self.assertNotIn('hello', dict(pipeline.prediction_rows('en', repeated, vocab, seeds={}, preferred=[])))

    def test_only_explicit_chat_priors_override_observed_rankings(self):
        vocab = dict(hello=100, there=90, world=80, friend=70, one=10, two=9)
        lines = ['hello there one', 'hello there two', 'hello world one', 'hello world two']
        seeds = {'hello': ['friend']}
        ordinary = dict(pipeline.prediction_rows('en', lines, vocab, seeds, preferred=[]))
        preferred = dict(pipeline.prediction_rows('en', lines, vocab, seeds, preferred=['hello']))
        self.assertEqual(ordinary['hello'], ['there', 'world', 'friend'])
        self.assertEqual(preferred['hello'], ['friend', 'there', 'world'])
        self.assertEqual(pipeline.pair_counts('en', lines, vocab)['hello']['friend'], 0)

    def test_accents_umlauts_and_apostrophes_are_preserved(self):
        self.assertEqual(pipeline.tokens('fr', pipeline.sentences('Un cafe\u0301 à côté')[0]), ['un', 'café', 'à', 'côté'])
        self.assertEqual(pipeline.tokens('de', 'für große grüße'), ['für', 'große', 'grüße'])
        self.assertEqual(pipeline.tokens('en', "i'm sure you're ready"), ["i'm", 'sure', "you're", 'ready'])

    def test_unpinned_input_fails_before_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            source = pipeline.registry()['sources']['predictions_en']
            (directory / source['filename']).write_text('unverified input')
            with self.assertRaisesRegex(ValueError, 'digest mismatch'):
                pipeline.source_bytes(directory, 'predictions_en')

    def test_legacy_tatoeba_builder_arguments_are_rejected(self):
        for lang in ['fr', 'de']:
            with tempfile.TemporaryDirectory() as temporary:
                result = subprocess.run([sys.executable, str(ROOT / f'tools/build_{lang}.py'), 'frequency.txt', 'sentences.tsv', 'words.tsv', 'pairs.tsv'], cwd=temporary, capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
                self.assertIn('unrecognized arguments', result.stderr)
                self.assertEqual(list(Path(temporary).iterdir()), [])

    def test_archive_rejects_replaced_prediction_data_or_missing_license(self):
        manifest = pipeline.audit_assets()
        for variant in ['valid', 'changed', 'missing-license']:
            memory = io.BytesIO()
            with zipfile.ZipFile(memory, 'w') as archive:
                archive.writestr('assets/data-provenance.json', (pipeline.ASSETS / 'data-provenance.json').read_bytes())
                for name in manifest['assets']:
                    data = b'unapproved replacement' if variant == 'changed' and name == 'pairs.tsv' else (pipeline.ASSETS / name).read_bytes()
                    archive.writestr('assets/' + name, data)
                for path in (pipeline.ASSETS / 'licenses').iterdir():
                    if variant != 'missing-license' or path.name != 'CommonVoice-CC0.txt':
                        archive.writestr('assets/licenses/' + path.name, path.read_bytes())
            with zipfile.ZipFile(memory) as archive:
                if variant == 'valid':
                    pipeline.verify_archive(archive, 'assets/')
                else:
                    with self.assertRaises((ValueError, KeyError)):
                        pipeline.verify_archive(archive, 'assets/')

    def test_rare_contexts_use_independently_recurring_corpus_followers(self):
        lines = pipeline.sentences('rare common\nother common\nrare isolated\nhello q bridge\nother bridge')
        result = coverage.model('en', lines)
        self.assertEqual(result['rare'], ['common'])
        self.assertNotIn('hello', result)  # invalid one-letter token is not removed/bridged
        self.assertNotIn('isolated', result.get('rare', []))
        self.assertNotIn('bridge', result.get('hello', []))

    def test_ranking_is_corpus_only_and_counts_each_pair_once_per_prompt(self):
        lines = pipeline.sentences('hello common hello common\nhello common else\nhello other\nelse other')
        unigram, following, support = coverage.corpus_statistics('en', lines)
        self.assertEqual(following['hello']['common'], 2)
        with patch.object(coverage.data, 'word_rows', side_effect=AssertionError('dictionary dependency')), patch.object(coverage.data, 'read_json', side_effect=AssertionError('seed dependency')):
            self.assertEqual(coverage.model('en', lines)['hello'], ['common', 'other'])
        self.assertEqual(len(coverage.model('en', lines, maximum_contexts=1)), 1)

    def test_prediction_model_and_plan_remain_sealed(self):
        seal = pipeline.read_json(pipeline.DATA / 'coverage_model_seal.json')
        self.assertTrue(seal['frozen_before_test'])
        for name, expected in seal['sha256'].items():
            self.assertEqual(pipeline.sha((ROOT / name).read_bytes()), expected)
        manifest = pipeline.audit_assets()
        self.assertEqual(manifest['schema'], 2)
        self.assertNotIn('seeds_sha256', manifest)
        for name, item in manifest['assets'].items():
            self.assertTrue(item['license'].startswith('CC0-1.0' if name.startswith('pairs') else 'CC-BY-SA-4.0'))

    def test_holdout_removes_exact_token_and_five_word_contamination(self):
        raw = pipeline.sentences('one two three four five six\none, two three four five six\none two three four five stranger\nnew unseen sentence here')
        clean, counts = evaluation.clean_test('en', raw, ['one two three four five six'], [])
        self.assertEqual(clean, ['new unseen sentence here'])
        self.assertEqual(counts['excluded_exact'], 1)
        self.assertEqual(counts['excluded_token_exact'], 1)
        self.assertEqual(counts['excluded_shared_five_token_window'], 1)

    def test_words_still_match_public_production_hashes(self):
        # Verified against public v0.6.1; works in a shallow workflow checkout.
        expected = {'en': 'e404215106bd16a1f3fa4b3e48da540c6c5e2c5b8fba83a99a8fd9c0f84d9b27', 'fr': 'aa9478d5633ee69866bc3ad6bcff40abd363b70e564c367d1133093f25c27ff3', 'de': 'e643893c3bb8233bc93f18a895c9503a85b217609938fb19f9fafb3b6b374798'}
        for lang, digest in expected.items():
            self.assertEqual(pipeline.sha((pipeline.ASSETS / pipeline.word_name(lang)).read_bytes()), digest)

    def test_duplicate_seed_keys_fail_instead_of_silent_overrides(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON key'):
            json.loads('{"x":1,"x":2}', object_pairs_hook=pipeline.no_duplicates)


if __name__ == '__main__':
    unittest.main()
