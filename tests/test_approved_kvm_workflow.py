"""Mock-only checks for the one approved disposable job; never access /dev/kvm.

The dated branch must not be deleted/recreated: a creation event alone cannot
distinguish recreation. This approval does not authorize such a push or rerun.
"""
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('approved_kvm_access', ROOT / 'tools/qa/approved-kvm-access.py')
KVM = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(KVM)
STOP_OWNED = KVM.stop_owned_emulator
DEVICE_METADATA = KVM.metadata
WORKFLOW = (ROOT / '.github/workflows/colour-approved-kvm-qa-4.yml').read_text()
SCRIPT = (ROOT / 'tools/qa/run-approved-kvm-ui.sh').read_text()
BEFORE = {'uid': 0, 'gid': 108, 'mode': 0o660, 'rdev': 0x0A00E8}
UID = 1001
RUN = '40400000000'
GRANTED = ['user::rw-', f'user:{UID}:rw-', 'group::rw-', 'mask::rw-', 'other::---']


class AccessLifecycleTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.state = self.root / 'state'
        self.event = self.root / 'event.json'
        self.event.write_text(json.dumps({'created': True, 'deleted': False, 'ref': KVM.REF}))
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, {
            'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'JayMandava/bold-keys',
            'GITHUB_REF': KVM.REF, 'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_EVENT_NAME': 'push',
            'GITHUB_ACTOR': 'JayMandava', 'GITHUB_EVENT_PATH': str(self.event),
            'GITHUB_RUN_ID': RUN, 'RUNNER_TEMP': str(self.root), 'GITHUB_SHA': 'a' * 40,
        }, clear=True).start()
        # Every device/process boundary is mocked before a function under test can run.
        self.external = patch.object(KVM.subprocess, 'run', side_effect=AssertionError('Real subprocess forbidden')).start()
        self.urlopen = patch.object(KVM.urllib.request, 'urlopen', side_effect=AssertionError('Real network forbidden')).start()
        patch.object(KVM, 'print', create=True).start()  # Never print mocked ACL successes as real evidence.
        self.run = patch.object(KVM, 'run', side_effect=AssertionError('Unconfigured command forbidden')).start()
        self.acl = patch.object(KVM, 'acl', return_value=KVM.BASE.copy()).start()
        self.metadata = patch.object(KVM, 'metadata', return_value=BEFORE.copy()).start()
        self.stop = patch.object(KVM, 'stop_owned_emulator').start()
        self.kill = patch.object(KVM.os, 'kill', side_effect=AssertionError('Real process signals forbidden')).start()
        self.getuid = patch.object(KVM.os, 'getuid', return_value=UID).start()
        self.geteuid = patch.object(KVM.os, 'geteuid', return_value=UID).start()
        self.access = patch.object(KVM.os, 'access', return_value=True).start()
        patch.object(KVM.grp, 'getgrnam', return_value=SimpleNamespace(gr_gid=108)).start()
        write = KVM.write

        def isolated_write(path, data):
            path = Path(path)
            write(path if path.is_absolute() else self.root / path, data)

        patch.object(KVM, 'write', side_effect=isolated_write).start()

    def save_state(self, **changes):
        self.state.mkdir(exist_ok=True)
        data = {'uid': UID, 'run_id': RUN, 'before': BEFORE.copy(), 'phase': 'active'} | changes
        (self.state / 'state.json').write_text(json.dumps(data))

    def read_state(self):
        return json.loads((self.state / 'state.json').read_text())

    def test_exact_context_is_allowed_without_commands(self):
        self.assertEqual((UID, RUN), KVM.context())
        self.run.assert_not_called()
        self.external.assert_not_called()

    def test_context_rejects_other_jobs_actors_branches_and_reruns(self):
        for name, value in {
            'GITHUB_ACTIONS': 'false', 'GITHUB_REPOSITORY': 'other/bold-keys',
            'GITHUB_REF': 'refs/heads/main', 'GITHUB_RUN_ATTEMPT': '2',
            'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_ACTOR': 'someone-else',
        }.items():
            with self.subTest(name=name), patch.dict(os.environ, {name: value}):
                with self.assertRaises(RuntimeError):
                    KVM.context()
        self.run.assert_not_called()

    def test_consumed_first_approval_branch_cannot_use_fourth_grant(self):
        with patch.dict(os.environ, {'GITHUB_REF': 'refs/heads/qa/iconic-colours-kvm-20261027'}):
            with self.assertRaises(RuntimeError):
                KVM.context()
        self.run.assert_not_called()

    def test_consumed_second_approval_branch_cannot_use_fourth_grant(self):
        with patch.dict(os.environ, {'GITHUB_REF': 'refs/heads/qa/iconic-colours-kvm-20261027-2'}):
            with self.assertRaises(RuntimeError):
                KVM.context()
        self.run.assert_not_called()

    def test_consumed_third_approval_branch_cannot_use_fourth_grant(self):
        with patch.dict(os.environ, {'GITHUB_REF': 'refs/heads/qa/iconic-colours-kvm-20261027-3'}):
            with self.assertRaises(RuntimeError):
                KVM.context()
        self.run.assert_not_called()

    def test_context_requires_creation_not_later_push_or_deletion(self):
        for event in ({'ref': KVM.REF}, {'created': False, 'ref': KVM.REF},
                      {'created': True, 'deleted': True, 'ref': KVM.REF},
                      {'created': True, 'ref': 'refs/heads/other'}):
            with self.subTest(event=event):
                self.event.write_text(json.dumps(event))
                with self.assertRaises(RuntimeError):
                    KVM.context()
        self.run.assert_not_called()

    def test_context_rejects_root_and_effective_uid_mismatch(self):
        self.getuid.return_value = 0
        with self.assertRaises(RuntimeError):
            KVM.context()
        self.getuid.return_value = UID
        self.geteuid.return_value = 0
        with self.assertRaises(RuntimeError):
            KVM.context()

    def test_baseline_is_exact_and_read_only(self):
        self.assertEqual(BEFORE, KVM.baseline())
        self.run.assert_not_called()

    def test_device_metadata_requires_non_symlink_character_device(self):
        fake_stat = SimpleNamespace(st_uid=0, st_gid=108, st_mode=stat.S_IFCHR | 0o660, st_rdev=BEFORE['rdev'])
        with patch.object(Path, 'is_symlink', return_value=False), patch.object(KVM.os, 'stat', return_value=fake_stat):
            self.assertEqual(BEFORE, DEVICE_METADATA())
            fake_stat.st_mode = stat.S_IFREG | 0o660
            with self.assertRaisesRegex(RuntimeError, 'not a character device'):
                DEVICE_METADATA()
        with patch.object(Path, 'is_symlink', return_value=True), patch.object(KVM.os, 'stat') as device_stat:
            with self.assertRaisesRegex(RuntimeError, 'symlink'):
                DEVICE_METADATA()
            device_stat.assert_not_called()

    def test_baseline_refuses_changed_owner_group_mode_or_acl(self):
        for key, value in (('uid', UID), ('gid', 109), ('mode', 0o666)):
            with self.subTest(key=key):
                self.metadata.return_value = BEFORE | {key: value}
                with self.assertRaises(RuntimeError):
                    KVM.baseline()
        self.metadata.return_value = BEFORE.copy()
        self.acl.return_value = GRANTED
        with self.assertRaises(RuntimeError):
            KVM.baseline()
        self.run.assert_not_called()

    def test_grant_records_intent_before_only_runner_acl_command(self):
        self.acl.side_effect = [KVM.BASE, GRANTED]

        def command(*args):
            self.assertEqual('grant_intent', self.read_state()['phase'])
            self.assertEqual(('sudo', '-n', 'setfacl', '--no-mask', '--modify',
                              f'user:{UID}:rw-,mask::rw-', '/dev/kvm'), args)
            return ''

        self.run.side_effect = command
        KVM.grant(self.state, UID, RUN)
        self.assertEqual('active', self.read_state()['phase'])
        self.run.assert_called_once()
        self.assertTrue((self.root / 'qa-artifacts/kvm-grant.json').is_file())
        self.external.assert_not_called()

    def test_all_existing_phases_consume_the_single_grant_attempt(self):
        for phase in ('grant_intent', 'active', 'cleaned'):
            with self.subTest(phase=phase):
                self.save_state(phase=phase)
                with self.assertRaisesRegex(RuntimeError, 'single grant'):
                    KVM.grant(self.state, UID, RUN)
        self.run.assert_not_called()

    def test_failed_sudo_leaves_durable_intent_and_cleanup_is_safe(self):
        self.run.side_effect = subprocess.CalledProcessError(1, ['mock-sudo'])
        with self.assertRaises(subprocess.CalledProcessError):
            KVM.grant(self.state, UID, RUN)
        self.assertEqual('grant_intent', self.read_state()['phase'])
        self.run.reset_mock()
        KVM.cleanup(self.state, UID, RUN)
        self.run.assert_not_called()
        self.assertEqual('cleaned', self.read_state()['phase'])

    def test_unverified_grant_fails_without_marking_active(self):
        for failure in ('acl', 'metadata', 'access'):
            with self.subTest(failure=failure):
                (self.state / 'state.json').unlink(missing_ok=True)
                self.acl.side_effect = [KVM.BASE, KVM.BASE if failure == 'acl' else GRANTED]
                self.metadata.side_effect = [BEFORE, BEFORE | {'rdev': 9}] if failure == 'metadata' else None
                self.access.return_value = failure != 'access'
                self.run.side_effect = None
                self.run.return_value = ''
                with self.assertRaisesRegex(RuntimeError, 'not verified'):
                    KVM.grant(self.state, UID, RUN)
                self.assertEqual('grant_intent', self.read_state()['phase'])

    def test_cleanup_without_intent_never_touches_acl_or_processes(self):
        KVM.cleanup(self.state, UID, RUN)
        self.run.assert_not_called()
        self.acl.assert_not_called()
        self.stop.assert_not_called()

    def test_cleanup_refuses_another_uid_or_run(self):
        for changes in ({'uid': UID + 1}, {'run_id': 'other'}):
            with self.subTest(changes=changes):
                self.save_state(**changes)
                with self.assertRaisesRegex(RuntimeError, 'exact approved job'):
                    KVM.cleanup(self.state, UID, RUN)
        self.run.assert_not_called()
        self.stop.assert_not_called()

    def test_cleanup_stops_owned_emulator_before_restoring_exact_baseline(self):
        self.save_state()
        self.acl.side_effect = [GRANTED, KVM.BASE, KVM.BASE, KVM.BASE]
        events = []
        self.stop.side_effect = lambda *args: events.append('stop')
        self.run.side_effect = lambda *args: events.append(args) or ''
        KVM.cleanup(self.state, UID, RUN)
        self.assertEqual(['stop', ('sudo', '-n', 'setfacl', '--set', ','.join(KVM.BASE), '/dev/kvm')], events)
        self.assertEqual('cleaned', self.read_state()['phase'])
        result = json.loads((self.root / 'qa-artifacts/kvm-cleanup.json').read_text())
        self.assertTrue(result['restored_exact_baseline'])
        self.assertTrue(result['runner_grant_absent'])

    def test_cleanup_is_idempotent_after_success(self):
        self.save_state(phase='cleaned')
        KVM.cleanup(self.state, UID, RUN)
        KVM.cleanup(self.state, UID, RUN)
        self.run.assert_not_called()
        self.assertEqual('cleaned', self.read_state()['phase'])

    def test_stop_failure_still_restores_acl_but_cannot_report_success(self):
        self.save_state()
        self.stop.side_effect = RuntimeError('mock owned process failed to stop')
        self.acl.side_effect = [GRANTED, KVM.BASE, KVM.BASE, KVM.BASE]
        self.run.side_effect = None
        with self.assertRaisesRegex(RuntimeError, 'Cleanup verification failed'):
            KVM.cleanup(self.state, UID, RUN)
        self.run.assert_called_once_with('sudo', '-n', 'setfacl', '--set', ','.join(KVM.BASE), '/dev/kvm')
        result = json.loads((self.root / 'qa-artifacts/kvm-cleanup.json').read_text())
        self.assertTrue(result['restored_exact_baseline'])
        self.assertIn('failed to stop', result['emulator_stop_error'])
        self.assertNotEqual('cleaned', self.read_state()['phase'])

    def test_unexpected_acl_removes_only_runner_entry_without_recalculating_mask(self):
        self.save_state()
        self.acl.return_value = GRANTED + ['user:2000:rwx']
        self.run.side_effect = None
        with self.assertRaisesRegex(RuntimeError, 'Unexpected ACL mutation'):
            KVM.cleanup(self.state, UID, RUN)
        self.run.assert_called_once_with('sudo', '-n', 'setfacl', '--no-mask', '--remove', f'user:{UID}', '/dev/kvm')
        self.assertNotEqual('cleaned', self.read_state()['phase'])

    def test_unexpected_acl_without_our_entry_is_not_overwritten(self):
        self.save_state()
        self.acl.return_value = KVM.BASE + ['user:2000:rw-', 'mask::rw-']
        with self.assertRaisesRegex(RuntimeError, 'Unexpected ACL mutation'):
            KVM.cleanup(self.state, UID, RUN)
        self.run.assert_not_called()

    def test_metadata_mismatch_cannot_be_claimed_as_exact_restoration(self):
        self.save_state()
        self.metadata.return_value = BEFORE | {'rdev': 9}
        with self.assertRaisesRegex(RuntimeError, 'Cleanup verification failed'):
            KVM.cleanup(self.state, UID, RUN)
        self.assertNotEqual('cleaned', self.read_state()['phase'])

    def test_public_history_accepts_only_exact_current_run_without_credentials(self):
        data = {'total_count': 1, 'workflow_runs': [{'id': int(RUN), 'run_attempt': 1, 'head_sha': 'a' * 40}]}
        self.urlopen.side_effect = None
        self.urlopen.return_value = io.StringIO(json.dumps(data))
        KVM.verify_sole_run(RUN)
        request = self.urlopen.call_args.args[0]
        self.assertTrue(request.full_url.startswith('https://api.github.com/repos/JayMandava/bold-keys/actions/workflows/colour-approved-kvm-qa-4.yml/runs?'))
        self.assertIn('qa%2Ficonic-colours-kvm-20261027-4', request.full_url)
        self.assertFalse(any(key.lower() == 'authorization' for key in request.headers))
        self.assertEqual(20, self.urlopen.call_args.kwargs['timeout'])
        self.assertTrue((self.root / 'qa-artifacts/one-job-approval.json').is_file())
        self.run.assert_not_called()

    def test_missing_previous_ambiguous_rerun_or_other_sha_history_fails_closed(self):
        good = {'id': int(RUN), 'run_attempt': 1, 'head_sha': 'a' * 40}
        cases = [{}, {'total_count': 0, 'workflow_runs': []},
                 {'total_count': 2, 'workflow_runs': [good, good]},
                 {'total_count': 1, 'workflow_runs': []},
                 *({'total_count': 1, 'workflow_runs': [good | change]} for change in
                   ({'id': int(RUN) - 1}, {'run_attempt': 2}, {'head_sha': 'b' * 40}))]
        self.urlopen.side_effect = None
        for data in cases:
            with self.subTest(data=data):
                self.urlopen.return_value = io.StringIO(json.dumps(data))
                with self.assertRaises(RuntimeError):
                    KVM.verify_sole_run(RUN)
        self.run.assert_not_called()

    def test_history_network_or_json_failure_cannot_grant(self):
        self.urlopen.side_effect = TimeoutError('mock unavailable public history')
        with self.assertRaises(TimeoutError):
            KVM.verify_sole_run(RUN)
        self.urlopen.side_effect = None
        self.urlopen.return_value = io.StringIO('not JSON')
        with self.assertRaises(json.JSONDecodeError):
            KVM.verify_sole_run(RUN)
        self.run.assert_not_called()

    def test_main_cannot_grant_before_successful_single_run_check(self):
        with (patch.object(KVM.argparse.ArgumentParser, 'parse_args', return_value=SimpleNamespace(operation='grant')),
                patch.object(KVM, 'verify_sole_run', side_effect=RuntimeError('mock repeated run')) as history,
                patch.object(KVM, 'grant') as grant):
            with self.assertRaises(RuntimeError):
                KVM.main()
            history.assert_called_once_with(RUN)
            grant.assert_not_called()

    def test_main_verifies_single_run_immediately_before_mock_grant(self):
        events = []
        with (patch.object(KVM.argparse.ArgumentParser, 'parse_args', return_value=SimpleNamespace(operation='grant')),
                patch.object(KVM, 'verify_sole_run', side_effect=lambda run_id: events.append(('verify', run_id))),
                patch.object(KVM, 'grant', side_effect=lambda state, uid, run_id: events.append(('mock_grant', run_id)))):
            KVM.main()
        self.assertEqual([('verify', RUN), ('mock_grant', RUN)], events)
        self.run.assert_not_called()

    def test_cleanup_never_depends_on_public_history_availability(self):
        with (patch.object(KVM.argparse.ArgumentParser, 'parse_args', return_value=SimpleNamespace(operation='cleanup')),
                patch.object(KVM, 'verify_sole_run', side_effect=RuntimeError('mock network failure')) as history,
                patch.object(KVM, 'cleanup') as cleanup):
            KVM.main()
            history.assert_not_called()
            cleanup.assert_called_once()

    def test_process_guard_refuses_unrelated_or_different_uid(self):
        self.state.mkdir()
        (self.state / 'emulator.pid').write_text('4321')
        proc = self.root / 'proc/4321'
        proc.mkdir(parents=True)
        real_path = Path
        with patch.object(KVM, 'Path', side_effect=lambda value: self.root / 'proc' if value == '/proc' else real_path(value)):
            for uid, command in ((UID + 1, 'emulator -avd bold-approved-colours-35'),
                                 (UID, 'emulator -avd unrelated')):
                with self.subTest(uid=uid, command=command):
                    (proc / 'status').write_text(f'State:\tS (sleeping)\nUid:\t{uid}\t{uid}\n')
                    (proc / 'cmdline').write_text(command)
                    with self.assertRaisesRegex(RuntimeError, 'unrelated or differently owned'):
                        STOP_OWNED(self.state, UID)
        self.kill.assert_not_called()

    def test_exited_or_zombie_owned_process_does_not_get_signalled(self):
        self.state.mkdir()
        (self.state / 'emulator.pid').write_text('4321')
        proc = self.root / 'proc/4321'
        real_path = Path
        with patch.object(KVM, 'Path', side_effect=lambda value: self.root / 'proc' if value == '/proc' else real_path(value)):
            STOP_OWNED(self.state, UID)
            proc.mkdir(parents=True)
            (proc / 'status').write_text(f'State:\tZ (zombie)\nUid:\t{UID}\t{UID}\n')
            STOP_OWNED(self.state, UID)
        self.kill.assert_not_called()


