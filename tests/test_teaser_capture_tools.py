"""Local-only safeguards: no emulator, grants, network, publication or real input."""
import importlib.util
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


STAGE = module('teaser_stage', 'tools/capture/stage_evidence.py')
SOURCE = module('teaser_source', 'tools/capture/verify_source.py')
PREFLIGHT = module('teaser_preflight', 'tools/capture/preflight.py')
WORKFLOW = (ROOT / '.github/workflows/teaser-capture-once.yml').read_text()
RUNTIME = (ROOT / 'tools/capture/run-capture.sh').read_text()


class WorkflowTests(unittest.TestCase):
    def test_single_owner_initial_push_only_and_bounded(self):
        self.assertIn('branches: [qa/bold-teaser-capture-20261029-2]', WORKFLOW)
        for guard in ("github.actor == 'JayMandava'", 'github.event.created == true', 'github.run_attempt == 1',
                      "github.ref == 'refs/heads/qa/bold-teaser-capture-20261029-2'", 'github.event.repository.private == false'):
            self.assertIn(guard, WORKFLOW)
        for trigger in ('workflow_dispatch:', 'pull_request:', 'pull_request_target:', 'schedule:', 'workflow_run:'):
            self.assertNotIn(trigger, WORKFLOW)
        self.assertIn('timeout-minutes: 45', WORKFLOW)
        self.assertIn('timeout-minutes: 32', WORKFLOW)
        self.assertIn('timeout 20m ./gradlew', RUNTIME)
        self.assertIn('SECONDS + 600', RUNTIME)

    def test_source_build_before_grant_and_independent_cleanup(self):
        self.assertLess(WORKFLOW.index('verify_source.py'), WORKFLOW.index('kvm_access.py grant'))
        self.assertLess(WORKFLOW.index('assembleDebugAndroidTest'), WORKFLOW.index('kvm_access.py grant'))
        self.assertGreaterEqual(WORKFLOW.count('kvm_access.py cleanup'), 2)
        self.assertIn('trap cleanup EXIT', WORKFLOW)
        self.assertIn('trap cleanup EXIT', RUNTIME)
        self.assertIn('if: always()', WORKFLOW)
        self.assertIn('bold-teaser-kvm-access/emulator.pid', RUNTIME)
        # Package provisioning is the only root action outside the isolated ACL
        # guard. It must never become root emulator/controller execution.
        safe_workflow = WORKFLOW.replace('sudo apt-get update', '').replace(
            'sudo apt-get install -y --no-install-recommends ffmpeg acl', '')
        for text in (safe_workflow, RUNTIME):
            for forbidden in ('chmod ', 'usermod ', 'udev', '--privileged', 'adb root', '-selinux', 'sudo '):
                self.assertNotIn(forbidden, text)

    def test_decoder_is_provisioned_before_it_is_required(self):
        self.assertIn('sudo apt-get install -y --no-install-recommends ffmpeg acl', WORKFLOW)
        self.assertLess(WORKFLOW.index('sudo apt-get install'), WORKFLOW.index('preflight.py --phase host'))
        self.assertLess(WORKFLOW.index('sudo apt-get install'), WORKFLOW.index('kvm_access.py grant'))

    def test_all_host_and_guest_checks_precede_capture_actions(self):
        self.assertLess(WORKFLOW.index('preflight.py --phase host'), WORKFLOW.index('kvm_access.py grant'))
        self.assertLess(RUNTIME.index('preflight.py --phase guest'), RUNTIME.index('connectedDebugAndroidTest'))
        cleanup = RUNTIME.split('cleanup() {', 1)[1].split('\n}', 1)[0]
        self.assertNotIn('adb pull', cleanup)
        self.assertIn('capture_result=$?', RUNTIME)

    def test_pinned_actions_no_secrets_or_application_artifact_paths(self):
        for action in re.findall(r'uses:\s*(\S+)', WORKFLOW):
            self.assertRegex(action, r'^actions/(checkout|setup-java|upload-artifact)@[0-9a-f]{40}$')
        self.assertIn('contents: read', WORKFLOW)
        self.assertIn('persist-credentials: false', WORKFLOW)
        self.assertIn('path: teaser-public-evidence/', WORKFLOW)
        self.assertIn('retention-days: 1', WORKFLOW)
        for forbidden in ('secrets.', 'environment:', 'contents: write', 'GH_TOKEN:', 'GITHUB_TOKEN:',
                          'app/build/outputs/apk/', 'gh release', 'git push', 'printenv'):
            self.assertNotIn(forbidden, WORKFLOW + RUNTIME)

    def test_runtime_is_syntax_valid_and_keeps_guest_nonroot(self):
        subprocess.run(['bash', '-n', str(ROOT / 'tools/capture/run-capture.sh')], check=True)
        self.assertIn('== 2000', RUNTIME)
        self.assertIn('-accel on', RUNTIME)
        self.assertIn('arm64-v8a', RUNTIME)
        self.assertIn('wait-software-ui-ready.py', RUNTIME)
        self.assertIn('1080', RUNTIME)

    def test_capture_manifest_does_not_claim_unexecuted_runtime(self):
        plan = json.loads((ROOT / 'tools/capture/feature-plan.json').read_text())
        self.assertEqual(SOURCE.BASE, plan['product_source_sha'])
        self.assertEqual(18, len(plan['clips']))
        self.assertEqual(3, sum(x['classification'] == 'development-preview' for x in plan['clips']))
        self.assertTrue(all(x['status'] == 'planned_not_runtime_verified' for x in plan['clips']))


