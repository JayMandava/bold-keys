"""Credential-free tests of the deliberately blocked PEPK scaffold; no keys or tool run."""
import importlib.util
import json
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
        for key in ('official_source_sha256', 'console_tool_sha256', 'console_encryption_pem_sha256'):
            record[key] = 'a' * 64
        record['password_input_source_verified'] = record['encryption_and_output_source_verified'] = True
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
