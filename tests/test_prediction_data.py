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


class PredictionDataChecks(unittest.TestCase):
    def test_packaged_data_and_recipe_hashes_match(self):
        manifest = pipeline.audit_assets()
        self.assertEqual(len(manifest['assets']), 6)
        for lang in pipeline.LANGS:
            source = manifest['source_snapshots']['sources']['predictions_' + lang]
            self.assertEqual(source['license'], 'CC0-1.0')
            self.assertIn('/70dde553e48d7d0da452ba4ba070c001f65db88c/server/data/' + lang + '/sentence-collector.txt', source['url'])
        self.assertEqual(pipeline.sha((ROOT / 'app/src/main/assets/licenses/CommonVoice-CC0.txt').read_bytes()), pipeline.registry()['sources']['cv_license']['sha256'])

    def test_all_tables_have_valid_unique_multilingual_dictionary_followers(self):
        for lang in pipeline.LANGS:
            vocab = dict(line.split('\t') for line in (pipeline.ASSETS / pipeline.word_name(lang)).read_text().splitlines())
            seen = set()
            for line in (pipeline.ASSETS / pipeline.pair_name(lang)).read_text().splitlines():
                context, following = line.split('\t')
                words = following.split(',')
                self.assertNotIn(context, seen)
                seen.add(context)
                self.assertIn(context, vocab)
                self.assertTrue(1 <= len(words) <= 4)
                self.assertEqual(len(words), len(set(words)))
                self.assertNotIn(context, words)
                self.assertTrue(all(word in vocab for word in words))
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

    def test_duplicate_seed_keys_fail_instead_of_silent_overrides(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON key'):
            json.loads('{"x":1,"x":2}', object_pairs_hook=pipeline.no_duplicates)


if __name__ == '__main__':
    unittest.main()