class SourceTests(unittest.TestCase):
    def test_changed_product_object_fails_closed(self):
        with patch.object(SOURCE.subprocess, 'run'), patch.object(SOURCE, 'git', side_effect=['original', 'changed']):
            with self.assertRaisesRegex(RuntimeError, 'Product source changed'):
                SOURCE.verify()


class PreflightTests(unittest.TestCase):
    def test_missing_decoders_and_acl_tools_are_aggregated(self):
        gaps = PREFLIGHT.command_gaps(PREFLIGHT.HOST_COMMANDS, which=lambda name: None)
        for name in ('ffmpeg', 'ffprobe', 'getfacl', 'setfacl', 'timeout', 'ldd', 'git', 'python3'):
            self.assertIn('missing command: ' + name, gaps)
        self.assertEqual(len(PREFLIGHT.HOST_COMMANDS), len(gaps))

    def test_signer_presence_is_rejected_without_returning_values(self):
        result = PREFLIGHT.signer_gaps({'BOLD_DEBUG_STORE_PASSWORD': 'mock-do-not-log'})
        self.assertTrue(result)
        self.assertNotIn('mock-do-not-log', str(result))
        self.assertEqual([], PREFLIGHT.signer_gaps({}))

    def test_all_python_dependencies_are_standard_library(self):
        for name in PREFLIGHT.STDLIB:
            __import__(name)

    def test_guest_codec_smoke_rejects_fallback_or_blank_media(self):
        good = {'streams': [{'codec_name': 'h264', 'width': 1080, 'height': 1920, 'nb_read_frames': '3'}]}
        self.assertTrue(PREFLIGHT.validate_smoke(good))
        for change in ({'width':720}, {'height':1280}, {'nb_read_frames':'0'}, {'nb_read_frames':'N/A'}, {'codec_name':'mpeg4'}):
            self.assertFalse(PREFLIGHT.validate_smoke({'streams':[good['streams'][0] | change]}))
        self.assertTrue(PREFLIGHT.validate_smoke({'streams':[good['streams'][0] | {'nb_read_frames':'1'}]}))

    def test_uncommitted_product_change_fails_closed(self):
        with patch.object(SOURCE.subprocess, 'run'), patch.object(SOURCE, 'git',
                side_effect=['same'] * (2 * len(SOURCE.PRODUCT_PATHS)) + ['app/changed.kt']):
            with self.assertRaisesRegex(RuntimeError, 'Uncommitted product'):
                SOURCE.verify()


