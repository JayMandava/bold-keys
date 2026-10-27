"""Boundary checks for independent non-privileged software-emulator UI validation."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
R = Path(__file__).resolve().parents[1]
W = (R / '.github/workflows/colour-software-ui-qa.yml').read_text()
S = (R / 'tools/qa/run-software-ui.sh').read_text()
P = (R / 'tools/qa/sparsify-system-image.py').read_text()
READY = (R / 'tools/qa/wait-software-ui-ready.py').read_text()

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
            self.assertNotIn(forbidden, W + S + P + READY)
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
        self.assertIn('-PqaX86Debug=true -Pandroid.testInstrumentationRunnerArguments.softwareEmulator=true', S)
        self.assertIn('connectedDebugAndroidTest', S)
        self.assertIn('testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest', W)
        self.assertIn('adb pull /sdcard/Download/bold-keys-qa', S)
    def test_cold_transport_uses_the_full_shared_boot_budget(self):
        self.assertNotIn('adb wait-for-device', S)
        self.assertLess(S.index('boot_deadline=$((SECONDS + 1200))'), S.index('adb shell getprop sys.boot_completed'))
        self.assertIn('SECONDS >= boot_deadline', S)
        self.assertIn('kill -0 "$emulator_pid"', S)
        self.assertIn('timeout 10 adb shell getprop sys.boot_completed', S)
        self.assertIn('shared transport/boot budget', S)
    def test_readiness_is_inside_existing_startup_budget_and_preserves_failures(self):
        self.assertIn('readiness_budget=$((boot_deadline - SECONDS))', S)
        self.assertIn('readiness_budget > 600', S)
        self.assertIn('Another ANR dialog appeared after the single startup recovery', READY)
        self.assertIn("time.monotonic() - quiet_since >= 60", READY)
        self.assertIn("output / 'startup-dialog.png'", READY)
        self.assertIn("output / 'ready-home.png'", READY)
        self.assertIn('product tests still required', READY)
    def test_readiness_wait_target_is_exact_and_on_screen(self):
        spec = importlib.util.spec_from_file_location('readiness', R / 'tools/qa/wait-software-ui-ready.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        xml = '''<hierarchy><node text="System UI isn't responding"/>
          <node text="Wait" package="android" resource-id="android:id/aerr_wait" clickable="true" bounds="[100,650][650,750]"/></hierarchy>'''
        focus = 'mCurrentFocus=Window{abc u0 Application Not Responding: com.android.systemui}'
        self.assertEqual((375,700), module.wait_button(xml,focus))
        for bad_xml,bad_focus in ((xml,focus.replace('com.android.systemui','ai.boldkeys')),
                                  (xml.replace('System UI', 'Bold Keys'),focus),
                                  (xml.replace('aerr_wait','aerr_close'),focus),
                                  (xml.replace('[650,750]','[900,750]'),focus),
                                  (xml.replace('clickable="true"','clickable="false"'),focus)):
            with self.assertRaises(ValueError):module.wait_button(bad_xml,bad_focus)
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

class HomeReadinessRegressionTests(unittest.TestCase):
    # Real focus captured in run 40438950301; no dependency on a local artifact directory.
    GOOGLE_FOCUS = 'mCurrentFocus=Window{73ad53 u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}'
    GOOGLE_HOME = 'com.google.android.apps.nexuslauncher/.NexusLauncherActivity'
    AOSP_HOME = 'com.android.launcher3/.uioverrides.QuickstepLauncher'

    def setUp(self):
        spec = importlib.util.spec_from_file_location('readiness_regression', R / 'tools/qa/wait-software-ui-ready.py')
        self.ready = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.ready)

    def test_captured_google_focus_matches_resolved_short_or_full_component(self):
        short = self.ready.resolved_home(self.GOOGLE_HOME + '\n')
        full = self.ready.resolved_home('priority=0 preferredOrder=0 match=0x108000\n' +
                                        'com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity\n')
        self.assertEqual(short, full)
        self.assertTrue(self.ready.home_is_focused(self.GOOGLE_FOCUS, short))
        self.assertTrue(self.ready.home_is_focused('mCurrentFocus=Window{abc u0 ' + self.GOOGLE_HOME + '}', full))

    def test_aosp_focus_matches_resolved_home_without_special_casing(self):
        home = self.ready.resolved_home(self.AOSP_HOME)
        self.assertEqual('com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher', home)
        self.assertTrue(self.ready.home_is_focused('mCurrentFocus=Window{abc u0 ' + self.AOSP_HOME + '}', home))

    def test_unresolved_invalid_and_ambiguous_resolution_fail_closed(self):
        for output in ('', 'No activity found\n', 'priority=0\n', 'not a/component',
                       self.GOOGLE_HOME + '\n' + self.AOSP_HOME, self.GOOGLE_HOME + '\n' + self.GOOGLE_HOME):
            with self.subTest(output=output), self.assertRaises(ValueError):
                self.ready.resolved_home(output)
        for value in ('com.example/', '/.Launcher', 'com.example/.Launcher;input', 'com.example/.Launcher extra'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.ready.normalized_component(value)

    def test_wrong_foreground_activity_package_or_ambiguous_focus_is_rejected(self):
        home = self.ready.resolved_home(self.GOOGLE_HOME)
        for focus in ('mCurrentFocus=null',
                      'mCurrentFocus=Window{abc u0 com.google.android.apps.nexuslauncher/.SettingsActivity}',
                      'mCurrentFocus=Window{abc u0 com.other.launcher/.NexusLauncherActivity}',
                      'mCurrentFocus=Window{abc u0 ai.boldkeys/.SetupActivity}',
                      self.GOOGLE_FOCUS + ' ' + self.AOSP_HOME):
            with self.subTest(focus=focus):
                self.assertFalse(self.ready.home_is_focused(focus, home))

    def test_instrumentation_uses_actual_setup_window_not_accessibility_event_package(self):
        source = (R / 'app/src/androidTest/java/ai/boldkeys/ColourUiTest.kt').read_text()
        helper = source.split('private fun setupWindowFocused(): Boolean {', 1)[1].split('private fun textOnScreen', 1)[0]
        self.assertIn('executeShellCommand("dumpsys window")', helper)
        expression = re.search(r'Regex\("""(.*?)"""\)', helper, re.S).group(1)
        for activity in ('.SetupActivity', 'ai.boldkeys.SetupActivity'):
            self.assertIsNotNone(re.fullmatch(expression, 'mCurrentFocus=Window{abc u0 ai.boldkeys/' + activity + '}'))
        for focus in (self.GOOGLE_FOCUS, 'mCurrentFocus=null',
                      'mCurrentFocus=Window{abc u0 ai.boldkeys/.KeyboardQaActivity}',
                      'mCurrentFocus=Window{abc u0 Application Not Responding: ai.boldkeys}',
                      'mCurrentFocus=Window{abc u0 com.other/ai.boldkeys.SetupActivity}'):
            self.assertIsNone(re.fullmatch(expression, focus))

    def test_recording_waits_for_output_and_finalization_without_skipping_ui(self):
        source = (R / 'app/src/androidTest/java/ai/boldkeys/ColourUiTest.kt').read_text()
        recorder = source.split('private fun recording(name: String, action: () -> Unit) {', 1)[1].split('@Test fun a_', 1)[0]
        self.assertIn('screenrecord --verbose', recorder)
        self.assertEqual(2, recorder.count('SystemClock.uptimeMillis() + 10_000'))
        self.assertIn('cat /proc/$pid/comm', recorder)
        self.assertIn('size > 4096L && alive()', recorder)
        self.assertIn('file bytes never prove recorded frames', recorder)
        self.assertNotIn('deadline && alive()', recorder)
        self.assertLess(recorder.index('SystemClock.sleep(1000)'), recorder.index('action() // Functional'))
        self.assertIn('if (alive()) device.executeShellCommand("kill -INT $pid")', recorder)
        self.assertIn('Recorder did not finalize within ten seconds; do not use this clip', recorder)
        self.assertIn('Verify decoding and actual action coverage', recorder)

    def run_mock_readiness(self, resolution, focus, hierarchy_package):
        """Exercise readiness itself using only synthetic ADB replies and a simulated clock."""
        now = [0.0]
        calls = []
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)

        def sleep(seconds):
            now[0] += seconds

        def adb_reply(arguments, **kwargs):
            calls.append(arguments)
            self.assertEqual('adb', arguments[0])
            command = arguments[1:]
            if command == ['shell', 'id', '-u']:
                output = b'2000\n'
            elif command[:4] == ['shell', 'cmd', 'package', 'resolve-activity']:
                self.assertEqual(['--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'], command[4:])
                output = resolution.encode()
            elif command == ['shell', 'input', 'keyevent', 'KEYCODE_HOME']:
                output = b''
            elif command == ['shell', 'dumpsys', 'window']:
                output = focus.encode()
            elif command == ['logcat', '-d', '-v', 'threadtime', '-s', 'ActivityManager:E']:
                output = b''
            elif command == ['shell', 'uiautomator', 'dump', '/sdcard/Download/bold-readiness.xml']:
                output = b'UI hierarchy dumped\n'
            elif command == ['exec-out', 'cat', '/sdcard/Download/bold-readiness.xml']:
                output = ('<hierarchy><node package="' + hierarchy_package + '"/></hierarchy>').encode()
            elif command == ['exec-out', 'screencap', '-p']:
                output = b'mock screenshot bytes: not device evidence'
            else:
                self.fail('Unexpected command; no real ADB commands are allowed: ' + repr(arguments))
            self.assertTrue(kwargs['check'])
            self.assertGreater(kwargs['timeout'], 0)
            return SimpleNamespace(stdout=output)

        real_path = Path
        with (patch.object(self.ready.subprocess, 'run', side_effect=adb_reply),
              patch.object(self.ready.argparse.ArgumentParser, 'parse_args', return_value=SimpleNamespace(timeout=90)),
              patch.object(self.ready.time, 'monotonic', side_effect=lambda: now[0]),
              patch.object(self.ready.time, 'sleep', side_effect=sleep),
              patch.object(self.ready, 'Path', side_effect=lambda path: directory / real_path(path)),
              patch.object(self.ready, 'print', create=True)):
            self.ready.main()
        return directory, calls

    def test_real_google_and_aosp_shapes_pass_mock_readiness_and_capture(self):
        for resolution, focus in ((self.GOOGLE_HOME, self.GOOGLE_FOCUS),
                                  (self.AOSP_HOME, 'mCurrentFocus=Window{abc u0 ' + self.AOSP_HOME + '}')):
            with self.subTest(resolution=resolution):
                directory, calls = self.run_mock_readiness(resolution, focus, resolution.partition('/')[0])
                events = json.loads((directory / 'qa-artifacts/readiness/events.json').read_text())
                self.assertEqual('Ordinary home UI ready; product tests still required', events[-1]['event'])
                self.assertIn(['adb', 'exec-out', 'screencap', '-p'], calls)

    def test_matching_focus_but_wrong_hierarchy_package_fails(self):
        with self.assertRaisesRegex(RuntimeError, 'not present in actual hierarchy'):
            self.run_mock_readiness(self.GOOGLE_HOME, self.GOOGLE_FOCUS, 'com.android.launcher3')

    def test_unresolved_home_never_attempts_readiness_loop(self):
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            self.run_mock_readiness('No activity found', self.GOOGLE_FOCUS, 'com.google.android.apps.nexuslauncher')


if __name__ == '__main__':
    unittest.main()
