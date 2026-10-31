"""Credential-free tests of the deliberately blocked PEPK scaffold; no keys or tool run."""
import importlib.util
import json
import hashlib
import tempfile
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/release'))
spec = importlib.util.spec_from_file_location('pepk_preflight', ROOT / 'tools/release/pepk_preflight.py')
pepk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pepk)
public_spec = importlib.util.spec_from_file_location('pepk_public', ROOT / 'tools/release/pepk_public.py')
public = importlib.util.module_from_spec(public_spec)
public_spec.loader.exec_module(public)
SHA = 'a' * 40


class PepkScaffoldChecks(unittest.TestCase):
    def env(self):
        return dict(GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REPOSITORY='JayMandava/bold-keys',
                    GITHUB_REF='refs/heads/main', GITHUB_SHA=SHA, GITHUB_WORKFLOW_SHA=SHA)

    def record(self):
        return json.loads((ROOT / 'tools/release/pepk-export-review.json').read_text())

    def test_current_record_cannot_export(self):
        with self.assertRaisesRegex(ValueError, 'Missing reviewed'):
            pepk.validate_review_record(self.record())

    def test_metadata_alone_can_never_activate_export(self):
        record = self.record()
        for key in ('official_source_sha256', 'source_built_tool_sha256', 'console_encryption_pem_sha256'):
            record[key] = 'a' * 64
        record['password_input_source_verified'] = record['encryption_and_output_source_verified'] = True
        record['export_implementation_reviewed'] = True
        for key in ('build_recipe_review_id', 'source_licence_review_id', 'controlled_pty_protocol_review_id'):
            record[key] = 'synthetic-test-review'
        with self.assertRaisesRegex(ValueError, 'intentionally unimplemented'):
            pepk.validate_review_record(record)

    def test_wrong_recipient_or_certificate_pin_refused(self):
        for key, value in [('official_source_url', 'https://example.invalid/tool'),
                           ('expected_certificate_sha256', '0' * 64), ('status', 'ready')]:
            record = self.record() | {key: value}
            with self.subTest(key=key), self.assertRaises(ValueError):
                pepk.validate_review_record(record)

    def test_nonmanual_fork_branch_or_sha_rejected_before_api(self):
        for key, value in [('GITHUB_EVENT_NAME', 'push'), ('GITHUB_REPOSITORY', 'other/fork'),
                           ('GITHUB_REF', 'refs/heads/review'), ('GITHUB_SHA', 'b' * 40),
                           ('GITHUB_WORKFLOW_SHA', 'b' * 40)]:
            with self.subTest(key=key), patch.dict(os.environ, self.env() | {key: value}), patch.object(pepk.release, 'api') as api, self.assertRaises(ValueError):
                pepk.preflight(SHA)
            api.assert_not_called()

    def test_moved_main_refused(self):
        with patch.dict(os.environ, self.env()), patch.object(pepk.release, 'api', return_value={'object': {'sha': 'b' * 40}}), patch.object(pepk.release, 'gate') as gate, self.assertRaisesRegex(ValueError, 'Main moved'):
            pepk.preflight(SHA)
        gate.assert_not_called()

    def test_existing_gate_checked_before_readiness(self):
        with patch.dict(os.environ, self.env()), patch.object(pepk.release, 'api', return_value={'object': {'sha': SHA}}), patch.object(pepk.release, 'gate') as gate, self.assertRaisesRegex(ValueError, 'Missing reviewed'):
            pepk.preflight(SHA)
        gate.assert_called_once_with()

    def test_reported_source_and_build_pins_are_recorded(self):
        record = self.record()
        self.assertEqual(record['official_source_sha256'], 'dc29a1d192eef9371eec0d2c134879fefc0a9dcf0c7046d6c8f3d302d0009b09')
        self.assertEqual(record['official_source_size_bytes'], 5268938)
        self.assertEqual(record['source_built_tool_sha256'], '7923237dd6ea1c0198b0ee91a1bfc0dafbd858aa88936f2ce847652e93d1e18a')
        self.assertIsNone(record['console_encryption_pem_sha256'])
        self.assertFalse(record['export_implementation_reviewed'])

    def test_public_byte_pin_rejects_corruption_missing_pin_and_symlink(self):
        # Harmless public byte fixtures, not a key, PEPK JAR or encryption PEM.
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'fixture'
            path.write_bytes(b'public fixture')
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(public.verify_public_file(path, digest, 14), digest)
            for pin, size in [(None, 14), ('0' * 64, 14), (digest, 15)]:
                with self.subTest(pin=pin, size=size), self.assertRaises(ValueError):
                    public.verify_public_file(path, pin, size)
            link = Path(temp) / 'link'
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                public.verify_public_file(link, digest)
            path.write_bytes(b'changed fixture')
            with self.assertRaises(ValueError):
                public.verify_public_file(path, digest)

    def test_public_preparation_precedes_owner_gate_and_is_not_a_tool_build(self):
        text = (ROOT / '.github/workflows/pepk-export.yml').read_text()
        public_job = text.split('  prepare_public:')[1].split('  export:')[0]
        self.assertNotIn('environment:', public_job)
        self.assertNotIn('${{ secrets.', public_job)
        self.assertIn('pepk_public.py --source', public_job)
        self.assertIn('Refuse missing audited source-build', public_job)
        self.assertNotIn('javac', public_job)
        self.assertNotIn('--location', public_job)
        self.assertIn('  export:\n    needs: prepare_public', text)

    def test_scaffold_has_no_secret_or_export_or_upload_path(self):
        text = (ROOT / '.github/workflows/pepk-export.yml').read_text()
        self.assertNotIn('${{ secrets.', text)
        self.assertNotIn('upload-artifact@', text)
        self.assertNotIn('java -jar', text)
        self.assertNotIn('base64 --decode', text)
        self.assertNotIn('gradlew', text)
        self.assertIn('environment: production-release', text)
        self.assertIn('needs: preflight', text)
        self.assertIn('if: always()', text)
        self.assertIn('exit 1', text)
        self.assertNotIn('contents: write', text)


if __name__ == '__main__':
    unittest.main()