class ApprovedWorkflowTests(unittest.TestCase):
    def test_single_initial_owner_creation_job_not_dispatch_or_rerun(self):
        self.assertIn('branches: [qa/iconic-colours-kvm-20261027-4]', WORKFLOW)
        for guard in ("github.repository == 'JayMandava/bold-keys'", 'github.event.repository.private == false',
                      "github.actor == 'JayMandava'", "github.ref == '" + KVM.REF + "'",
                      'github.event.created == true', 'github.run_attempt == 1'):
            self.assertIn(guard, WORKFLOW)
        for trigger in ('workflow_dispatch:', 'workflow_run:', 'pull_request_target:', 'pull_request:', 'schedule:'):
            self.assertNotIn(trigger, WORKFLOW)

    def test_official_pinned_read_only_no_secrets_or_release(self):
        self.assertIn('permissions:\n  contents: read', WORKFLOW)
        self.assertIn('persist-credentials: false', WORKFLOW)
        for action in re.findall(r'uses:\s*(\S+)', WORKFLOW):
            self.assertRegex(action, r'^actions/(checkout|setup-java|upload-artifact)@[0-9a-f]{40}$')
        for forbidden in ('contents: write', 'environment:', 'secrets.', 'GH_TOKEN:', 'GITHUB_TOKEN:',
                          'apksigner sign', 'git push', 'gh release', 'gh workflow run'):
            self.assertNotIn(forbidden, WORKFLOW + SCRIPT)

    def test_build_before_grant_and_two_independent_cleanup_paths(self):
        self.assertLess(WORKFLOW.index('assembleDebugAndroidTest'), WORKFLOW.index('approved-kvm-access.py grant'))
        self.assertIn('trap cleanup EXIT', WORKFLOW)
        self.assertIn("trap 'exit 130' INT", WORKFLOW)
        self.assertIn("trap 'exit 143' TERM", WORKFLOW)
        self.assertRegex(WORKFLOW, r'Always remove grant[^\n]*\n\s+if: always\(\)')
        self.assertGreaterEqual(WORKFLOW.count('approved-kvm-access.py cleanup'), 2)
        self.assertIn('trap cleanup EXIT', SCRIPT)
        self.assertIn('emulator.pid', SCRIPT)

    def test_bounded_full_rendering_arm64_ui_and_no_broader_permission_change(self):
        self.assertIn('timeout-minutes: 45', WORKFLOW)
        self.assertIn('timeout-minutes: 32', WORKFLOW)
        self.assertIn('retention-days: 1', WORKFLOW)
        self.assertIn('SECONDS + 600', SCRIPT)
        self.assertIn('timeout 20m ./gradlew', SCRIPT)
        self.assertIn('-accel on', SCRIPT)
        self.assertIn('arm64-v8a', SCRIPT)
        self.assertIn('connectedDebugAndroidTest', SCRIPT)
        self.assertIn('wait-software-ui-ready.py', SCRIPT)
        self.assertIn('adb pull /sdcard/Download/bold-keys-qa', SCRIPT)
        self.assertNotIn('-PqaX86Debug=true', WORKFLOW + SCRIPT)
        for forbidden in ('sudo ', 'chmod ', 'usermod ', 'udevadm ', '--privileged', '-selinux', '--licenses'):
            self.assertNotIn(forbidden, WORKFLOW + SCRIPT)
        self.assertIn("'system-images;android-35;google_apis;x86_64' </dev/null", WORKFLOW)


if __name__ == '__main__':
    unittest.main()
