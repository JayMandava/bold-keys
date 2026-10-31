"""Credential-free policy/workflow checks. No real GitHub dispatch or signing secrets."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/release'))
import pepk_preflight as pepk
import pepk_policy as policy
import pepk_public as public
import pepk_export as exporter
SHA='a'*40


class PepkWorkflowChecks(unittest.TestCase):
    def env(self):
        return dict(GITHUB_EVENT_NAME='workflow_dispatch',GITHUB_REPOSITORY='JayMandava/bold-keys',
                    GITHUB_REF='refs/heads/main',GITHUB_SHA=SHA,GITHUB_WORKFLOW_SHA=SHA)

    def record(self):return json.loads(pepk.RECORD.read_text())

    def test_missing_authenticated_pem_still_blocks(self):
        with self.assertRaisesRegex(ValueError,'Console encryption PEM'):
            policy.validate_ready(self.record()|{'console_encryption_pem_sha256':None})

    def test_final_review_cannot_be_skipped_by_filling_public_pins(self):
        record=self.record() | {'console_encryption_pem_sha256':'a'*64,'third_party_notices_sha256':'b'*64}
        with self.assertRaisesRegex(ValueError,'independent production'):
            policy.validate_ready(record)
        for name in ('source_licence_review_id','controlled_pty_protocol_review_id','private_delivery_review_id',
                     'independent_production_review_id'):
            record[name]='synthetic-review'
        with self.assertRaisesRegex(ValueError,'implementation review'):
            policy.validate_ready(record)
        record['export_implementation_reviewed']=True
        with self.assertRaisesRegex(ValueError,'disabled'):
            policy.validate_ready(record)

    def test_blocked_export_does_not_read_secret_environment_or_create_files(self):
        class NoSecretReads:
            def get(self,name):raise AssertionError('Environment must not be read before readiness')
        with tempfile.TemporaryDirectory() as temp,patch.object(exporter,'verify_payload') as verify:
            with self.assertRaisesRegex(ValueError,'notice payload'):
                exporter.export_reviewed(Path(temp)/'payload',Path(temp)/'out',self.record(),SHA,Path(temp),NoSecretReads())
            self.assertEqual(list(Path(temp).iterdir()),[])
            verify.assert_not_called()

    def test_wrong_source_or_certificate_pin_rejected(self):
        for key,value in [('expected_certificate_sha256','0'*64),('official_source_sha256','0'*64),
                          ('source_built_tool_sha256','0'*64),('build_script_sha256','0'*64)]:
            with self.subTest(key=key),self.assertRaises(ValueError):policy.validate_ready(self.record()|{key:value})

    def test_dispatch_context_rejected_before_api(self):
        for key,value in [('GITHUB_EVENT_NAME','push'),('GITHUB_REPOSITORY','other/fork'),
                          ('GITHUB_REF','refs/heads/review'),('GITHUB_SHA','b'*40),('GITHUB_WORKFLOW_SHA','b'*40)]:
            with self.subTest(key=key),patch.dict(os.environ,self.env()|{key:value}),patch.object(pepk.release,'api') as api,self.assertRaises(ValueError):pepk.preflight(SHA)
            api.assert_not_called()

    def test_moved_main_rejected(self):
        with patch.dict(os.environ,self.env()),patch.object(pepk.release,'api',return_value={'object':{'sha':'b'*40}}),patch.object(pepk.release,'gate') as gate,self.assertRaisesRegex(ValueError,'Main moved'):pepk.preflight(SHA)
        gate.assert_not_called()

    def test_public_repository_cannot_be_called_private_artifact_delivery(self):
        with patch.dict(os.environ,self.env()),patch.object(pepk.release,'api',side_effect=[{'object':{'sha':SHA}},{'private':False}]),patch.object(pepk.release,'gate') as gate,self.assertRaisesRegex(ValueError,'Private artifact delivery'):pepk.preflight(SHA)
        gate.assert_called_once_with()

    def test_private_repository_still_needs_pem_and_independent_review(self):
        with patch.dict(os.environ,self.env()),patch.object(pepk.release,'api',side_effect=[{'object':{'sha':SHA}},{'private':True}]),patch.object(pepk.release,'gate'),self.assertRaisesRegex(ValueError,'notice payload'):pepk.preflight(SHA)

    def test_public_byte_pins_reject_corruption_missing_pin_and_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'fixture';path.write_bytes(b'public fixture');digest=hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(public.verify_public_file(path,digest,14),digest)
            for pin,size in [(None,14),('0'*64,14),(digest,15)]:
                with self.subTest(pin=pin,size=size),self.assertRaises(ValueError):public.verify_public_file(path,pin,size)
            link=Path(temp)/'link';link.symlink_to(path)
            with self.assertRaises(ValueError):public.verify_public_file(link,digest)

    def test_workflow_secrets_scoped_and_public_build_precedes_gate(self):
        text=(ROOT/'.github/workflows/pepk-export.yml').read_text()
        import re
        self.assertTrue(all(re.fullmatch(r'actions/[a-z-]+@[a-f0-9]{40}',action) for action in re.findall(r'uses: ([^\s]+)',text)))
        self.assertNotIn('push:',text);self.assertNotIn('pull_request',text);self.assertNotIn('contents: write',text)
        public_job=text.split('  prepare_public:')[1].split('  export:')[0]
        self.assertNotIn('environment:',public_job);self.assertNotIn('${{ secrets.',public_job)
        self.assertIn('pepk_build.py --verify-toolchain',public_job);self.assertNotIn('--location',public_job)
        self.assertIn('  export:\n    needs: prepare_public',text)
        self.assertIn('environment: production-release',text)
        self.assertEqual(text.count('pepk_preflight.py --commit'),2)
        secret=text.split('- name: Export only encrypted existing key')[1].split('- name: Reverify exact encrypted-only')[0]
        self.assertEqual(text.count('${{ secrets.'),4);self.assertEqual(secret.count('${{ secrets.'),4)
        for forbidden in ['curl ','javac','gradlew','GH_TOKEN','--storepass=','--keypass=']:
            self.assertNotIn(forbidden,secret)
        self.assertIn('set +x',secret);self.assertIn('trap ',secret)
        self.assertIn('digest-mismatch: error',text);self.assertEqual(text.count('retention-days: 1'),2)
        final=text.split('name: encrypted-existing-key-')[1].split('- name: Remove private')[0]
        self.assertNotIn('pepk.jar',final);self.assertNotIn('keystore',final)
        for name in exporter.EXPORT_NAMES:self.assertIn('/'+name,final)
        for module in ['pepk_export.py','pepk_policy.py','pepk_terminal.py','pepk_public.py']:
            self.assertNotIn('import release',(ROOT/'tools/release'/module).read_text())
            self.assertNotIn('data_pipeline',(ROOT/'tools/release'/module).read_text())
