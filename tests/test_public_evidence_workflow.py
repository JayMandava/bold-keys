"""Public evidence packaging tests; no device, credentials or network access."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('stage_public_evidence', ROOT / 'tools/qa/stage-public-evidence.py')
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)
WORKFLOW = (ROOT / '.github/workflows/colour-approved-kvm-qa-5.yml').read_text()


class PublicEvidenceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def put(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def stage(self):
        return EVIDENCE.stage(self.root, self.root / 'qa-public-evidence')

    def test_only_staged_path_uploaded_after_successful_staging(self):
        upload = WORKFLOW.split('- name: Upload bounded evidence', 1)[1]
        self.assertIn("if: always() && steps.public_evidence.outcome == 'success'", upload)
        self.assertIn('path: qa-public-evidence/\n', upload)
        self.assertEqual(1, upload.count('path:'))
        for forbidden in ('.apk', '.aab', '.bundle', 'app/build/', 'qa-artifacts/'):
            self.assertNotIn(forbidden, upload)
        self.assertIn('run: python3 tools/qa/stage-public-evidence.py', WORKFLOW)
        self.assertLess(WORKFLOW.index('Always remove grant'), WORKFLOW.index('Stage only allowlisted'))

    def test_reports_cleanup_results_and_hashes_preserved(self):
        for name in ('qa-artifacts/kvm-cleanup.json', 'qa-artifacts/APK-SHA256SUMS.txt',
                     'app/build/test-results/testDebugUnitTest/result.xml',
                     'app/build/reports/lint-results-debug.html'):
            self.put(name, b'valid evidence\n')
        result = self.stage()
        self.assertEqual(4, len(result['included']))
        self.assertEqual([], result['excluded'])
        for item in result['included']:
            self.assertEqual(64, len(item['sha256']))
            self.assertEqual(b'valid evidence\n', (self.root / 'qa-public-evidence' / item['path']).read_bytes())

    def test_package_and_binary_extensions_never_copied_even_under_evidence(self):
        for suffix in ('.apk', '.APK', '.aab', '.bundle', '.zip', '.jar', '.bin', '.dex', '.so', '.pb'):
            self.put('qa-artifacts/accidental' + suffix, b'not for public distribution')
        self.put('app/build/outputs/apk/debug/app-debug.apk', b'private APK')
        result = self.stage()
        self.assertEqual([], result['included'])
        self.assertEqual(10, len(result['excluded']))
        self.assertTrue((self.root / 'app/build/outputs/apk/debug/app-debug.apk').exists())

    def test_renamed_package_content_is_rejected(self):
        for suffix in ('.txt', '.json', '.png', '.mp4'):
            self.put('qa-artifacts/renamed' + suffix, b'PK\x03\x04\x00APK')
        for name, data in (('null.log', b'a\0b'), ('bad.xml', b'\xff'),
                           ('native.txt', b'\x7fELF'), ('classes.js', b'dex\n035')):
            self.put('qa-artifacts/' + name, data)
        result = self.stage()
        self.assertEqual([], result['included'])
        self.assertEqual(8, len(result['excluded']))

    def test_real_capture_container_types_and_no_appended_archive(self):
        png = b'\x89PNG\r\n\x1a\n' + EVIDENCE.PNG_END
        box = lambda name, body=b'': (8 + len(body)).to_bytes(4, 'big') + name + body
        mp4 = box(b'ftyp', b'mp42') + box(b'free') + box(b'mdat', b'video samples') + box(b'moov')
        for name, data in (('screen.png', png), ('typing.mp4', mp4)):
            self.put('qa-artifacts/' + name, data)
            self.put('qa-artifacts/appended-' + name, data + b'PK\x03\x04APK')
        result = self.stage()
        self.assertEqual(2, len(result['included']))
        self.assertEqual(2, len(result['excluded']))

    def test_symlink_files_and_roots_are_not_followed(self):
        outside = self.put('private/secret.txt', b'private')
        (self.root / 'qa-artifacts').mkdir()
        (self.root / 'qa-artifacts/link.txt').symlink_to(outside)
        (self.root / 'app/build').mkdir(parents=True)
        (self.root / 'app/build/test-results').symlink_to(outside.parent, target_is_directory=True)
        result = self.stage()
        self.assertEqual([], result['included'])
        self.assertEqual(2, len(result['excluded']))

    def test_embedded_zip_and_git_bundle_are_rejected_under_allowed_names(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            archive.writestr('AndroidManifest.xml', 'APK-like archive')
        png_polyglot = b'\x89PNG\r\n\x1a\n' + buffer.getvalue() + EVIDENCE.PNG_END
        self.put('qa-artifacts/pretend-screen.png', png_polyglot)
        self.put('qa-artifacts/pretend-report.txt', b'# v2 git bundle\n')
        result = self.stage()
        self.assertEqual([], result['included'])
        self.assertEqual(2, len(result['excluded']))

    def test_existing_staging_fails_closed_instead_of_reusing_old_files(self):
        self.put('qa-public-evidence/old.apk', b'old package')
        with self.assertRaises(FileExistsError):
            self.stage()


if __name__ == '__main__':
    unittest.main()
