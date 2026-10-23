#!/usr/bin/env python3
"""Credential-free regression tests for release validation and no-write validation mode."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import subprocess
import textwrap
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('release', ROOT / 'tools/release/release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
SHA = 'a' * 40


class ReleaseChecks(unittest.TestCase):
    def test_stable_versions_only(self):
        self.assertEqual(release.version_tuple('0.6.1'), (0, 6, 1))
        for version in ['v0.6.1', '0.06.1', '0.6', '0.6.1-rc1', '0.6.1\n', '$(echo hi)', '../0.6.1']:
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.version_tuple(version)

    def test_source_version_unambiguous(self):
        self.assertEqual(release.source_version('versionCode = 9\nversionName = "0.6.1"'), ('0.6.1', 9))
        with self.assertRaises(ValueError):
            release.source_version('versionCode = 9\nversionName = "0.6.1"\nversionName = "other"')

    def valid_gate(self):
        return {'can_admins_bypass': False, 'deployment_branch_policy': {'custom_branch_policies': True, 'protected_branches': False}, 'protection_rules': [{'type': 'required_reviewers', 'prevent_self_review': False, 'reviewers': [{'type': 'User', 'reviewer': {'login': 'JayMandava'}}]}]}

    def test_exact_owner_main_gate(self):
        with patch.object(release, 'api', side_effect=[self.valid_gate(), {'total_count': 1, 'branch_policies': [{'name': 'main', 'type': 'branch'}]}]):
            release.gate()

    def test_gate_rejects_missing_bypass_or_review_rules(self):
        for change in ['bypass', 'review', 'branch', 'self']:
            value = self.valid_gate()
            if change == 'bypass':
                del value['can_admins_bypass']
            elif change == 'review':
                value['protection_rules'] = []
            elif change == 'branch':
                value['deployment_branch_policy']['protected_branches'] = True
            else:
                value['protection_rules'][0]['prevent_self_review'] = True
            with self.subTest(change=change), patch.object(release, 'api', return_value=value), self.assertRaises(ValueError):
                release.gate()

    def test_gate_rejects_tag_or_wildcard_policy(self):
        for policy in [{'name': '*', 'type': 'branch'}, {'name': 'main', 'type': 'tag'}]:
            with self.subTest(policy=policy), patch.object(release, 'api', side_effect=[self.valid_gate(), {'total_count': 1, 'branch_policies': [policy]}]), self.assertRaises(ValueError):
                release.gate()

    def env(self):
        return {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REPOSITORY': release.REPO, 'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': SHA, 'GITHUB_WORKFLOW_SHA': SHA}

    def test_validation_only_never_checks_or_mutates_existing_tag(self):
        calls = []
        def fake_api(path, *args, **kwargs):
            calls.append((path, args))
            self.assertEqual(path, f'/repos/{release.REPO}/git/ref/heads/main')
            return {'object': {'sha': SHA}}
        with patch.dict(os.environ, self.env()), patch.object(release, 'api', side_effect=fake_api), patch.object(release, 'gate'), patch.object(Path, 'read_text', return_value='versionCode = 9\nversionName = "0.6.1"'), patch.object(Path, 'is_file', return_value=True):
            self.assertEqual(release.preflight('0.6.1', SHA, 'validate'), 9)
        self.assertEqual(len(calls), 1)

    def test_main_movement_rejected(self):
        with patch.dict(os.environ, self.env()), patch.object(release, 'api', return_value={'object': {'sha': 'b' * 40}}), self.assertRaises(ValueError):
            release.preflight('0.6.1', SHA, 'validate')

    def test_fork_pr_and_nonmain_rejected(self):
        for key, value in [('GITHUB_EVENT_NAME', 'pull_request_target'), ('GITHUB_REPOSITORY', 'attacker/fork'), ('GITHUB_REF', 'refs/heads/feature'), ('GITHUB_SHA', 'b' * 40), ('GITHUB_WORKFLOW_SHA', 'b' * 40)]:
            env = self.env() | {key: value}
            with self.subTest(key=key), patch.dict(os.environ, env), patch.object(release, 'api') as api, self.assertRaises(ValueError):
                release.preflight('0.6.1', SHA, 'validate')
            api.assert_not_called()

    def test_publish_refuses_existing_tag_before_writes(self):
        with patch.dict(os.environ, self.env()), patch.object(release, 'api', side_effect=[{'object': {'sha': SHA}}, {'ref': 'refs/tags/v0.6.1'}]) as api, patch.object(release, 'gate'), patch.object(Path, 'read_text', return_value='versionCode = 9\nversionName = "0.6.1"'), self.assertRaisesRegex(ValueError, 'Tag already exists'):
            release.preflight('0.6.1', SHA, 'publish')
        self.assertTrue(all(not call.args[1:] for call in api.call_args_list))

    def test_publish_refuses_existing_release_without_tag(self):
        with patch.dict(os.environ, self.env()), patch.object(release, 'api', side_effect=[{'object': {'sha': SHA}}, None, {'id': 1}]), patch.object(release, 'gate'), patch.object(Path, 'read_text', return_value='versionCode = 9\nversionName = "0.6.1"'), self.assertRaisesRegex(ValueError, 'Release already exists'):
            release.preflight('0.6.1', SHA, 'publish')

    def test_publish_requires_increasing_version_and_code(self):
        import base64
        old_source = base64.b64encode(b'versionCode = 9\nversionName = "0.6.1"').decode()
        for version, code, expected in [('0.6.1', 10, 'Version must increase'), ('0.6.2', 9, 'versionCode must increase')]:
            responses = [{'object': {'sha': SHA}}, None, None, {'tag_name': 'v0.6.1'}, {'content': old_source}]
            with self.subTest(version=version), patch.dict(os.environ, self.env()), patch.object(release, 'api', side_effect=responses), patch.object(release, 'gate'), patch.object(Path, 'read_text', return_value=f'versionCode = {code}\nversionName = "{version}"'), self.assertRaisesRegex(ValueError, expected):
                release.preflight(version, SHA, 'publish')

    def test_api_404_only_is_absence(self):
        import urllib.error
        for status in [403, 429, 500]:
            error = urllib.error.HTTPError('https://api.github.com/example', status, 'failure', {}, None)
            with patch.dict(os.environ, {'GH_TOKEN': 'not-a-real-token'}), patch.object(release.URL_OPENER, 'open', side_effect=error), self.assertRaises(ValueError):
                release.api('/example', absent_ok=True)
        error = urllib.error.HTTPError('https://api.github.com/example', 404, 'missing', {}, None)
        with patch.dict(os.environ, {'GH_TOKEN': 'not-a-real-token'}), patch.object(release.URL_OPENER, 'open', side_effect=error):
            self.assertIsNone(release.api('/example', absent_ok=True))

    def test_api_redirects_rejected(self):
        import urllib.request
        request = urllib.request.Request('https://api.github.com/example', headers={'Authorization': 'Bearer harmless-test-sentinel'})
        for destination in ['https://attacker.invalid/steal', 'https://api.github.com/moved', 'http://api.github.com/insecure']:
            self.assertIsNone(release.NoRedirect().redirect_request(request, None, 302, 'redirect', {}, destination))
        import urllib.error
        error = urllib.error.HTTPError(request.full_url, 302, 'redirect', {}, None)
        with patch.dict(os.environ, {'GH_TOKEN': 'not-a-real-token'}), patch.object(release.URL_OPENER, 'open', side_effect=error), self.assertRaises(ValueError):
            release.api('/example', absent_ok=True)

    def test_api_rejects_arbitrary_origin(self):
        for path in ['https://elsewhere.invalid/secret', '//elsewhere.invalid/secret']:
            with self.assertRaises(ValueError):
                release.api(path)

    def test_artifact_tampering_detected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / 'unsigned.apk').write_bytes(b'not-a-real-apk')
            (directory / 'release-notes.md').write_text('reviewed notes')
            with patch.object(release, 'verify_apk'):
                digest = release.write_metadata(directory, '0.6.1', SHA, 9, False)
                release.metadata(directory, '0.6.1', SHA, 9, False, digest)
                (directory / 'unsigned.apk').write_bytes(b'tampered')
                with self.assertRaisesRegex(ValueError, 'APK digest mismatch'):
                    release.metadata(directory, '0.6.1', SHA, 9, False, digest)
                with self.assertRaisesRegex(ValueError, 'manifest digest mismatch'):
                    release.metadata(directory, '0.6.1', SHA, 9, False, '0' * 64)

    def test_missing_or_invalid_signing_secrets_fail_closed_and_clean_up(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        step = workflow.split('- name: Sign with existing production key')[1].split('- name: Verify production certificate')[0]
        script = textwrap.dedent(step.split('        run: |\n')[1])
        names = ['BOLD_RELEASE_KEYSTORE_BASE64', 'BOLD_RELEASE_STORE_PASSWORD', 'BOLD_RELEASE_KEY_ALIAS', 'BOLD_RELEASE_KEY_PASSWORD']
        fixtures = [{}, dict(zip(names, ['%', 'non-secret-test-password', 'fixture', 'non-secret-test-password'])), dict(zip(names, ['bm90LWEta2V5', 'fixture', 'androiddebugkey', 'fixture']))]
        for fixture in fixtures:
            with self.subTest(fixture=list(fixture)), tempfile.TemporaryDirectory() as temp:
                env = {key: value for key, value in os.environ.items() if key not in names}
                env.update(fixture)
                env['RUNNER_TEMP'] = temp
                result = subprocess.run(['bash', '-c', script], cwd=temp, env=env, text=True, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((Path(temp) / 'bold-release-key').exists())
                self.assertFalse((Path(temp) / 'signed').exists())
                self.assertNotIn('non-secret-test-password', result.stdout + result.stderr)

    def test_workflow_security_contract(self):
        # Text assertions supplement actionlint without a third-party test dependency.
        text = (ROOT / '.github/workflows/release.yml').read_text()
        import re
        uses = re.findall(r'uses: ([^\s]+)', text)
        self.assertTrue(uses)
        self.assertTrue(all(re.fullmatch(r'actions/[a-z-]+@[a-f0-9]{40}', action) for action in uses))
        self.assertNotIn('pull_request', text)
        self.assertNotIn('push:', text)
        self.assertEqual(text.count('contents: write'), 1)
        self.assertIn("if: inputs.operation == 'publish'", text)
        self.assertIn('environment: production-release', text)
        self.assertEqual(text.count('retention-days: 1'), 2)
        self.assertEqual(text.count('digest-mismatch: error'), 2)
        self.assertNotIn('cache:', text)
        self.assertNotIn('--licenses', text)
        self.assertEqual(text.count('"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install'), 3)
        self.assertNotRegex(text, r'(?m)^\s+(?:run: )?sdkmanager\b')
        secret_step = text.split('- name: Sign with existing production key')[1].split('- name: Verify production certificate')[0]
        self.assertNotIn('gradlew', secret_step)
        self.assertNotIn('python', secret_step)
        self.assertIn("trap 'rm -rf", secret_step)
        self.assertIn('--ks-pass env:BOLD_RELEASE_STORE_PASSWORD', secret_step)
        self.assertEqual(text.count('${{ secrets.'), 4)


if __name__ == '__main__':
    unittest.main()
