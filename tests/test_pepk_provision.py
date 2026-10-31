"""Staged hosted-runtime policy tests; no workflow dispatch or credential access."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/release'))
import pepk_provision as provision
import pepk_policy as policy


class ProvisionChecks(unittest.TestCase):
    def record(self):return json.loads((ROOT/'tools/release/pepk-export-review.json').read_text())
    def env(self):return {'RUNNER_OS':'Linux','RUNNER_ARCH':'X64','ImageVersion':'20261025.353.1'}
    def release(self):return {'ID':'ubuntu','VERSION_ID':'24.04'}

    def test_staged_pins_match_reviewed_github_host_not_local_debian(self):
        record=self.record();pins=record['toolchain'];host=record['github_hosted_runtime']
        self.assertEqual(host['evidence_commit'],'2b2dd4c764e83c86cdcafe185ed4cffe12476847')
        self.assertEqual(host['evidence_report_sha256'],'379d4e1132d5cd3cbd21078299c826186f5e8a09c84718f4f9b49485ddd0f052')
        self.assertEqual(host['installer_manifest_sha256'],hashlib.sha256((ROOT/'tools/release/pepk-candidate-runtime.json').read_bytes()).hexdigest())
        self.assertEqual(host['java_tree_sha256'],'46445321ecee9b8967c9fced8d882ef37289092d3ae794c6e9ee9bc967d7c185')
        self.assertEqual(len(pins['native_library_pins']),41)
        self.assertEqual(pins['zlib_runtime'],'1.3')
        self.assertEqual(pins['native_library_pins']['host:/usr/lib/x86_64-linux-gnu/libz.so.1.3'],
                         'f61677c7bdb7c2aa2a243ea781bfd16afcb361cede7b4ef76127a9d8eb9a5242')
        self.assertNotIn('85590dd58edf5445e18bc7193e5ebc01ac5841f1ae187e97705a662e90c6421e',pins['native_library_pins'].values())
        self.assertEqual(pins['java_realpath'],'/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/21.0.13-9.0.LTS/x64/bin/java')
        _,derived=provision.reviewed_build_recipe(Path(pins['java_realpath']),record)
        self.assertEqual(derived,'6a1e635d5926ac0a7a0406eed88da3f7f9b13f2d816cbe86d9bf426f4eca61ab')
        self.assertEqual(record['expected_certificate_sha256'],policy.CERT)
        self.assertFalse(record['export_implementation_reviewed'])
        self.assertIsNone(record['independent_production_review_id']);self.assertIsNone(record['private_delivery_review_id'])
        with self.assertRaises(ValueError):policy.validate_ready(record)

    def test_image_check_reads_only_public_metadata_and_exact_manifest(self):
        class Metadata(dict):
            def get(self,key,*args):
                if key not in ('RUNNER_OS','RUNNER_ARCH','ImageVersion'):raise AssertionError('Unexpected environment lookup')
                return super().get(key,*args)
        provision.check_host(self.record(),Metadata(self.env()),self.release())
        record=self.record();record['github_hosted_runtime']['installer_manifest_sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'SHA256 mismatch'):provision.check_host(record,self.env(),self.release())

    def test_image_architecture_os_drift_or_missing_metadata_rejected(self):
        for changed in [{'ImageVersion':'20261026.1'},{'RUNNER_ARCH':'ARM64'},{'RUNNER_OS':'Windows'},{'ImageVersion':''}]:
            with self.subTest(changed=changed),self.assertRaisesRegex(ValueError,'image/architecture'):
                provision.check_host(self.record(),self.env()|changed,self.release())
        for release in [{'ID':'debian','VERSION_ID':'13'},{'ID':'ubuntu','VERSION_ID':'22.04'},{}]:
            with self.subTest(release=release),self.assertRaisesRegex(ValueError,'operating system'):
                provision.check_host(self.record(),self.env(),release)

    def test_host_rejection_precedes_any_runtime_probe(self):
        with patch.object(provision,'check_host',side_effect=ValueError('host drift')),patch.object(provision,'verify_toolchain') as verify:
            with self.assertRaisesRegex(ValueError,'host drift'):provision.verify_protected_runtime(self.record())
            verify.assert_not_called()

    def test_complete_jdk_tree_must_match_before_recipe_or_child(self):
        with patch.object(provision,'check_host'),patch.object(provision,'verify_toolchain',return_value=Path('/public/jdk/bin/java')),patch.object(provision,'tree_identity',return_value={'sha256':'0'*64}),patch.object(provision,'reviewed_build_recipe') as recipe,patch.object(provision,'probe_python_child') as child:
            with self.assertRaisesRegex(ValueError,'JDK tree'):provision.verify_protected_runtime(self.record())
            recipe.assert_not_called();child.assert_not_called()

    def test_runtime_recipe_and_live_child_are_all_enforced(self):
        record=self.record();child={'scope':'mocked public child'};java=Path('/public/jdk/bin/java')
        with patch.object(provision,'check_host'),patch.object(provision,'verify_toolchain',return_value=java),patch.object(provision,'tree_identity',return_value={'sha256':record['github_hosted_runtime']['java_tree_sha256']}),patch.object(provision,'reviewed_build_recipe') as recipe,patch.object(provision,'probe_python_child',return_value=child),patch.object(provision,'enforce_runtime_identity') as enforce:
            provision.verify_protected_runtime(record)
            recipe.assert_called_once_with(java,record);enforce.assert_called_once_with(record['toolchain'],child)
            recipe.side_effect=ValueError('recipe drift');enforce.reset_mock()
            with self.assertRaisesRegex(ValueError,'recipe drift'):provision.verify_protected_runtime(record)
            enforce.assert_not_called()
