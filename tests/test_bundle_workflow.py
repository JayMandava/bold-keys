"""Secret-free tests for nonpublishing bundle validation, provenance and signing gates."""
import importlib.util
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/release'))
import bundle

SHA = 'a' * 40
MANIFEST = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ai.boldkeys" android:versionCode="10" android:versionName="0.6.2"><uses-sdk android:minSdkVersion="33" android:targetSdkVersion="36"/><application/></manifest>'


def elf(alignment=16384, machine=183):
    value = bytearray(120)
    value[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H', value, 18, machine)
    struct.pack_into('<Q', value, 32, 64)
    struct.pack_into('<HH', value, 54, 56, 1)
    struct.pack_into('<IIQQQQQQ', value, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
    return bytes(value)


class BundleChecks(unittest.TestCase):
    def test_preflight_reuses_existing_owner_main_gate_in_validation_mode(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'outputs'
            with patch.object(sys, 'argv', ['bundle.py', 'preflight', '--version', '0.6.2', '--commit', SHA]), patch.object(bundle.release, 'preflight', return_value=10) as gate, patch.dict(os.environ, {'GITHUB_OUTPUT': str(output)}):
                bundle.main()
                gate.assert_called_once_with('0.6.2', SHA, 'validate')
                self.assertEqual(output.read_text(), 'version_code=10\n')

    def test_manifest_requires_production_metadata_and_no_debug_components(self):
        bundle.verify_manifest(MANIFEST, '0.6.2', 10)
        for old, new in [('ai.boldkeys', 'ai.boldkeys.debug'), ('versionCode="10"', 'versionCode="9"'), ('targetSdkVersion="36"', 'targetSdkVersion="35"'), ('<application/>', '<application android:debuggable="true"/>'), ('<application/>', '<application><activity android:name="ai.boldkeys.EvalActivity"/></application>')]:
            with self.subTest(change=new), self.assertRaises(ValueError):
                bundle.verify_manifest(MANIFEST.replace(old, new), '0.6.2', 10)

    def test_elf_requires_arm64_and_16k_load_alignment(self):
        bundle.elf_16k(elf())
        for data in [b'not-elf', elf(4096), elf(20000), elf(machine=62), elf()[:80]]:
            with self.assertRaises(ValueError):
                bundle.elf_16k(data)

    def test_bundle_structure_and_signature_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            tool = directory / 'bundletool.jar'
            tool.write_bytes(b'public-fixture')
            path = directory / 'bundle.aab'
            config = json.dumps({'optimizations': {'uncompressNativeLibraries': {'alignment': 'PAGE_ALIGNMENT_16K'}}})
            for variant in ['valid', 'wrong-abi', 'extra-module', 'signature', 'bad-elf']:
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr('base/manifest/AndroidManifest.xml', b'protobuf-fixture')
                    for name in ['libcontextlm.so', 'libandroidx.graphics.path.so']:
                        abi = 'x86_64' if variant == 'wrong-abi' else 'arm64-v8a'
                        archive.writestr(f'base/lib/{abi}/{name}', elf(4096 if variant == 'bad-elf' else 16384))
                    if variant == 'extra-module':
                        archive.writestr('extra/manifest/AndroidManifest.xml', b'fixture')
                    if variant == 'signature':
                        archive.writestr('META-INF/FIXTURE.SF', b'not-a-signature')
                with patch.dict(os.environ, {'BUNDLETOOL_JAR': str(tool)}), patch.object(bundle.release, 'sha', return_value=bundle.BUNDLETOOL_SHA256), patch.object(bundle, 'run', side_effect=['validated', MANIFEST, config, 'unsigned']) as run:
                    if variant == 'valid':
                        bundle.verify_bundle(path, '0.6.2', 10, False)
                        self.assertEqual(run.call_args.args[-1], bundle.release.CERT)
                    else:
                        with self.assertRaises(ValueError):
                            bundle.verify_bundle(path, '0.6.2', 10, False)

    def test_provenance_tampering_and_notes_changes_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'unsigned.aab').write_bytes(b'public-fixture')
            (directory / 'release-notes.md').write_text('reviewed notes')
            with patch.object(bundle, 'verify_bundle'):
                digest = bundle.metadata(directory, '0.6.2', SHA, 10, False, write=True)
                bundle.metadata(directory, '0.6.2', SHA, 10, False, digest)
                with self.assertRaisesRegex(ValueError, 'provenance'):
                    bundle.metadata(directory, '0.6.2', 'b' * 40, 10, False, digest)
                (directory / 'release-notes.md').write_text('changed')
                with self.assertRaisesRegex(ValueError, 'notes changed'):
                    bundle.metadata(directory, '0.6.2', SHA, 10, False, digest)
                (directory / 'unsigned.aab').write_bytes(b'tampered')
                with self.assertRaisesRegex(ValueError, 'digest mismatch'):
                    bundle.metadata(directory, '0.6.2', SHA, 10, False, digest)
                with self.assertRaisesRegex(ValueError, 'manifest digest mismatch'):
                    bundle.metadata(directory, '0.6.2', SHA, 10, False, '0' * 64)

    def test_java_verifier_rejects_unsigned_payload_as_signed_without_any_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'fixture.aab'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('base/manifest/AndroidManifest.xml', b'public-fixture')
            command = ['java', str(ROOT / 'tools/release/VerifyBundleSignature.java'), str(path)]
            unsigned = subprocess.run([*command, 'unsigned', bundle.release.CERT], capture_output=True, text=True)
            self.assertEqual(unsigned.returncode, 0, unsigned.stderr)
            signed = subprocess.run([*command, 'signed', bundle.release.CERT], capture_output=True, text=True)
            self.assertNotEqual(signed.returncode, 0)
            self.assertIn('Every payload must have exactly one signer', signed.stderr)

    def test_secret_step_fails_closed_and_cleans_up_without_a_signing_key(self):
        workflow = (ROOT / '.github/workflows/play-bundle.yml').read_text()
        step = workflow.split('- name: Sign with existing production key')[1].split('- name: Verify every signed payload')[0]
        script = textwrap.dedent(step.split('        run: |\n')[1])
        names = ['BOLD_RELEASE_KEYSTORE_BASE64', 'BOLD_RELEASE_STORE_PASSWORD', 'BOLD_RELEASE_KEY_ALIAS', 'BOLD_RELEASE_KEY_PASSWORD']
        fixtures = [{}, dict(zip(names, ['%', 'public-fixture', 'fixture', 'public-fixture'])), dict(zip(names, ['bm90LWEta2V5', 'public-fixture', 'androiddebugkey', 'public-fixture']))]
        for fixture in fixtures:
            with tempfile.TemporaryDirectory() as temporary:
                env = {k: v for k, v in os.environ.items() if k not in names}
                env.update(fixture, RUNNER_TEMP=temporary, VERSION='0.6.2')
                result = subprocess.run(['bash', '-c', script], cwd=temporary, env=env, capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((Path(temporary) / 'bold-release-key').exists())
                self.assertFalse((Path(temporary) / 'signed').exists())
                self.assertNotIn('public-fixture', result.stdout + result.stderr)

    def test_workflow_is_main_only_protected_and_never_publishes(self):
        text = (ROOT / '.github/workflows/play-bundle.yml').read_text()
        self.assertTrue(all(re.fullmatch(r'actions/[a-z-]+@[a-f0-9]{40}', action) for action in re.findall(r'uses: ([^\s]+)', text)))
        for forbidden in ['contents: write', 'pull_request', 'push:', '--licenses', 'gh release', 'publish:', 'cache:']:
            self.assertNotIn(forbidden, text)
        self.assertIn("github.ref == 'refs/heads/main'", text)
        self.assertIn('environment: production-release', text)
        self.assertIn('group: bold-keys-production-release', text)
        self.assertEqual(text.count('retention-days: 1'), 2)
        self.assertEqual(text.count('digest-mismatch: error'), 1)
        self.assertEqual(text.count('${{ secrets.'), 4)
        self.assertEqual(text.count('bundle.py preflight'), 2)
        secret = text.split('- name: Sign with existing production key')[1].split('- name: Verify every signed payload')[0]
        self.assertNotIn('gradlew', secret)
        self.assertNotIn('python', secret)
        self.assertNotIn('java tools/', secret)
        self.assertIn('-storepass:env BOLD_RELEASE_STORE_PASSWORD', secret)
        self.assertIn("trap 'rm -rf", secret)


if __name__ == '__main__':
    unittest.main()
