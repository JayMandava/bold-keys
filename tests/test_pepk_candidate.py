"""Candidate-path tests. Actual PEPK integration still needs the public source archive."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/release'))
import pepk_build as build
import pepk_candidate as candidate
import pepk_disposable_audit as disposable
import pepk_export as exporter
import pepk_policy as policy


def fixture_java(system_java=Path('/usr/bin/java')):
    """Use the workflow-provisioned JDK; local fallback remains Java21-validated."""
    home=os.environ.get('JAVA_HOME')
    return (Path(home)/'bin/java' if home else system_java).resolve(strict=True)


class PublicCandidateChecks(unittest.TestCase):
    def test_new_candidate_recipe_does_not_promote_or_change_production_pins(self):
        before=(ROOT/'tools/release/pepk-export-review.json').read_bytes()
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'source.jar';original=b'public class Fixture {}\n'
            with zipfile.ZipFile(source,'w') as archive:archive.writestr('Fixture.java',original)
            sha=hashlib.sha256(source.read_bytes()).hexdigest()
            tree=root/'manifest';tree.mkdir();(tree/'Fixture.java').write_bytes(original)
            digest=build.source_manifest(tree,[tree/'Fixture.java'])
            record={'official_source_size_bytes':source.stat().st_size,'java_sources':1,
                    'complete_source_manifest_sha256':build.source_manifest(tree,[tree/'Fixture.java'],'source/'),'group_manifests':[['Fixture',1,digest]],
                    'class_files':1,'source_built_tool_sha256':policy.TOOL_SHA,
                    'identity_source_sha256':hashlib.sha256((ROOT/'tools/release/PepkIdentity.java').read_bytes()).hexdigest()}
            java=fixture_java()
            with patch.object(candidate,'SOURCE_SHA',sha),patch.object(build,'SOURCE_SHA',sha),patch.object(candidate,'audit',return_value={'scope':'mock only; not PEPK execution'}),patch('pepk_driver_audit.audit_driver',return_value={'scope':'mock only; not real driver'}),patch.object(candidate,'public_host_facts',return_value={'checks':{'ImageVersion':{'observed':'20261026.354.1','matches':False}}}) as host,patch.object(candidate,'java_runtime_evidence',return_value={'scope':'mock only; real Java probe tested separately'}) as java_probe:
                report=candidate.candidate(source,root/'candidate',java,record)
            host.assert_called_once_with(record)
            java_probe.assert_called_once()
            self.assertEqual(report['public_host_observation']['checks']['ImageVersion']['observed'],'20261026.354.1')
            self.assertFalse(report['public_host_observation']['checks']['ImageVersion']['matches'])
            self.assertEqual(report['status'],'UNPRIVILEGED_CANDIDATE_NOT_APPROVED_FOR_REAL_KEYS')
            self.assertTrue(report['two_clean_builds_identical'])
            self.assertFalse(report['matches_prior_audit'])
            self.assertNotEqual(report['derived_recipe_sha256'],policy.BUILD_SHA)
            self.assertEqual(report['base_recipe_sha256'],policy.BUILD_SHA)
            self.assertEqual(report['compiler_stack']['java_sha256'],hashlib.sha256(java.read_bytes()).hexdigest())
        self.assertEqual((ROOT/'tools/release/pepk-export-review.json').read_bytes(),before)

    def test_fixture_prefers_configured_java21_over_different_system_java(self):
        # Synthetic version reporters reproduce runner selection, not actual JDK execution.
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);home=root/'configured-jdk';java=home/'bin/java'
            java.parent.mkdir(parents=True);(home/'lib').mkdir();(home/'lib/modules').write_bytes(b'fixture modules')
            system=root/'system-java'
            for executable,version in [(java,'21.0.13'),(system,'17.0.16')]:
                executable.write_text(f"#!/bin/sh\necho 'openjdk version \"{version}\"' >&2\n")
                executable.chmod(0o700)
            with patch.dict(os.environ,{'JAVA_HOME':str(home)}):
                selected=fixture_java(system)
                self.assertEqual(selected,java.resolve())
                self.assertEqual(candidate.compiler_provenance(selected)['java_path'],str(java.resolve()))
                with self.assertRaisesRegex(ValueError,'Java21'):
                    candidate.compiler_provenance(system)
                # Verify the original build fixture passes this selection to the builder.
                class SelectionObserved(Exception):pass
                with patch.object(candidate,'candidate',side_effect=SelectionObserved) as builder:
                    with self.assertRaises(SelectionObserved):
                        self.test_new_candidate_recipe_does_not_promote_or_change_production_pins()
                self.assertEqual(builder.call_args.args[2],java.resolve())
            with patch.dict(os.environ,{'JAVA_HOME':str(root/'missing-jdk')}):
                with self.assertRaises(FileNotFoundError):fixture_java(system)
            with patch.dict(os.environ,{},clear=True):
                self.assertEqual(fixture_java(system),system.resolve())

    def test_independent_crypto_fixture_unwrap_and_corruption(self):
        # Synthetic encryption fixture validates the independent checker, NOT PEPK's implementation.
        from cryptography.hazmat.primitives import hashes,serialization
        from cryptography.hazmat.primitives.asymmetric import rsa,padding
        from cryptography.hazmat.primitives.keywrap import aes_key_wrap_with_padding
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        recipient=rsa.generate_private_key(public_exponent=65537,key_size=3072)
        der=key.private_bytes(serialization.Encoding.DER,serialization.PrivateFormat.PKCS8,serialization.NoEncryption())
        aes=os.urandom(32)
        encrypted=recipient.public_key().encrypt(aes,padding.OAEP(mgf=padding.MGF1(hashes.SHA1()),algorithm=hashes.SHA1(),label=None))+aes_key_wrap_with_padding(aes,der)
        actual=disposable.independent_unwrap(encrypted,recipient)
        self.assertEqual(actual.private_numbers(),key.private_numbers())
        changed=encrypted[:-1]+bytes([encrypted[-1]^1])
        with self.assertRaises(Exception):disposable.independent_unwrap(changed,recipient)

    def test_owner_supplied_console_public_pem_hash_and_rsa_shape(self):
        record=json.loads((ROOT/'tools/release/pepk-export-review.json').read_text())
        path=ROOT/'tools/release/pepk-console-encryption.pem'
        self.assertEqual(path.stat().st_size,625)
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(),'ac50890bea088f457fd6ebdb56fd48ed1c58f42b4e7c380b2608ec9b2085e623')
        self.assertEqual(exporter.recipient_bytes(path),384)
        from cryptography.hazmat.primitives import serialization
        key=serialization.load_pem_public_key(path.read_bytes())
        self.assertEqual(key.key_size,3072);self.assertEqual(key.public_numbers().e,65537)
        der=key.public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)
        self.assertEqual(hashlib.sha256(der).hexdigest(),record['console_encryption_pem_spki_sha256'])
        self.assertNotEqual(record['console_encryption_pem_sha256'],record['expected_certificate_sha256'])

    def test_public_workflow_has_no_secret_gate_or_binary_upload(self):
        text=(ROOT/'.github/workflows/pepk-public-audit.yml').read_text()
        self.assertNotIn('${{ secrets.',text);self.assertNotIn('environment:',text)
        self.assertNotIn('upload-artifact@',text);self.assertNotIn('BOLD_RELEASE_',text)
        self.assertIn('verify-signature: true',text);self.assertIn('force-download: true',text)
        self.assertIn('steps.java.outputs.version',text);self.assertNotIn('--location',text)
        self.assertIn('pepk_candidate.py',text);self.assertNotIn('export_reviewed(',text)
        self.assertNotIn('BOLD_RELEASE_',(ROOT/'tools/release/pepk_disposable_audit.py').read_text())

    def test_driver_harness_counts_real_guard_failure_as_expected_rejection(self):
        # Harness-only regression: no PEPK execution/crypto claim. The real pin checker
        # raises GuardFailure after the harness tampers with its synthetic helper.
        import pepk_driver_audit as driver
        from pepk_terminal import verify_trusted_files
        from unittest.mock import Mock
        baseline=Mock();baseline.private_numbers.return_value=('synthetic-baseline',)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);java=root/'jdk/bin/java';java.parent.mkdir(parents=True);java.write_bytes(b'fixture-java')
            modules=root/'jdk/lib/modules';modules.parent.mkdir();modules.write_bytes(b'fixture-modules')
            jar=root/'public.jar';jar.write_bytes(b'fixture-jar')
            classes=root/'classes';classes.mkdir();(classes/'PepkIdentity.class').write_bytes(b'fixture-helper')
            def identity(argv,env):
                if '-genkeypair' in argv:
                    Path(argv[argv.index('-keystore')+1]).write_bytes(b'synthetic-container')
                else:
                    Path(argv[-2]).write_bytes(b'synthetic-baseline')
                    Path(argv[-1]).write_bytes(b'synthetic-public-cert')
            def export(payload,output,record,commit,runner_temp,env):
                verify_trusted_files({str(payload/'PepkIdentity.class'):record['identity_class_sha256']})
                if output.exists():raise ValueError('existing fixture')
                if env['PRIVATE_DELIVERY_CHECKED']!='true' or 'wrong-fixture-only' in env.values():
                    raise ValueError('rejected synthetic input')
                output.mkdir()
                with zipfile.ZipFile(output/'encrypted-export.zip','w') as z:
                    z.writestr('encryptedPrivateKey',output.name.encode())
                (output/'public-certificate.pem').write_bytes(b'synthetic-public-cert')
                (output/'SHA256SUMS').write_bytes(b'synthetic-sums')
                (output/'export-provenance.json').write_bytes(b'{}')
            def verify(output,*args):
                if (output/'public-certificate.pem').read_bytes().endswith(b'bad'):
                    raise ValueError('tampered synthetic cert')
            with patch.object(driver,'java_runtime_evidence',return_value={}), \
                 patch.object(driver,'java_contract_from_evidence',return_value={'scope':'mocked harness only'}), \
                 patch.object(driver.exporter,'run_identity_quiet',side_effect=identity), \
                 patch.object(driver.exporter,'export_reviewed',side_effect=export), \
                 patch.object(driver.exporter,'verify_export_output',side_effect=verify), \
                 patch('cryptography.hazmat.primitives.serialization.load_der_private_key',return_value=baseline), \
                 patch.object(driver,'independent_unwrap',return_value=baseline):
                report=driver.audit_driver(jar,java,classes,hashlib.sha256(jar.read_bytes()).hexdigest(),{})
            self.assertEqual(report['checks_passed'],20)
            self.assertIn('driver-helper-pin-tamper-rejected',report['checks'])
            self.assertTrue(report['private_fixtures_removed'])
