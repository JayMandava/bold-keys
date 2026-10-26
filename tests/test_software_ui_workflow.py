"""Boundary checks for independent non-privileged software-emulator UI validation."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import unittest
R = Path(__file__).resolve().parents[1]
W = (R / '.github/workflows/colour-software-ui-qa.yml').read_text()
S = (R / 'tools/qa/run-software-ui.sh').read_text()
P = (R / 'tools/qa/sparsify-system-image.py').read_text()

class SoftwareUiWorkflowTests(unittest.TestCase):
    def test_separate_public_feature_branch_only(self):
        self.assertIn('branches: [qa/iconic-colours-software]', W)
        self.assertIn("github.ref == 'refs/heads/qa/iconic-colours-software'", W)
        self.assertIn("github.repository == 'JayMandava/bold-keys'", W)
        self.assertIn('github.event.repository.private == false', W)
        self.assertNotIn('pull_request_target:', W)
        self.assertNotIn('workflow_run:', W)
    def test_no_hardware_device_or_privilege_changes(self):
        for forbidden in ('/dev/kvm', '-accel on', '-accel-check', 'sudo ', 'setfacl ', 'chmod ', 'usermod ', 'udevadm ', 'adb root', 'adb unroot', '--privileged', '-selinux', '--licenses'):
            self.assertNotIn(forbidden, W + S + P)
        self.assertIn('-accel off', S)
        self.assertIn('adb shell id -u', S)
        self.assertIn('== 2000', S)
    def test_no_secrets_or_production_writes(self):
        self.assertIn('contents: read', W)
        self.assertIn('persist-credentials: false', W)
        for forbidden in ('contents: write', 'environment:', 'secrets.', 'GH_TOKEN:', 'GITHUB_TOKEN:', 'apksigner sign', 'git push', 'gh release', 'release.py publish', 'gh workflow run'):
            self.assertNotIn(forbidden, W + S)
    def test_pinned_actions_bounded_runtime_and_retention(self):
        actions = re.findall(r'uses:\s*(\S+)', W)
        self.assertEqual(3, len(actions))
        for action in actions:
            self.assertRegex(action, r'^actions/(checkout|setup-java|upload-artifact)@[0-9a-f]{40}$')
        self.assertIn('timeout-minutes: 45', W)
        self.assertIn('retention-days: 1', W)
        self.assertIn('SECONDS + 1200', S)
        self.assertIn('timeout 15m ./gradlew', S)
        self.assertIn('trap cleanup EXIT', S)
    def test_real_standard_image_and_explicit_debug_abi(self):
        self.assertIn("'system-images;android-33;default;x86_64' </dev/null", W)
        self.assertNotIn('aosp_atd', W + S)
        self.assertIn('-PqaX86Debug=true', W)
        self.assertIn('-PqaX86Debug=true connectedDebugAndroidTest', S)
        self.assertIn('testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest', W)
        self.assertIn('adb pull /sdcard/Download/bold-keys-qa', S)
    def test_cold_transport_uses_the_full_shared_boot_budget(self):
        self.assertNotIn('adb wait-for-device', S)
        self.assertLess(S.index('boot_deadline=$((SECONDS + 1200))'), S.index('adb shell getprop sys.boot_completed'))
        self.assertIn('SECONDS >= boot_deadline', S)
        self.assertIn('kill -0 "$emulator_pid"', S)
        self.assertIn('timeout 10 adb shell getprop sys.boot_completed', S)
        self.assertIn('shared transport/boot budget', S)
    def test_sparse_reclaim_checks_full_image_identity(self):
        self.assertIn("['fallocate', '--dig-holes', str(path)]", P)
        self.assertIn('hashlib.sha256()', P)
        self.assertIn("before['size'] == after['size'] and before['sha256'] == after['sha256']", P)
        self.assertIn('do not boot this image', P)
    def test_sparse_helper_preserves_nonzero_data(self):
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / 'system.img'
            image.write_bytes(b'header' + bytes(3 * 1024 * 1024) + b'trailer')
            with image.open('r+b') as stream:
                stream.seek(1024 * 1024)
                stream.write(b'nonzero middle sample')
            expected = hashlib.sha256(image.read_bytes()).hexdigest()
            result = subprocess.run([sys.executable, str(R / 'tools/qa/sparsify-system-image.py'), str(image)], check=True, text=True, capture_output=True)
            evidence = json.loads(result.stdout)
            self.assertTrue(evidence['logical_bytes_unchanged'])
            self.assertEqual(expected, evidence['after']['sha256'])
            self.assertEqual(evidence['before']['size'], evidence['after']['size'])
            self.assertEqual(expected, hashlib.sha256(image.read_bytes()).hexdigest())

if __name__ == '__main__':
    unittest.main()