class EvidenceTests(unittest.TestCase):
    def fixture(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        (root / 'capture-output').mkdir()
        (root / 'tools/capture').mkdir(parents=True)
        (root / 'tools/capture/feature-plan.json').write_text('{}')
        return root

    def test_unknown_binaries_and_dumps_are_not_selected(self):
        for name in ('app.apk', 'signer.keystore', 'environment.json', 'environment.log', 'archive.zip', 'unrelated-geometry.json'):
            self.assertFalse(STAGE.allowed_capture_name(name), name)

    def test_renamed_archive_is_rejected_before_any_decoder(self):
        root = self.fixture()
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr('classes.dex', b'dex\n')
        (root / 'capture-output/01-renamed.png').write_bytes(data.getvalue())
        with patch.object(STAGE.subprocess, 'run', side_effect=AssertionError('Decoder must not receive packages')):
            report = STAGE.stage(root, root / 'evidence')
        self.assertEqual([], report['included'])
        self.assertEqual(1, len(report['excluded']))

    def test_symlink_cannot_export_outside_bytes(self):
        root = self.fixture()
        outside = root / 'secret'; outside.write_text('mock secret not for artifact')
        (root / 'capture-output/recording-01-typing.json').symlink_to(outside)
        report = STAGE.stage(root, root / 'evidence')
        self.assertEqual([], report['included'])

    def test_invalid_json_proof_is_not_published(self):
        root = self.fixture()
        (root / 'capture-output/recording-01-typing.json').write_text('{invalid')
        report = STAGE.stage(root, root / 'evidence')
        self.assertEqual([], report['included'])
        self.assertEqual(1, len(report['excluded']))

    def test_decode_requires_two_frames_and_bounded_duration(self):
        info = {'streams': [{'nb_read_frames': '1'}], 'format': {'duration': '3'}}
        with patch.object(STAGE.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, json.dumps(info))):
            with self.assertRaisesRegex(ValueError, 'two decoded'):
                STAGE.probe_video(Path('fixture-not-product.mp4'))
        info['streams'][0]['nb_read_frames'] = '3'; info['format']['duration'] = '999'
        with patch.object(STAGE.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, json.dumps(info))):
            with self.assertRaisesRegex(ValueError, 'duration'):
                STAGE.probe_video(Path('fixture-not-product.mp4'))

    def test_valid_decode_is_still_marked_for_visual_review(self):
        info = {'streams': [{'nb_read_frames': '3'}], 'format': {'duration': '3'}}
        outputs = [subprocess.CompletedProcess([], 0, json.dumps(info)),
                   subprocess.CompletedProcess([], 0, '# fixture only\n0,0,aaa\n0,1,bbb\n')]
        with patch.object(STAGE.subprocess, 'run', side_effect=outputs):
            result = STAGE.probe_video(Path('fixture-not-product.mp4'))
        self.assertTrue(result['decode_passed'])
        self.assertTrue(result['visual_review_required'])
        self.assertEqual(2, result['distinct_decoded_frames'])

    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'codec tools not installed locally')
    def test_real_codec_fixture_roundtrip_is_not_product_interaction_proof(self):
        # Synthetic codec test pattern ONLY, under TemporaryDirectory. Never
        # product UI, screenshot evidence, a captured gesture or a published file.
        root = self.fixture()
        source = root / 'capture-output/01-codec-fixture.mp4'
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=32x32:rate=4',
                        '-t', '1', '-filter_threads', '1', '-c:v', 'libx264', '-threads', '1',
                        '-pix_fmt', 'yuv420p', str(source)], capture_output=True, check=True)
        proof = {'recorder_startup_verified':True, 'action_assertions_passed':True,
                 'recorder_finalized':True, 'producer':'codec-test-fixture-not-product'}
        (root / 'capture-output/recording-01-codec-fixture.json').write_text(json.dumps(proof))
        original = source.read_bytes()
        report = STAGE.stage(root, root / 'evidence')
        self.assertEqual([], report['excluded'])
        self.assertEqual(1, len(report['clips']))
        clip = report['clips'][0]
        self.assertTrue(clip['decode_passed'])
        self.assertGreaterEqual(clip['distinct_decoded_frames'], 2)
        self.assertFalse(clip['assertions_passed'])
        self.assertEqual(original, (root / 'evidence' / clip['path']).read_bytes())
        self.assertTrue(all((root / 'evidence' / name).is_file() for name in clip['review_frames']))
