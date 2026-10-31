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
                    'complete_source_manifest_sha256':digest,'group_manifests':[['Fixture',1,digest]],
                    'class_files':1,'source_built_tool_sha256':policy.TOOL_SHA}
            java=Path('/usr/bin/java').resolve()
            with patch.object(candidate,'SOURCE_SHA',sha),patch.object(build,'SOURCE_SHA',sha),patch.object(candidate,'audit',return_value={'scope':'mock only; not PEPK execution'}):
                report=candidate.candidate(source,root/'candidate',java,record)
            self.assertEqual(report['status'],'UNPRIVILEGED_CANDIDATE_NOT_APPROVED_FOR_REAL_KEYS')
            self.assertTrue(report['two_clean_builds_identical'])
            self.assertFalse(report['matches_prior_audit'])
            self.assertNotEqual(report['derived_recipe_sha256'],policy.BUILD_SHA)
            self.assertEqual(report['base_recipe_sha256'],policy.BUILD_SHA)
            self.assertEqual(report['compiler_stack']['java_sha256'],hashlib.sha256(java.read_bytes()).hexdigest())
        self.assertEqual((ROOT/'tools/release/pepk-export-review.json').read_bytes(),before)

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
