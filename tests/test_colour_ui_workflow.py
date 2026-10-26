"""Guard the feature-only UI runner's secret-free/no-permission-bypass boundary."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / '.github/workflows/colour-ui-qa.yml').read_text()
SCRIPT = (ROOT / 'tools/qa/run-cloud-ui.sh').read_text()


class ColourUiWorkflowTests(unittest.TestCase):
    def test_feature_branch_and_public_repo_only(self):
        self.assertIn('branches: [feat/iconic-colours]', WORKFLOW)
        self.assertIn("github.ref == 'refs/heads/feat/iconic-colours'", WORKFLOW)
        self.assertIn("github.repository == 'JayMandava/bold-keys'", WORKFLOW)
        self.assertIn('github.event.repository.private == false', WORKFLOW)
        for trigger in ('pull_request_target:', 'workflow_run:', 'schedule:'):
            self.assertNotIn(trigger, WORKFLOW)

    def test_read_only_and_no_protected_environment_or_secrets(self):
        self.assertIn('permissions:\n  contents: read', WORKFLOW)
        self.assertNotIn('contents: write', WORKFLOW)
        self.assertNotIn('environment:', WORKFLOW)
        self.assertNotIn('secrets.', WORKFLOW)
        self.assertNotIn('GH_TOKEN:', WORKFLOW)
        self.assertNotIn('GITHUB_TOKEN:', WORKFLOW)
        self.assertIn('persist-credentials: false', WORKFLOW)

    def test_actions_are_official_and_sha_pinned(self):
        uses = re.findall(r'uses:\s*(\S+)', WORKFLOW)
        self.assertEqual(3, len(uses))
        for action in uses:
            self.assertRegex(action, r'^actions/(checkout|setup-java|upload-artifact)@[0-9a-f]{40}$')

    def test_bounded_job_and_artifact_lifetime(self):
        self.assertIn('timeout-minutes: 45', WORKFLOW)
        self.assertIn('retention-days: 1', WORKFLOW)
        self.assertIn('if: always()', WORKFLOW)
        self.assertIn('timeout 20m ./gradlew', SCRIPT)
        self.assertIn('SECONDS + 600', SCRIPT)
        self.assertIn('trap cleanup EXIT', SCRIPT)

    def test_no_permission_or_license_workarounds(self):
        for forbidden in ('sudo ', 'setfacl ', 'chmod ', 'usermod ', 'udevadm ', '--privileged', '-selinux', '--licenses'):
            self.assertNotIn(forbidden, WORKFLOW + SCRIPT)
        self.assertIn('! -r /dev/kvm || ! -w /dev/kvm', WORKFLOW)
        self.assertIn('-accel on', SCRIPT)
        self.assertNotIn('-accel off', SCRIPT)
        self.assertNotRegex(WORKFLOW + SCRIPT, r'(?m)^\s*yes\s*[|]')
        self.assertIn("'system-images;android-35;google_apis;x86_64' </dev/null", WORKFLOW)

    def test_real_instrumentation_and_no_publication(self):
        self.assertIn('assembleDebugAndroidTest', WORKFLOW)
        self.assertIn('connectedDebugAndroidTest', SCRIPT)
        self.assertIn('-PunsignedRelease=true', WORKFLOW)
        self.assertIn('-PunsignedRelease=true', SCRIPT)
        for forbidden in ('apksigner sign', 'git push', 'gh release', 'release.py publish', 'gh workflow run'):
            self.assertNotIn(forbidden, WORKFLOW + SCRIPT)
        self.assertIn('arm64-v8a', SCRIPT)
        self.assertIn('adb pull /sdcard/Android/data/ai.boldkeys/files/qa', SCRIPT)
        self.assertIn('adb pull /sdcard/Download/bold-keys-qa', SCRIPT)


if __name__ == '__main__':
    unittest.main()
