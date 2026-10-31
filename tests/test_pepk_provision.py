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
from pepk_fixtures import disabled_record, approved_record


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

    def test_image_check_reads_only_public_metadata_and_exact_manifest(self):
        class Metadata(dict):
            def get(self,key,*args):
                if key not in ('RUNNER_OS','RUNNER_ARCH','ImageVersion'):raise AssertionError('Unexpected environment lookup')
                return super().get(key,*args)
        provision.check_host(self.record(),Metadata(self.env()),self.release())
        record=self.record();record['github_hosted_runtime']['installer_manifest_sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'SHA256 mismatch'):provision.check_host(record,self.env(),self.release())

    def test_image_architecture_os_drift_or_missing_metadata_rejected(self):
        for changed in [{'RUNNER_ARCH':'ARM64'},{'RUNNER_OS':'Windows'},{'RUNNER_OS':None},{'RUNNER_ARCH':None}]:
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
        with patch.object(provision,'check_host'),patch.object(provision,'verify_toolchain',return_value=java),patch.object(provision,'tree_identity',return_value={'sha256':record['github_hosted_runtime']['java_tree_sha256']}),patch.object(provision,'reviewed_build_recipe') as recipe,patch.object(provision,'probe_python_child',return_value=child),patch.object(provision,'enforce_runtime_identity') as enforce,patch.object(provision,'verify_reviewed_java_runtime') as java_runtime:
            provision.verify_protected_runtime(record)
            recipe.assert_called_once_with(java,record);enforce.assert_called_once_with(record['toolchain'],child)
            java_runtime.assert_called_once_with(record,java)
            recipe.side_effect=ValueError('recipe drift');enforce.reset_mock()
            with self.assertRaisesRegex(ValueError,'recipe drift'):provision.verify_protected_runtime(record)
            enforce.assert_not_called()

    def test_diagnostics_allowlist_excludes_credentials_and_exception_payload(self):
        class PublicOnly(dict):
            def get(self,key,*args):
                if key not in ('RUNNER_OS','RUNNER_ARCH','ImageVersion'):raise AssertionError('Credential lookup forbidden')
                return super().get(key,*args)
        facts=provision.public_host_diagnostics(self.record(),ValueError('PRIVATE-DUMMY-PAYLOAD'),PublicOnly(self.env()),self.release())
        self.assertEqual(set(facts['checks']),{'RUNNER_OS','RUNNER_ARCH','ImageVersion','os_release.ID','os_release.VERSION_ID','installer_manifest_sha256'})
        self.assertIsNone(facts['first_nonmatching_public_field']);self.assertEqual(facts['failure_category'],'ValueError')
        self.assertNotIn('PRIVATE-DUMMY-PAYLOAD',json.dumps(facts))
        facts=provision.public_host_diagnostics(self.record(),RuntimeError('PRIVATE-DUMMY-PAYLOAD'),self.env()|{'ImageVersion':'unsafe:PRIVATE-DUMMY-PAYLOAD\n'},self.release())
        self.assertEqual(facts['failure_category'],'OtherError')
        self.assertEqual(facts['checks']['ImageVersion']['observed'],'<invalid>')
        self.assertNotIn('PRIVATE-DUMMY-PAYLOAD',json.dumps(facts))

    def test_diagnostics_identify_each_public_comparison_without_repinning(self):
        record=self.record();before=copy.deepcopy(record)
        for key in ('RUNNER_OS','RUNNER_ARCH'):
            facts=provision.public_host_diagnostics(record,ValueError(),self.env()|{key:None},self.release())
            self.assertEqual(facts['first_nonmatching_public_field'],key)
        for key in ('ID','VERSION_ID'):
            facts=provision.public_host_diagnostics(record,ValueError(),self.env(),self.release()|{key:'different'})
            self.assertEqual(facts['first_nonmatching_public_field'],'os_release.'+key)
        wrong=copy.deepcopy(record);wrong['github_hosted_runtime']['installer_manifest_sha256']='0'*64
        facts=provision.public_host_diagnostics(wrong,ValueError(),self.env(),self.release())
        self.assertEqual(facts['first_nonmatching_public_field'],'installer_manifest_sha256')
        self.assertEqual(record,before)

    def test_diagnostics_report_safe_read_categories_and_python_version(self):
        record=self.record()
        with patch.object(Path,'read_text',side_effect=PermissionError('PRIVATE-DUMMY-PAYLOAD')):
            facts=provision.public_host_diagnostics(record,ValueError(),self.env())
        self.assertEqual(facts['os_release_read_error'],'PermissionError')
        self.assertNotIn('PRIVATE-DUMMY-PAYLOAD',json.dumps(facts))
        for error in [FileNotFoundError('PRIVATE-DUMMY-PAYLOAD'),AttributeError('PRIVATE-DUMMY-PAYLOAD')]:
            with patch.object(provision,'digest',side_effect=error):
                facts=provision.public_host_diagnostics(record,error,self.env(),self.release())
            self.assertEqual(facts['manifest_read_error'],type(error).__name__)
            self.assertIsNone(facts['checks']['installer_manifest_sha256']['observed'])
            self.assertEqual(facts['system_python_version'],'.'.join(map(str,sys.version_info[:3])))
            self.assertNotIn('PRIVATE-DUMMY-PAYLOAD',json.dumps(facts))

    def test_only_presecret_check_host_cli_emits_public_diagnostics(self):
        import subprocess
        from pepk_runtime import python_child_env
        env=python_child_env()|self.env()|{'RUNNER_OS':'WrongOS','BOLD_RELEASE_STORE_PASSWORD':'PRIVATE-DUMMY-PAYLOAD','BOLD_PEPK_DELIVERY_TOKEN':'PRIVATE-DUMMY-PAYLOAD'}
        for flag,diagnostic in [('--check-host',True),('--verify-runtime',False)]:
            result=subprocess.run([sys.executable,str(ROOT/'tools/release/pepk_provision.py'),flag],env=env,capture_output=True,text=True,timeout=30)
            self.assertEqual(result.returncode,1)
            self.assertEqual('Public host-check diagnostics:' in result.stderr,diagnostic)
            self.assertNotIn('PRIVATE-DUMMY-PAYLOAD',result.stdout+result.stderr)
            if diagnostic:
                line=next(line for line in result.stderr.splitlines() if line.startswith('Public host-check diagnostics:'))
                facts=json.loads(line.split(': ',1)[1]);self.assertEqual(facts['first_nonmatching_public_field'],'RUNNER_OS')

    def test_explicit_policy_states_do_not_depend_on_live_approval(self):
        record=self.record();before=copy.deepcopy(record)
        with self.assertRaises(ValueError):policy.validate_ready(disabled_record(record))
        policy.validate_ready(approved_record(record))
        self.assertEqual(record,before)

    def test_public_facts_observe_new_raw_image_without_promoting_it(self):
        record=self.record();before=copy.deepcopy(record)
        env=self.env()|{'ImageVersion':'20261026.354.1'}
        facts=provision.public_host_facts(record,env,self.release())
        self.assertEqual(facts['checks']['ImageVersion'],{'observed':'20261026.354.1','expected':'20261025.353.1','matches':False,'enforced':False})
        provision.check_host(record,env,self.release())
        self.assertIsNone(provision.public_host_diagnostics(record,ValueError(),env,self.release())['first_nonmatching_public_field'])
        self.assertEqual(record,before)

    def test_coarse_image_label_is_informational_but_contract_is_required(self):
        for label in ['20261026.354.1','future-image','',None]:
            provision.check_host(self.record(),self.env()|{'ImageVersion':label},self.release())
        record=self.record();record['github_hosted_runtime'].pop('host_contract')
        with self.assertRaisesRegex(ValueError,'component host contract'):
            provision.check_host(record,self.env(),self.release())

    def test_java_failure_propagates_after_all_existing_runtime_checks(self):
        record=self.record();java=Path('/public/jdk/bin/java')
        with patch.object(provision,'check_host'),patch.object(provision,'verify_toolchain',return_value=java),patch.object(provision,'tree_identity',return_value={'sha256':record['github_hosted_runtime']['java_tree_sha256']}),patch.object(provision,'reviewed_build_recipe'),patch.object(provision,'probe_python_child'),patch.object(provision,'enforce_runtime_identity'),patch.object(provision,'verify_reviewed_java_runtime',side_effect=ValueError('Java host changed')):
            with self.assertRaisesRegex(ValueError,'Java host changed'):
                provision.verify_protected_runtime(record)
